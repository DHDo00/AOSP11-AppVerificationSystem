package com.android.server.app;

import android.app.AppVerificationInfo;
import android.app.IAppVerificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.UserHandle;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用验证管理服务 (High Security Edition: Package + Signature)
 * @hide
 */
public class AppVerificationManagerService extends IAppVerificationManager.Stub {
    private static final String TAG = "AppVerificationService";
    private static final boolean DEBUG = false;

    private static AppVerificationManagerService sInstance;

    // XML 持久化配置
    private static final String WHITELIST_FILE_NAME = "app_verification_whitelist.xml";
    private static final String TAG_ROOT = "verification-policy";
    private static final String TAG_PACKAGE = "allow-package";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_SIGNATURE = "signature"; // 新增字段

    // 验证模式常量
    private static final int MODE_DISABLED = 0;
    private static final int MODE_WHITELIST_ONLY = 1; 
    // 现在的逻辑其实融合了白名单和签名检查，模式1即开启全功能。
    // 如果需要更细粒度控制，可以复用 MODE_SIGNATURE_CHECK，但这里我们让默认模式直接支持签名校验。
    private static final int MODE_SIGNATURE_CHECK = 2;

    private static final int MAX_LOG_SIZE = 50;

    private final Context mContext;
    private final Object mLock = new Object();
    private final AtomicFile mPolicyFile;
    
    // [核心修改] 使用 Map 存储：包名 -> 签名Hash
    // 如果 Value 为空字符串，表示仅校验包名（兼容旧数据）
    private final Map<String, String> mWhitelist; 
    
    private final ConcurrentHashMap<String, List<String>> mVerificationLogs;
    private int mVerificationMode = MODE_WHITELIST_ONLY;

    public AppVerificationManagerService(Context context) {
        mContext = context;
        mWhitelist = new HashMap<>(); // 修改为 HashMap
        mVerificationLogs = new ConcurrentHashMap<>();
        
        File dataDir = Environment.getDataSystemDirectory();
        mPolicyFile = new AtomicFile(new File(dataDir, WHITELIST_FILE_NAME));

        synchronized (mLock) {
            readPolicyLocked();
        }
        
        sInstance = this;
        registerPackageReceiver();
    }

    public static AppVerificationManagerService getInstance() {
        return sInstance;
    }

    /**
     * 核心拦截方法：双重校验 (包名 + 签名)
     */
    public boolean verifyAppStart(String packageName) {
        if (packageName == null) return true;
        
        // 0. 模式检查
        if (mVerificationMode == MODE_DISABLED) return true;

        // 1. 特殊放行：防止死锁，必须放行管理 App
        if ("com.android.appverify".equals(packageName)) {
            return true;
        }

        synchronized (mLock) {
            // 2. 第一重校验：检查包名是否存在
            if (!mWhitelist.containsKey(packageName)) {
                if (DEBUG) Slog.w(TAG, "Block: Package not in whitelist -> " + packageName);
                return false;
            }

            // 3. 第二重校验：检查签名 Hash
            String storedHash = mWhitelist.get(packageName);
            
            // 如果白名单里存了 Hash (不为空)，则必须匹配
            if (storedHash != null && !storedHash.isEmpty()) {
                String currentHash = getSignatureHash(packageName);
                
                if (currentHash == null || currentHash.isEmpty()) {
                    Slog.e(TAG, "Block: Cannot get signature for " + packageName);
                    return false; // 获取不到签名，视为异常，拦截
                }

                if (!storedHash.equalsIgnoreCase(currentHash)) {
                    // 🚨 严重安全警报：签名不匹配！可能被篡改！
                    Slog.e(TAG, "SECURITY ALERT: Signature mismatch for " + packageName);
                    Slog.e(TAG, "  - Expected: " + storedHash);
                    Slog.e(TAG, "  - Actual:   " + currentHash);
                    return false; // 拦截
                }
            }
        }
        
        return true; // 通过双重校验
    }

    // =========================================================================
    // 持久化逻辑 (升级版)
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
                        // [NEW] 读取 signature 属性
                        String signature = parser.getAttributeValue(null, ATTR_SIGNATURE);
                        
                        if (pkgName != null) {
                            // 如果 XML 里没有 signature (旧版本)，存入空字符串
                            mWhitelist.put(pkgName, signature != null ? signature : "");
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
                try { stream.close(); } catch (IOException e) { }
            }
        }
    }

    private void writePolicyLocked() {
        FileOutputStream stream = null;
        try {
            stream = mPolicyFile.startWrite();
            XmlSerializer out = Xml.newSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            for (Map.Entry<String, String> entry : mWhitelist.entrySet()) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATTR_NAME, entry.getKey());
                // [NEW] 写入 signature 属性
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    out.attribute(null, ATTR_SIGNATURE, entry.getValue());
                }
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
        // 默认应用加载 (暂不校验默认应用的 Hash，除非你需要极高安全性)
        // 存入空字符串 "" 代表只校验包名
        addDefaultApp("com.android.settings");
        addDefaultApp("com.android.systemui");
        addDefaultApp("com.android.phone");
        addDefaultApp("com.android.launcher3");
        addDefaultApp("com.google.android.gms");
        addDefaultApp("com.android.vending");
        addDefaultApp("com.android.appverify");
    }
    
    private void addDefaultApp(String pkg) {
        mWhitelist.put(pkg, "");
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
                // [NEW] 1. 获取当前应用的签名 Hash
                String signatureHash = getSignatureHash(packageName);
                
                if (signatureHash == null || signatureHash.isEmpty()) {
                    // 如果应用还没安装，我们无法获取 Hash。
                    // 策略选择：
                    // A. 拒绝添加 (严格模式)
                    // B. 允许添加但 Hash 为空 (宽容模式 -> 此时只校验包名)
                    // 这里采用 B，方便预配置，但建议日志警告
                    Slog.w(TAG, "Adding " + packageName + " without signature (not installed yet?)");
                    signatureHash = "";
                } else {
                    if (DEBUG) Slog.i(TAG, "Locking signature for " + packageName + ": " + signatureHash);
                }

                // 2. 存入 Map (会覆盖旧值，相当于更新)
                mWhitelist.put(packageName, signatureHash);
                
                writePolicyLocked();
                addLogLocked(packageName, "Added to whitelist with sig: " + shortenHash(signatureHash));
                Slog.i(TAG, "Added to whitelist: " + packageName);
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
                if (mWhitelist.containsKey(packageName)) {
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
            return mWhitelist.containsKey(packageName);
        }
    }

    @Override
    public List<String> getWhitelistedApps() {
        checkPermission();
        synchronized (mLock) {
            // 返回 Map 的 KeySet (包名列表)
            return new ArrayList<>(mWhitelist.keySet());
        }
    }

    // =========================================================================
    // 事件监听与通知模块 (Event Listener & Notification)
    // =========================================================================

    // [新增] 通知 App 的广播 Action常量
    private static final String ACTION_NOTIFY_EVENT = "com.android.appverify.ACTION_EVENT";

    /**
     * 注册广播接收器，监听应用的安装、卸载和更新
     */
    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);    // 监听安装
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);  // 监听卸载
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED); // 监听更新覆盖
        filter.addDataScheme("package");
        
        // 使用 UserHandle.ALL 确保监听所有用户的应用变动
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Uri data = intent.getData();
                if (data == null) return;
                
                // 获取变动的包名 (例如 com.tencent.mm)
                String packageName = data.getSchemeSpecificPart();
                
                // 排除自己 (AppVerifier)，防止死循环或逻辑冲突
                if ("com.android.appverify".equals(packageName)) return;

                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    // 场景：新安装了一个应用 -> 通知 App 提示用户是否加入白名单
                    notifyAppVerifier(packageName, "INSTALL");
                    
                    // (可选) 如果你还保留了之前的"自动学习"逻辑，可以在这里调用 handleNewPackageInstalled(packageName);
                    
                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                    // 场景：卸载了应用 -> 检查是不是"更新造成的卸载"
                    // EXTRA_REPLACING 为 true 表示这只是更新过程中的卸载，不需要提示移除白名单
                    boolean isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    if (!isReplacing) {
                        notifyAppVerifier(packageName, "UNINSTALL");
                    }
                    
                } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                    // 场景：应用更新完成 -> 签名可能变了，通知 App 提示用户更新校验
                    notifyAppVerifier(packageName, "UPDATE");
                }
            }
        }, UserHandle.ALL, filter, null, null);
    }

    /**
     * 发送显式广播给管理 App (AppVerifyManager)
     * 将系统底层的事件“转发”给上层应用
     */
    private void notifyAppVerifier(String packageName, String eventType) {
        try {
            Intent intent = new Intent(ACTION_NOTIFY_EVENT);
            intent.setPackage("com.android.appverify"); // 明确指定接收者，防止被其他应用窃听
            intent.putExtra("pkg", packageName);
            intent.putExtra("type", eventType); // 类型: INSTALL, UNINSTALL, UPDATE
            intent.putExtra("time", System.currentTimeMillis());
            
            // 发送给所有用户，确保 App 无论在哪个用户空间运行都能收到
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            
            if (DEBUG) Slog.i(TAG, "Notified AppVerifier: " + eventType + " -> " + packageName);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to notify AppVerifier", e);
        }
    }
    // ... 其他接口 (verifyAppSignature, getAppVerificationInfo 等) 保持原样 ...
    @Override
    public boolean verifyAppSignature(String packageName) {
        checkPermission();
        // 现在的逻辑其实就是 getSignatureHash 是否为空
        return getSignatureHash(packageName).length() > 0;
    }

    @Override
    public AppVerificationInfo getAppVerificationInfo(String packageName) {
        checkPermission();
        // ... (保持不变)
        return null; // 示例简化
    }

    @Override
    public void setVerificationMode(int mode) {
        checkPermission();
        synchronized (mLock) {
            mVerificationMode = mode;
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
    // 核心辅助方法：获取签名 Hash
    // =========================================================================

    private String getSignatureHash(String packageName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            // 注意：GET_SIGNATURES 在 Android 11+ 依然可用，但建议适配 GET_SIGNING_CERTIFICATES
            // 这里为了兼容性使用 GET_SIGNATURES
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                // 取第一个签名 (通常够用)
                return calculateSignatureHash(packageInfo.signatures[0]);
            }
        } catch (Exception e) {
            // App 未安装时会抛出 NameNotFoundException
        }
        return "";
    }

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
            Slog.e(TAG, "Error calculating hash", e);
            return "";
        }
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
    
    private String shortenHash(String hash) {
        if (hash == null || hash.length() < 8) return hash;
        return hash.substring(0, 8) + "...";
    }
}
