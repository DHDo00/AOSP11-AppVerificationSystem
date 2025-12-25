package com.android.server.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.IAppVerificationManager;
import android.os.Binder;
import android.os.Environment;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 11 应用验证服务
 */
public class AppVerificationManagerService extends IAppVerificationManager.Stub {

    private static final String TAG = "AppVerifyService";
    private static final boolean DEBUG = true;

    // [修复点 1] 定义静态实例变量
    private static AppVerificationManagerService sInstance;

    // XML 存储路径
    private final AtomicFile POLICY_FILE = new AtomicFile(new File(
            Environment.getDataSystemDirectory(), "app_verification_whitelist.xml"));

    private final Context mContext;
    private final Object mLock = new Object();

    // 存储结构: PackageName -> SignatureHash (SHA-256)
    private final Map<String, String> mWhitelist = new HashMap<>();

    // 0: Disabled, 1: Whitelist Mode
    private int mVerificationMode = 1; 

    // 广播定义
    private static final String ACTION_EVENT_NOTIFY = "com.android.appverify.ACTION_EVENT";
    private static final String TARGET_PACKAGE = "com.android.appverify";

    public AppVerificationManagerService(Context context) {
        mContext = context;
        // [修复点 2] 在构造函数中赋值实例
        sInstance = this;

        // 读取配置
        readPolicy();
        // 注册监听
        registerPackageMonitor();
        Slog.i(TAG, "AppVerificationManagerService started. Mode: " + mVerificationMode);
    }

    // [修复点 3] 提供静态获取方法供 ATMS/AMS 调用
    public static AppVerificationManagerService getInstance() {
        return sInstance;
    }

    /**
     * 注册广播接收器，监听系统应用安装、卸载、更新事件
     */
    private void registerPackageMonitor() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (intent.getData() == null) return;
                String packageName = intent.getData().getSchemeSpecificPart();
                
                if (TARGET_PACKAGE.equals(packageName)) return;

                String type = "UNKNOWN";
                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    type = "INSTALL";
                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return; 
                    type = "UNINSTALL";
                } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                    type = "UPDATE";
                }

                Slog.d(TAG, "Detected package change: " + packageName + " Type: " + type);
                notifyAppVerifier(packageName, type);
            }
        }, UserHandle.ALL, filter, null, null);
    }

    private void notifyAppVerifier(String packageName, String type) {
        Intent intent = new Intent(ACTION_EVENT_NOTIFY);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra("pkg", packageName);
        intent.putExtra("type", type);
        intent.putExtra("time", System.currentTimeMillis());
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); 
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    // ==========================================
    // 核心拦截逻辑 verifyAppStart
    // ==========================================

    @Override
    public boolean verifyAppStart(String packageName) {
        if (packageName == null) return true;

        // 1. 自身与开关检查
        if (mVerificationMode == 0) return true; 
        if (TARGET_PACKAGE.equals(packageName)) return true;

        // 2. 白名单检查 (含签名校验)
        synchronized (mLock) {
            if (mWhitelist.containsKey(packageName)) {
                String storedHash = mWhitelist.get(packageName);
                if (storedHash != null && !storedHash.isEmpty()) {
                    String currentHash = getSignatureHash(packageName);
                    if (currentHash == null || !storedHash.equalsIgnoreCase(currentHash)) {
                        Slog.e(TAG, "SECURITY ALERT: Signature mismatch for " + packageName);
                        return false; 
                    }
                }
                return true; 
            }
        }

        // 3. 系统应用自动放行
        long token = Binder.clearCallingIdentity(); 
        try {
            ApplicationInfo ai = mContext.getPackageManager()
                    .getApplicationInfo(packageName, 0);

            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

            if (isSystem || isUpdatedSystem) {
                return true; 
            }

        } catch (Exception e) {
            Slog.e(TAG, "Error checking system app info for " + packageName + ", allowing by default.", e);
            return true; 
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (DEBUG) Slog.w(TAG, "BLOCK: " + packageName);
        return false;
    }

    // ==========================================
    // AIDL 接口实现
    // ==========================================

    @Override
    public void addToWhitelist(String packageName) {
        checkPermission();
        String signatureHash = getSignatureHash(packageName);
        if (signatureHash == null) signatureHash = "";

        synchronized (mLock) {
            mWhitelist.put(packageName, signatureHash);
            writePolicy();
        }
        Slog.i(TAG, "Added to whitelist: " + packageName);
    }

    @Override
    public boolean removeFromWhitelist(String packageName) {
        checkPermission();
        synchronized (mLock) {
            if (mWhitelist.containsKey(packageName)) {
                mWhitelist.remove(packageName);
                writePolicy();
                Slog.i(TAG, "Removed from whitelist: " + packageName);
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> getWhitelistedApps() {
        checkPermission();
        synchronized (mLock) {
            return new ArrayList<>(mWhitelist.keySet());
        }
    }

    @Override
    public void setVerificationMode(int mode) {
        checkPermission();
        synchronized (mLock) {
            mVerificationMode = mode;
            writePolicy();
        }
        Slog.i(TAG, "Verification mode set to: " + mode);
    }

    @Override
    public int getVerificationMode() {
        checkPermission();
        synchronized (mLock) {
            return mVerificationMode;
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private void checkPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_APP_VERIFICATION,
                "Requires MANAGE_APP_VERIFICATION permission");
    }

    private String getSignatureHash(String packageName) {
        long token = Binder.clearCallingIdentity();
        try {
            PackageInfo pkgInfo = mContext.getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNATURES);
            
            if (pkgInfo.signatures == null || pkgInfo.signatures.length == 0) {
                return null;
            }
            byte[] signatureBytes = pkgInfo.signatures[0].toByteArray();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signatureBytes);
            return bytesToHex(digest);
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            Slog.e(TAG, "Failed to get signature for " + packageName, e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==========================================
    // 持久化存储 (XML)
    // ==========================================

    private void readPolicy() {
        synchronized (mLock) {
            mWhitelist.clear();
            FileInputStream stream = null;
            try {
                stream = POLICY_FILE.openRead();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    String tag = parser.getName();
                    if (type == XmlPullParser.START_TAG) {
                        if ("config".equals(tag)) {
                            String modeStr = parser.getAttributeValue(null, "mode");
                            if (modeStr != null) {
                                mVerificationMode = Integer.parseInt(modeStr);
                            }
                        } else if ("allow".equals(tag)) {
                            String pkg = parser.getAttributeValue(null, "package");
                            String hash = parser.getAttributeValue(null, "hash");
                            if (pkg != null) {
                                if (hash == null) hash = "";
                                mWhitelist.put(pkg, hash);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to read policy, using defaults.", e);
            } finally {
                if (stream != null) {
                    try { stream.close(); } catch (IOException e) {}
                }
            }
        }
    }

    private void writePolicy() {
        FileOutputStream stream = null;
        try {
            stream = POLICY_FILE.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            
            out.startTag(null, "policy");
            out.startTag(null, "config");
            out.attribute(null, "mode", String.valueOf(mVerificationMode));
            out.endTag(null, "config");

            for (Map.Entry<String, String> entry : mWhitelist.entrySet()) {
                out.startTag(null, "allow");
                out.attribute(null, "package", entry.getKey());
                out.attribute(null, "hash", entry.getValue() == null ? "" : entry.getValue());
                out.endTag(null, "allow");
            }
            
            out.endTag(null, "policy");
            out.endDocument();
            POLICY_FILE.finishWrite(stream);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write policy", e);
            if (stream != null) {
                POLICY_FILE.failWrite(stream);
            }
        }
    }
}
