````markdown
# 技术原理：应用扫描与权限机制 (Technical Principles)

本文档详细说明了 `AppVerifyManager` 中「一键扫描添加所有应用」功能的底层实现原理，以及针对 Android 11 (R) 包可见性限制的适配方案。

---

## 1. 核心机制：与 PackageManager 的交互

应用**并不会**直接扫描文件系统（例如 `/data/app`），而是通过 Android 的核心服务 `PackageManagerService`（PMS）获取已安装应用清单。

### 1.1 关键 API

```java
// PackageManager.java
List<ApplicationInfo> installedApps = pm.getInstalledApplications(
    PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.GET_META_DATA
);
```

- **`pm.getInstalledApplications(...)`**  
  通过 Binder IPC 向系统 PMS 发起查询请求，获取当前设备上已注册的应用信息列表。

- **`MATCH_UNINSTALLED_PACKAGES`**  
  默认情况下，系统不会返回「已禁用 / 冻结 / 卸载保留数据」的应用。  
  添加此 Flag 后，可确保获取到所有仍被系统记录的包信息，避免在扫描过程中出现「漏网之鱼」。

- **`GET_META_DATA`**  
  允许一并读取 `AndroidManifest.xml` 中声明的元数据，以便后续根据 MetaData 做额外筛选或标记（如渠道、特性开关等）。

---

## 2. Android 11+ 包可见性适配 (Package Visibility)

从 **Android 11 (API Level 30)** 开始，Google 引入了**包可见性**隐私限制。  
在默认规则下：

- 应用只能看到：
  - 极少量公开的系统应用；
  - 与自己共享 UID 或签名相同的应用；
- 无法再直接获取完整的第三方应用列表。

这会直接影响「一键扫描」场景，因为扫描结果会被系统过滤，导致无法感知设备上所有实际安装的应用。

### 2.1 解决方案：上帝视角权限 `QUERY_ALL_PACKAGES`

为突破上述限制，`AppVerifyManager` 在 Manifest 中申请了特权权限 **`QUERY_ALL_PACKAGES`**：

```xml
<manifest package="com.android.appverify">
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    ...
</manifest>
```

**原理说明：**

当 PMS 处理应用发起的包查询请求（如 `getInstalledApplications`）时，会：

1. 检查调用方是否声明并获得了 `android.permission.QUERY_ALL_PACKAGES`；
2. 若校验通过，则在可见性裁剪逻辑中对该调用方放宽限制；
3. 返回完整的应用列表，而非仅返回默认可见子集。

因此，在 Android 11+ 上，只要持有此权限，「一键扫描」功能的行为就可以基本保持与 Android 10 及之前版本一致。

> ⚠️ 注意：`QUERY_ALL_PACKAGES` 属于高敏感权限，在实际商用项目中应评估合规性与上架政策。

---

## 3. 业务流程与数据流架构

当用户点击「扫描所有应用」按钮时，系统内部的数据流转示意如下：

```mermaid
graph TD
    UI[用户点击“扫描所有应用”按钮] -->|启动子线程| Client[AppVerifyManager 客户端]

    subgraph System_Layer [Android 系统层]
        Client -->|1. getInstalledApplications 查询| PMS[PackageManagerService]
        PMS -->|2. 检查 QUERY_ALL_PACKAGES 权限| Check[权限检查通过]
        Check -->|3. 返回完整应用 List| Client
    end

    subgraph Logic_Layer [业务逻辑层（应用侧）]
        Client -->|4. 读取当前白名单| Cache[内存缓存 HashSet]
        Client -->|5. 逐个比对| Filter{是否已存在于白名单?}
        Filter -- 是 --> Skip[跳过（已处理过，避免重复）]
        Filter -- 否 --> New[标记为新应用待添加]
    end

    subgraph Service_Layer [Framework 服务层]
        New -->|6. AIDL 调用 addToWhitelist| Service[AppVerificationManagerService]
        Service -->|7. 写入内存 mWhitelist| Memory[SystemServer 内存 HashSet]
        Service -->|8. 原子持久化| Disk[/data/system/app_verification_whitelist.xml]
    end
```

### 3.1 流程分步说明

1. **查询 (Query)**  
   客户端使用 `PackageManager` 向 `PackageManagerService` 发起 `getInstalledApplications` 请求，  
   在 `QUERY_ALL_PACKAGES` 权限的加持下，获得完整的已安装应用列表。

2. **过滤与去重 (Filter & Deduplicate)**  
   - 客户端首先将当前白名单从服务端加载到本地内存中的 `HashSet`；
   - 对每一个扫描到的应用：
     - 若包名已存在于白名单 `HashSet` 中，则跳过（即节点 `Skip`）；
     - 否则视为新应用，加入待添加列表（即节点 `New`）。

   通过这种方式，大量减少了后续跨进程调用次数。

3. **AIDL 调用与服务端写入 (Service Update)**  
   - 对于标记为新应用的条目，通过 AIDL 接口调用 `AppVerificationManagerService.addToWhitelist()`；
   - 服务端在 SystemServer 进程中维护一个内存中的 `HashSet mWhitelist`；
   - 每次变更后，通过 `AtomicFile` 机制将最新白名单原子性写入  
     `/data/system/app_verification_whitelist.xml`，确保异常情况下的数据完整性。

---

## 4. 性能与稳定性考量

为了在复杂场景（应用数量多、设备性能较弱）下仍保障良好体验，`AppVerifyManager` 在实现中进行了多方面性能优化。

### 4.1 异步执行与 UI 反馈

- **异步线程处理**  
  扫描、比对及 AIDL 调用都在独立的 `Thread` 或线程池中执行，  
  避免在主线程长时间阻塞，防止出现 ANR（Application Not Responding）。

- **进度反馈**  
  通过 `ProgressDialog` 或其他进度反馈组件通知用户当前扫描进度，  
  在长时间操作场景中提升可感知性和交互体验。

### 4.2 算法复杂度优化

- 使用 `HashSet<String>` 存储白名单包名：
  - 单次 `contains()` 查询时间复杂度约为 **O(1)**；
  - 整体比对复杂度为 **O(N)**（N 为当前系统中安装应用数量）。

- 若不使用 `HashSet`，而是每次线性遍历白名单列表，则时间复杂度会退化为：
  - **O(N × M)**（N 为全量应用数，M 为白名单长度），
  - 在应用数量较多时，会造成明显卡顿与延时。

### 4.3 跨进程通信 (IPC) 开销控制

- 通过在客户端先做本地去重、批量组装新应用列表，再集中发送 AIDL 请求的方式：
  - 减少 Binder 调用次数；
  - 降低 SystemServer 负载；
  - 改善整体响应时间与系统流畅度。

---

## 5. 小结

- **可见性保障**：通过申请 `QUERY_ALL_PACKAGES` 权限，保证在 Android 11+ 系统中仍能获取完整应用列表。  
- **数据流清晰**：从 UI 点击开始，经 `PackageManagerService` 查询，到白名单过滤与 AIDL 持久化，构成一条清晰的端到端数据流。  
- **性能可靠**：异步执行、`HashSet` 去重与减少 IPC 的设计，使得「一键扫描添加所有应用」能够在实际设备环境中兼顾**正确性**与**性能表现**。

````

