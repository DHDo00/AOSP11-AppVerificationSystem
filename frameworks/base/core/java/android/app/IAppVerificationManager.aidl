package android.app;

/**
 * System private API for App Verification Service.
 * {@hide}
 */
interface IAppVerificationManager {
    
    /**
     * 验证应用启动请求 (由 ATMS 调用)
     */
    boolean verifyAppStart(String packageName);

    /**
     * 添加到白名单
     */
    void addToWhitelist(String packageName);

    /**
     * 从白名单移除
     */
    boolean removeFromWhitelist(String packageName);

    /**
     * 获取所有白名单应用列表
     */
    List<String> getWhitelistedApps();

    /**
     * [新增] 设置验证模式
     * 0: Disabled (禁用)
     * 1: Whitelist (启用白名单)
     */
    void setVerificationMode(int mode);

    /**
     * [新增] 获取当前验证模式
     */
    int getVerificationMode();
}
