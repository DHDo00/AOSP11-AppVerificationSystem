package android.app;

import android.app.AppVerificationInfo;

/** @hide */
interface IAppVerificationManager {
    // 模式管理
    int getVerificationMode();
    void setVerificationMode(int mode);
    
    // 白名单管理
    boolean addToWhitelist(in AppVerificationInfo info);
    boolean removeFromWhitelist(String packageName);
    boolean isAppInWhitelist(String packageName);
    List<AppVerificationInfo> getWhitelist();
    
    // 便捷方法：直接通过包名添加到白名单
    boolean addPackageToWhitelist(String packageName);
    
    // 日志管理
    List<String> getVerificationLogs();
    void clearVerificationLogs();
}
