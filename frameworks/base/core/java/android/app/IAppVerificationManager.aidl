package android.app;

import android.app.AppVerificationInfo;

/**
 * 应用验证管理器接口
 * @hide
 */
interface IAppVerificationManager {
    /**
     * 添加应用到白名单
     */
    void addToWhitelist(String packageName);
    
    /**
     * 从白名单移除应用
     * 修改为 boolean 以匹配潜在的接口定义冲突，并提供删除结果反馈
     */
    boolean removeFromWhitelist(String packageName);
    
    /**
     * 检查应用是否在白名单中
     */
    boolean isInWhitelist(String packageName);
    
    /**
     * 获取所有白名单应用
     */
    List<String> getWhitelistedApps();
    
    /**
     * 验证应用签名
     */
    boolean verifyAppSignature(String packageName);
    
    /**
     * 获取应用验证信息
     */
    AppVerificationInfo getAppVerificationInfo(String packageName);
    
    /**
     * 设置验证模式
     */
    void setVerificationMode(int mode);
    
    /**
     * 获取验证日志
     */
    List<String> getVerificationLogs(String packageName);
}
