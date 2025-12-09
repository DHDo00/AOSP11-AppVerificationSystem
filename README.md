# Android 11 应用验证防御系统 (App Verification System)

基于 AOSP 11 开发的系统级应用管控框架。该项目在 Android Framework 层实现了一套完整的应用启动拦截机制，并配套了图形化管理工具，支持白名单模式、断电持久化存储以及批量管理功能。

## 🚀 核心功能 (Features)

### 1. Framework 核心服务
* **启动拦截**: 深入 `ActivityTaskManagerService` (ATMS) 核心流程，在应用启动前进行权限校验。未授权应用将直接抛出 `SecurityException` 阻止启动。
* **双模式切换**:
    * **禁用模式 (Disabled)**: 系统不做任何拦截，便于调试。
    * **白名单模式 (Whitelist)**: 仅允许白名单内的应用启动，实现“默认拒绝”的安全策略。
* **持久化存储**: 使用 `AtomicFile` + XML 机制 (`/data/system/app_verification_whitelist.xml`)，确保白名单数据在重启、断电后不丢失。
* **权限控制**: 服务受 `android.permission.MANAGE_APP_VERIFICATION` 保护，仅允许系统签名应用调用。

### 2. 图形化管理工具 (AppVerifyManager)
* **系统级应用**: 拥有 `platform` 签名，直接驻留于 `/system/priv-app/`。
* **可视化管理**: 全中文界面，直观展示白名单列表，支持模式切换。
* **批量导入**: 一键扫描设备上所有已安装应用并导入白名单（后台线程处理）。
* **自动清理**: 自动检测并移除白名单中已卸载或无效的条目。

---

## 📂 项目结构 (Project Structure)

本项目采用 Overlay 结构，目录路径与 AOSP 源码树保持一致，便于集成。

```text
AOSP11-AppVerificationSystem/
├── frameworks/
│   └── base/
│       ├── core/java/android/app/
│       │   ├── IAppVerificationManager.aidl    # [接口] AIDL 通信接口定义
│       │   ├── AppVerificationInfo.java        # [数据] 跨进程传输实体类
│       │   └── IAppVerificationManager.aidl    # [接口] 定义 8 个核心方法
│       ├── core/res/AndroidManifest.xml        # [权限] 定义 MANAGE_APP_VERIFICATION 权限
│       └── services/
│           ├── core/java/com/android/server/app/
│           │   └── AppVerificationManagerService.java # [核心] 服务实现逻辑、拦截、持久化
│           └── java/com/android/server/
│               └── SystemServer.java           # [入口] (需修改) 在此处注册服务
├── packages/
│   └── apps/
│       └── AppVerifyManager/                   # [应用] 系统管理 App 源码
│           ├── Android.bp                      # 编译脚本 (platform 签名)
│           ├── AndroidManifest.xml             # 权限声明
│           ├── src/                            # Java 源码 (MainActivity 包含批量逻辑)
│           └── res/                            # UI 资源 (strings.xml 中文适配)
└── system/
    └── sepolicy/
        └── private/
            ├── app_verification_service.te     # [安全] SELinux 服务类型定义
            ├── service_contexts                # [安全] 服务上下文注册
            └── system_server.te                # [安全] 允许 system_server 添加服务
```
## 📝 关键文件说明

### Framework 层 (核心逻辑)
* **`AppVerificationManagerService.java`**:
    * **核心逻辑**: 实现了 `verifyAppStart(String packageName)` 供 AMS 拦截调用。
    * **数据初始化**: 包含 `loadDefaultWhitelistLocked()`，在首次启动时初始化默认应用（如 Settings, SystemUI）。
    * **性能优化**: 使用 `HashSet` 和细粒度对象锁 (`mLock`) 保证高并发下的查询性能 (O(1) 复杂度)。
    * **持久化**: 使用 `AtomicFile` 实现 XML 文件的原子性写入与读取。
* **`IAppVerificationManager.aidl`**:
    * 定义了跨进程接口 (IPC)：包含 `addToWhitelist`, `removeFromWhitelist`, `getWhitelistedApps`, `setVerificationMode` 等 8 个核心方法。
* **`SystemServer.java`** (集成点):
    * 系统启动入口，需在此文件的 `startOtherServices()` 阶段注册 `app_verification` 服务。

### 应用层 (AppVerifyManager)
* **`MainActivity.java`**:
    * **服务连接**: 通过 `ServiceManager` 获取 Framework 层的 Binder 代理。
    * **异步处理**: 使用后台线程 (`Thread`) 执行耗时的 `PackageManager` 全量扫描操作，配合 `ProgressDialog` 避免主线程阻塞。
    * **交互逻辑**: 实现了全中文界面的事件响应、列表刷新与弹窗确认。
* **`Android.bp`**:
    * **权限配置**: 关键配置 `platform_apis: true` (允许调用隐藏 API) 和 `certificate: "platform"` (获取系统特权)。

---

## 🛠️ 集成指南 (Integration)

### 1. 代码合并
将本仓库文件复制到 AOSP 源码对应的目录中（建议使用 Overlay 方式或集成脚本）。

### 2. 修改 SystemServer
在 `frameworks/base/services/java/com/android/server/SystemServer.java` 的 `startOtherServices` 方法中（建议在 PackageManagerService 启动后）添加启动逻辑：

```java
try {
    Slog.i(TAG, "Starting App Verification Service");
    ServiceManager.addService("app_verification", new AppVerificationManagerService(context));
} catch (Throwable e) {
    reportWtf("starting App Verification Service", e);
}
```
### 3. 修改 ActivityTaskManagerService (拦截点)
在'frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java'的'startActivity'流程中添加拦截：
```java
// 伪代码示例
if (!AppVerificationManagerService.getInstance().verifyAppStart(r.packageName)) {
    throw new SecurityException("START_BLOCKED_BY_POLICY: " + r.packageName);
}
```
### 4. 编译与部署
```shell
# 初始化环境
source build/envsetup.sh
lunch aosp_x86_64-eng

# 编译服务与应用
m services
m AppVerifyManager

# 推送更新 (或执行 make snod 重新打包 system.img)
adb root && adb remount
adb push out/target/product/generic_x86_64/system/framework/services.jar /system/framework/
adb push out/target/product/generic_x86_64/system/app/AppVerifyManager/AppVerifyManager.apk /system/priv-app/AppVerifyManager/
adb reboot
```
## 📊 版本记录 (Changelog)

v1.0 (Release)
✅ 功能完成: 核心拦截服务稳定运行、XML 断电持久化存储验证通过。

✅ UI 更新: 管理 App 适配全中文界面，精简为“禁用/启用”双模式。

✅ 高级特性: 新增“一键扫描导入所有应用”和“自动清理无效条目”功能。

✅ 安全强化: 完善 SELinux 策略，确保服务符合 Android 安全模型
