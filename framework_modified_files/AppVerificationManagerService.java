package com.android.server.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.app.IAppVerificationManager;

import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 核心服务：负责白名单管理、启动拦截、配置持久化以及安装事件监听
 */
public class AppVerificationManagerService extends IAppVerificationManager.Stub {
    private static final String TAG = "AppVerifyService";
    private static final String XML_FILE_PATH = "/data/system/app_verification_whitelist.xml";
    
    // 自定义内部广播 Action，用于通知客户端
    private static final String ACTION_AVS_EVENT = "com.android.appverify.ACTION_EVENT";

    private final Context mContext;
    private final Object mLock = new Object();
    
    // 内存中的白名单缓存
    private final Map<String, String> mWhitelist = new HashMap<>();
    
    // 全局开关
    private boolean mEnabled = false;

    private static AppVerificationManagerService sInstance;

    public AppVerificationManagerService(Context context) {
        mContext = context;
        sInstance = this;
        readConfigFile(); // 启动时读取配置
        
        // 【核心修改】在 SystemServer 启动时直接注册系统级监听
        registerSystemEventListener();
    }

    public static AppVerificationManagerService getInstance() {
        return sInstance;
    }

    // =============================================================
    // 新增：系统事件监听与转发
    // =============================================================
    
    private void registerSystemEventListener() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        
        // 在 SystemServer 上下文中注册，拥有最高权限，必定能收到
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Uri data = intent.getData();
                if (data == null) return;
                String pkg = data.getSchemeSpecificPart();
                
                // 排除自己
                if ("com.android.appverify".equals(pkg)) return;

                boolean isAdded = Intent.ACTION_PACKAGE_ADDED.equals(action);
                
                Slog.i(TAG, "System Monitor: Detected " + (isAdded ? "Install" : "Uninstall") + " -> " + pkg);

                // 转发给 AppVerifyManager 客户端
                notifyClientApp(pkg, isAdded ? 1 : 2);
            }
        }, filter);
    }

    private void notifyClientApp(String pkg, int type) {
        long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(ACTION_AVS_EVENT);
            // 显式指定包名，绕过 Android 11 的静态广播限制
            intent.setPackage("com.android.appverify"); 
            intent.putExtra("pkg", pkg);
            intent.putExtra("type", type); // 1=Add, 2=Remove
            
            // 确保即使 App 已停止也能收到
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            mContext.sendBroadcast(intent);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // =============================================================
    // AIDL 接口实现
    // =============================================================

    @Override
    public boolean verifyAppStart(String packageName) {
        // 1. 自身豁免
        if ("com.android.appverify".equals(packageName)) return true;

        synchronized (mLock) {
            // 2. 开关检查
            if (!mEnabled) return true;

            // 3. 系统应用豁免
            if (isSystemApp(packageName)) return true;

            // 4. 白名单检查
            return mWhitelist.containsKey(packageName);
        }
    }

    @Override
    public void addAppToWhitelist(String packageName) {
        enforcePermission();
        synchronized (mLock) {
            mWhitelist.put(packageName, "trusted");
            writeConfigFile();
        }
        Slog.i(TAG, "Added to whitelist: " + packageName);
    }

    @Override
    public void removeAppFromWhitelist(String packageName) {
        enforcePermission();
        synchronized (mLock) {
            if (mWhitelist.containsKey(packageName)) {
                mWhitelist.remove(packageName);
                writeConfigFile();
            }
        }
        Slog.i(TAG, "Removed from whitelist: " + packageName);
    }

    @Override
    public Map getWhitelist() {
        enforcePermission();
        synchronized (mLock) {
            return new HashMap<>(mWhitelist);
        }
    }

    @Override
    public void setVerificationMode(boolean enabled) {
        enforcePermission();
        synchronized (mLock) {
            mEnabled = enabled;
            writeConfigFile();
        }
        Slog.i(TAG, "Verification mode set to: " + enabled);
    }

    @Override
    public boolean getVerificationMode() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    // =============================================================
    // 辅助方法
    // =============================================================

    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_VERIFICATION,
                "AppVerificationManagerService");
    }

    private boolean isSystemApp(String packageName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                   (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // =============================================================
    // XML 持久化存储
    // =============================================================

    private void readConfigFile() {
        synchronized (mLock) {
            mWhitelist.clear();
            File file = new File(XML_FILE_PATH);
            if (!file.exists()) return;

            try (FileInputStream stream = new AtomicFile(file).openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG) {
                        String tag = parser.getName();
                        if ("config".equals(tag)) {
                            String enabled = parser.getAttributeValue(null, "enabled");
                            mEnabled = Boolean.parseBoolean(enabled);
                        } else if ("item".equals(tag)) {
                            String pkg = parser.getAttributeValue(null, "package");
                            if (pkg != null) mWhitelist.put(pkg, "trusted");
                        }
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to read config", e);
            }
        }
    }

    private void writeConfigFile() {
        synchronized (mLock) {
            try {
                AtomicFile atomicFile = new AtomicFile(new File(XML_FILE_PATH));
                FileOutputStream stream = atomicFile.startWrite();
                
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                
                out.startTag(null, "config");
                out.attribute(null, "enabled", String.valueOf(mEnabled));

                for (String pkg : mWhitelist.keySet()) {
                    out.startTag(null, "item");
                    out.attribute(null, "package", pkg);
                    out.endTag(null, "item");
                }

                out.endTag(null, "config");
                out.endDocument();
                atomicFile.finishWrite(stream);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to write config", e);
            }
        }
    }
}
