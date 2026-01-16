package android.app;

/**
 * System private API for App Verification Service.
 * {@hide}
 */
interface IAppVerificationManager {
    // 核心拦截接口
    boolean verifyAppStart(String packageName);

    // 添加应用到白名单
    void addAppToWhitelist(String packageName);

    // [新增] 从白名单移除应用 (修复错误: 找不到符号 removeAppFromWhitelist)
    void removeAppFromWhitelist(String packageName);

    // [新增] 获取白名单列表 (修复错误: 找不到符号 getWhitelist)
    // 注意: AIDL 支持 Map，但在 Java 端会映射为 java.util.Map
    Map getWhitelist();

    // [修改] 设置验证模式 (修复错误: boolean/int 类型不兼容)
    // 将参数改为 boolean 以匹配 Switch 控件
    void setVerificationMode(boolean enabled);

    // [修改] 获取验证模式
    boolean getVerificationMode();
}
