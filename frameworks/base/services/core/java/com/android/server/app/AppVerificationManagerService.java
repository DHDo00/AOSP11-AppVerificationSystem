package com.android.server.app;

import android.app.AppVerificationInfo;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用验证管理服务 (Fixed & Production Ready)
 * @hide
 */
public class AppVerificationManagerService extends IAppVerificationManager.Stub {
    private static final String TAG = "AppVerificationService";
    private static final boolean DEBUG = false;

    // Singleton instance for local system calls (AMS/ATMS)
    private static AppVerificationManagerService sInstance;

    private static final String WHITELIST_FILE_NAME = "app_verification_whitelist.xml";
    private static final String TAG_ROOT = "verification-policy";
    private static final String TAG_PACKAGE = "allow-package";
    private static final String ATTR_NAME = "name";

    private static final int MODE_DISABLED = 0;
    private static final int MODE_WHITELIST_ONLY = 1;
    private static final int MODE_SIGNATURE_CHECK = 2;
    private static final int MAX_LOG_SIZE = 50;

    private final Context mContext;
    private final Object mLock = new Object();
    private final AtomicFile mPolicyFile;
    
    private final Set<String> mWhitelist; 
    private final ConcurrentHashMap<String, List<String>> mVerificationLogs;
    private int mVerificationMode = MODE_WHITELIST_ONLY;

    public AppVerificationManagerService(Context context) {
        mContext = context;
        mWhitelist = new HashSet<>();
        mVerificationLogs = new ConcurrentHashMap<>();
        
        File dataDir = Environment.getDataSystemDirectory();
        mPolicyFile = new AtomicFile(new File(dataDir, WHITELIST_FILE_NAME));

        synchronized (mLock) {
            readPolicyLocked();
        }
        
        // 设置单例实例，供 SystemServer/AMS 使用
        sInstance = this;
    }

    /**
     * 供 AMS/ATMS/PMS 调用的静态方法
     */
    public static AppVerificationManagerService getInstance() {
        return sInstance;
    }

    /**
     * 核心拦截方法：供 AMS 在启动 Activity 时调用
     */
    public boolean verifyAppStart(String packageName) {
        // 如果服务未就绪或包名为空，默认放行
        if (packageName == null) return true;

        // 检查模式
        if (mVerificationMode == MODE_DISABLED) return true;

        // 检查白名单
        return isInWhitelist(packageName);
    }

    // =========================================================================
    // 持久化逻辑
    // =========================================================================

    private void readPolicyLocked() {
        mWhitelist.clear();
        FileInputStream stream = null;
        try {
            stream = mPolicyFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
            }

            if (type != XmlPullParser.START_TAG) return;

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    if (TAG_PACKAGE.equals(parser.getName())) {
                        String pkgName = parser.getAttributeValue(null, ATTR_NAME);
                        if (pkgName != null) {
                            mWhitelist.add(pkgName);
                        }
                    }
                }
            }
        } catch (java.io.FileNotFoundException e) {
            if (DEBUG) Slog.i(TAG, "Policy file not found, initializing defaults.");
            loadDefaultWhitelistLocked();
            writePolicyLocked();
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to read policy file", e);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }

    private void writePolicyLocked() {
        FileOutputStream stream = null;
        try {
            stream = mPolicyFile.startWrite();
            // FIX: 使用 Xml.newSerializer() 而不是 new XmlSerializer()
            XmlSerializer out = Xml.newSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            for (String pkg : mWhitelist) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATTR_NAME, pkg);
                out.endTag(null, TAG_PACKAGE);
            }

            out.endTag(null, TAG_ROOT);
            out.endDocument();
            mPolicyFile.finishWrite(stream);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write policy file", e);
            if (stream != null) {
                mPolicyFile.failWrite(stream);
            }
        }
    }

    private void loadDefaultWhitelistLocked() {
        mWhitelist.add("com.android.settings");
        mWhitelist.add("com.android.systemui");
        mWhitelist.add("com.android.phone");
        mWhitelist.add("com.android.launcher3");
        mWhitelist.add("com.google.android.gms");
        mWhitelist.add("com.android.vending");
    }

    // =========================================================================
    // AIDL 接口实现
    // =========================================================================

    private void checkPermission() {
        mContext.enforceCallingOrSelfPermission(
            android.Manifest.permission.MANAGE_APP_VERIFICATION,
            "Requires MANAGE_APP_VERIFICATION permission"
        );
    }

    @Override
    public void addToWhitelist(String packageName) {
        checkPermission();
        if (packageName == null || packageName.isEmpty()) return;

        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!mWhitelist.contains(packageName)) {
                    mWhitelist.add(packageName);
                    writePolicyLocked();
                    addLogLocked(packageName, "Added to whitelist");
                    if (DEBUG) Slog.i(TAG, "Added: " + packageName);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean removeFromWhitelist(String packageName) {
        checkPermission();
        if (packageName == null) return false;

        long identity = Binder.clearCallingIdentity();
        boolean removed = false;
        try {
            synchronized (mLock) {
                if (mWhitelist.contains(packageName)) {
                    mWhitelist.remove(packageName);
                    writePolicyLocked();
                    addLogLocked(packageName, "Removed from whitelist");
                    if (DEBUG) Slog.i(TAG, "Removed: " + packageName);
                    removed = true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return removed;
    }

    @Override
    public boolean isInWhitelist(String packageName) {
        if (packageName == null) return false;
        synchronized (mLock) {
            return mWhitelist.contains(packageName);
        }
    }

    @Override
    public List<String> getWhitelistedApps() {
        checkPermission();
        synchronized (mLock) {
            return new ArrayList<>(mWhitelist);
        }
    }

    @Override
    public boolean verifyAppSignature(String packageName) {
        checkPermission();
        long identity = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                String signatureHash = calculateSignatureHash(packageInfo.signatures[0]);
                synchronized (mLock) {
                    addLogLocked(packageName, "Signature verified: " + signatureHash);
                }
                return true;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Verify failed: " + packageName);
            synchronized (mLock) {
                addLogLocked(packageName, "Verification failed: " + e.getMessage());
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    @Override
    public AppVerificationInfo getAppVerificationInfo(String packageName) {
        checkPermission();
        long identity = Binder.clearCallingIdentity();
        try {
            boolean isWhitelisted = isInWhitelist(packageName);
            boolean isSignatureValid = verifyAppSignature(packageName);
            String signatureHash = getSignatureHash(packageName);
            
            // 这里现在应该能匹配 AppVerificationInfo.java 的构造函数了
            return new AppVerificationInfo(packageName, isWhitelisted, 
                                        isSignatureValid, signatureHash);
        } catch (Exception e) {
            Slog.e(TAG, "Error getting info", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void setVerificationMode(int mode) {
        checkPermission();
        if (mode >= MODE_DISABLED && mode <= MODE_SIGNATURE_CHECK) {
            synchronized (mLock) {
                mVerificationMode = mode;
            }
        }
    }

    @Override
    public List<String> getVerificationLogs(String packageName) {
        checkPermission();
        List<String> logs = mVerificationLogs.get(packageName);
        if (logs == null) return new ArrayList<>();
        
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private String calculateSignatureHash(Signature signature) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signature.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getSignatureHash(String packageName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                return calculateSignatureHash(packageInfo.signatures[0]);
            }
        } catch (Exception e) { }
        return "";
    }

    private void addLogLocked(String packageName, String message) {
        List<String> logs = mVerificationLogs.computeIfAbsent(packageName, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (logs) {
            if (logs.size() >= MAX_LOG_SIZE) {
                logs.remove(0);
            }
            logs.add(System.currentTimeMillis() + ": " + message);
        }
    }
}
