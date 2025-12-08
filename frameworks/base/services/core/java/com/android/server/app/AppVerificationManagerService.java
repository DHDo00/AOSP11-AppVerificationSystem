package com.android.server.app;

import android.app.AppVerificationInfo;
import android.app.IAppVerificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.util.Slog;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppVerificationManagerService extends IAppVerificationManager.Stub {
    private static final String TAG = "AppVerificationService";
    
    // 验证模式常量
    public static final int MODE_ENFORCED = 0;  // 强制模式
    public static final int MODE_PERMISSIVE = 1; // 宽容模式
    public static final int MODE_DISABLED = 2;   // 关闭模式
    
    private final Context mContext;
    private volatile int mCurrentMode = MODE_DISABLED; // 默认关闭模式
    
    // 使用线程安全的集合
    private final CopyOnWriteArrayList<AppVerificationInfo> mWhitelist = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> mLogs = new CopyOnWriteArrayList<>();
    
    // 单例实例
    private static AppVerificationManagerService sInstance;
    
    public AppVerificationManagerService(Context context) {
        mContext = context;
        sInstance = this;
	Slog.e(TAG, "DEBUG: Constructor started");

	// 初始化默认白名单
	Slog.e(TAG, "DEBUG: About to call initDefaultWhitelist()");
        initDefaultWhitelist();
	Slog.e(TAG, "DEBUG: initDefaultWhitelist() call completed");
        Slog.i(TAG, "App Verification Service started with mode: " + mCurrentMode);
    }
    
    public static AppVerificationManagerService getInstance() {
        return sInstance;
    }
    
    private void checkPermission() {
	    Slog.e(TAG, "DEBUG: checkPermission called, enforcing permission now");
	    mContext.enforceCallingOrSelfPermission(
			    "android.permission.MANAGE_APP_VERIFICATION",
			    "Caller does not have MANAGE_APP_VERIFICATION permission"
			    );
    }
    
    @Override
    public int getVerificationMode() {
        return mCurrentMode;
    }
    
    @Override
    public void setVerificationMode(int mode) {
        checkPermission();
        final long token = Binder.clearCallingIdentity();
        try {
            if (mode >= 0 && mode <= 2) {
                mCurrentMode = mode;
                String logEntry = "MODE_CHANGED: " + mode + " Time=" + System.currentTimeMillis();
                mLogs.add(logEntry);
                Slog.i(TAG, "Verification mode changed to: " + mode);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
    
    /**
     * 验证应用安装请求
     */
    public boolean verifyPackageInstallation(String packageName) {
        if (mCurrentMode == MODE_DISABLED) {
            return true; // 关闭模式，允许所有安装
        }
        
        boolean isInWhitelist = isAppInWhitelist(packageName);
        
        // 记录日志
        String logEntry = "INSTALL_ATTEMPT: " + packageName + 
                         " Mode=" + mCurrentMode + 
                         " InWhitelist=" + isInWhitelist + 
                         " Time=" + System.currentTimeMillis();
        mLogs.add(logEntry);
        
        if (mCurrentMode == MODE_ENFORCED) {
            if (!isInWhitelist) {
                Slog.w(TAG, "Installation blocked for " + packageName + " (not in whitelist)");
                return false; // 强制模式，阻止安装
            }
        } else if (mCurrentMode == MODE_PERMISSIVE) {
            if (!isInWhitelist) {
                Slog.w(TAG, "Installation allowed but logged for " + packageName + " (not in whitelist)");
            }
            return true; // 宽容模式，允许但记录
        }
        
        return true;
    }
    
    /**
     * 验证应用启动请求
     */
    public boolean verifyAppStart(String packageName) {
        android.util.Log.w(TAG, "DEBUG: verifyAppStart被调用! 包名=" + packageName + ", 模式=" + mCurrentMode);
        if (mCurrentMode == MODE_DISABLED) {
            return true; // 关闭模式，允许所有启动
        }
        
        boolean isInWhitelist = isAppInWhitelist(packageName);
        
        // 记录日志
        String logEntry = "START_ATTEMPT: " + packageName + 
                         " Mode=" + mCurrentMode + 
                         " InWhitelist=" + isInWhitelist + 
                         " Time=" + System.currentTimeMillis();
        mLogs.add(logEntry);
        
        if (mCurrentMode == MODE_ENFORCED) {
            if (!isInWhitelist) {
                Slog.w(TAG, "Start blocked for " + packageName + " (not in whitelist)");
                return false; // 强制模式，阻止启动
            }
        } else if (mCurrentMode == MODE_PERMISSIVE) {
            if (!isInWhitelist) {
                Slog.w(TAG, "Start allowed but logged for " + packageName + " (not in whitelist)");
            }
            return true; // 宽容模式，允许但记录
        }
        
        return true;
    }
    
    @Override
    public boolean addToWhitelist(AppVerificationInfo info) {
        checkPermission();
        if (info != null && info.packageName != null) {
            // 检查是否已存在
            for (AppVerificationInfo existing : mWhitelist) {
                if (info.packageName.equals(existing.packageName)) {
                    return false; // 已存在
                }
            }
            mWhitelist.add(info);
            String logEntry = "WHITELIST_ADD: " + info.packageName + " Time=" + System.currentTimeMillis();
            mLogs.add(logEntry);
            Slog.i(TAG, "Added to whitelist: " + info.packageName);
            return true;
        }
        return false;
    }

    @Override
    public boolean addPackageToWhitelist(String packageName) {
        Slog.e(TAG, "DEBUG: addPackageToWhitelist called with package: " + packageName);
	    checkPermission();
        if (packageName != null && !packageName.isEmpty()) {
            // 创建AppVerificationInfo对象
            AppVerificationInfo info = new AppVerificationInfo();
            info.packageName = packageName;
            info.appName = "Unknown";  // 默认应用名
            info.versionCode = 0;      // 默认版本
            info.installTime = System.currentTimeMillis();
            info.installerPackage = "system";
            
            // 调用现有的addToWhitelist方法
            boolean result = addToWhitelist(info);
            if (result) {
                Slog.i(TAG, "Package added to whitelist via convenience method: " + packageName);
            }
            return result;
        }
        return false;
    }
    
    @Override
    public boolean removeFromWhitelist(String packageName) {
        checkPermission();
        if (packageName != null) {
            for (int i = 0; i < mWhitelist.size(); i++) {
                if (packageName.equals(mWhitelist.get(i).packageName)) {
                    mWhitelist.remove(i);
                    String logEntry = "WHITELIST_REMOVE: " + packageName + " Time=" + System.currentTimeMillis();
                    mLogs.add(logEntry);
                    Slog.i(TAG, "Removed from whitelist: " + packageName);
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override
    public boolean isAppInWhitelist(String packageName) {
        if (packageName == null) return false;
        
        for (AppVerificationInfo info : mWhitelist) {
            if (packageName.equals(info.packageName)) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public List<AppVerificationInfo> getWhitelist() {
        checkPermission();
        return new ArrayList<>(mWhitelist);
    }
    
    @Override
    public List<String> getVerificationLogs() {
        checkPermission();
        return new ArrayList<>(mLogs);
    }
    
    @Override
    public void clearVerificationLogs() {
        checkPermission();
        mLogs.clear();
        Slog.i(TAG, "Verification logs cleared");
    }
    // 在这里添加initDefaultWhitelist方法
    private void initDefaultWhitelist() {
	// 添加强制调试日志
        Slog.e(TAG, "DEBUG: initDefaultWhitelist() method STARTED");
        try {
            // 添加测试应用到白名单
            AppVerificationInfo testApp = new AppVerificationInfo();
            testApp.packageName = "com.wallora.app";
            testApp.appName = "Test App";
            testApp.versionCode = 1;
            testApp.installTime = System.currentTimeMillis();
            testApp.installerPackage = "system";
            mWhitelist.add(testApp);

	    Slog.e(TAG, "DEBUG: Adding testApp to mWhitelist");
            mWhitelist.add(testApp);
            Slog.e(TAG, "DEBUG: testApp added, current mWhitelist size: " + mWhitelist.size());
            
            // 添加关键系统应用避免系统问题
            String[] systemApps = {
                "com.android.settings",
                "com.android.systemui",
                "com.android.launcher3",
                "com.android.shell"
            };
            
	    Slog.e(TAG, "DEBUG: Adding system apps to whitelist");
            for (String pkg : systemApps) {
                AppVerificationInfo info = new AppVerificationInfo();
                info.packageName = pkg;
                info.appName = "System App";
                info.versionCode = 1;
                info.installTime = System.currentTimeMillis();
                info.installerPackage = "system";
                mWhitelist.add(info);
		Slog.e(TAG, "DEBUG: Added " + pkg + ", whitelist size now: " + mWhitelist.size());
            }
            
            // 记录初始化日志
            String logEntry = "WHITELIST_INIT: Added " + mWhitelist.size() + " default apps Time=" + System.currentTimeMillis();
            mLogs.add(logEntry);
            
            Slog.i(TAG, "Initialized default whitelist with " + mWhitelist.size() + " apps including com.wallora.app");
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize default whitelist", e);
        }
    }

}
