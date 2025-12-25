# Android 应用完整性验证系统 (App Integrity Verification System)

基于 Android 11 (AOSP) 开发的系统级应用安全管控框架 (v2.0)。
通过系统服务拦截未授权应用的启动，并结合签名 Hash 校验机制，防止应用被恶意篡改或替换。

## 🚀 版本特性 (v2.0) - 重构版

### 核心功能
* **双重验证机制**：基于「包名 + 签名 Hash (SHA-256)」的白名单校验，有效防御“真包名、假应用”的攻击。
* **系统级拦截**：深入 `ActivityTaskManagerService`，在应用启动的最早阶段进行拦截。
* **智能防变砖**：自动识别并放行系统关键应用 (`FLAG_SYSTEM`)，无需手动配置，杜绝开机黑屏风险。
* **特权权限管控**：通过 `priv-app` 权限白名单机制，安全地获取系统管理权限。

### 交互模块 (全新 UI)
1.  **添加应用 (App Selection)**
    * 自动扫描本机已安装的第三方应用。
    * 智能过滤系统自带应用，界面清爽。
    * 支持批量勾选并一键导入白名单。
2.  **名单管理 (Whitelist Manager)**
    * 查看当前受保护的应用列表。
    * **完整性校验**：点击应用可查看并复制其 SHA-256 签名指纹。
    * 支持关键词搜索与长按移除保护。
3.  **事件审查 (Event Review)**
    * 后台自动监听应用安装、更新、卸载广播。
    * 形成待办事件流，防止新安装的应用被遗漏管理。

---

## 🛠️ 编译与部署

本项目包含修改 Framework 层的权限配置，**必须重新打包系统镜像**。

### 1. 代码位置 (对应 AOSP 源码路径)
* **Client App**: `packages/apps/AppVerifyManager/`
* **System Service**: `frameworks/base/services/core/java/com/android/server/app/AppVerificationManagerService.java`
* **AIDL Interface**: `frameworks/base/core/java/android/app/IAppVerificationManager.aidl`
* **Permission Config**: `frameworks/base/data/etc/privapp-permissions-platform.xml`

### 2. 编译命令
```bash
# 1. 编译框架与服务 (更新 AIDL 和 Service 逻辑)
m framework
m services

# 2. 编译客户端 App
m AppVerifyManager

# 3. 打包系统镜像 (关键步骤，为了生效权限白名单)
m systemimage
```

### 3. 模拟器启动
由于修改了 SystemServer 和 Boot Classpath，建议进行冷启动以清除缓存：
```bash
emulator -wipe-data
```

---

## ⚠️ 关键配置说明

### 特权权限白名单 (Priv-app Whitelist)
为了防止 SystemServer 启动崩溃，必须在 `privapp-permissions-platform.xml` 中声明：

```xml
<privapp-permissions package="com.android.appverify">
    <permission name="android.permission.MANAGE_APP_VERIFICATION"/>
    <permission name="android.permission.QUERY_ALL_PACKAGES"/>
</privapp-permissions>
```
