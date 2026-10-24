# HS5 CAF 车型适配

本适配将现有 HS5 Qisi 3212 车辆接口连接到 CAF 车辆协议；不是上游的空配置树 fallback。

## 代码与边界

- `Hs5CafVehicleModel`：覆盖现有 31 个车辆读取字段的不可变本地模型，提供 13 类空调/座椅字段的类型化控制映射；未得到新鲜读数时值为未知。
- `Hs5CafVehiclePlugin`：实现真实 `config/read/register/unregister/write/update`；每条 CAF 数据流单独保存订阅和事务，跨数据流共享现有车辆数据源。
- `AndroidHs5CafVehicleSource`：复用 `HongqiVehicleData` 的缓存、固件匹配、Binder 白名单、车辆操作限流与反馈确认，不新增任意 transaction 调用入口。
- `CafPluginHandler.onSessionOpened/onSessionClosed`：每个数据流按 handler 实例通知一次。关闭数据流会立即取消未完成操作的句柄并释放车辆 lease，随后清理该流的订阅与事务。

插件名 `cn.autunn.hs5`、插件实例 ID `1` 和各 IID 是本应用分配的标识，不冒充原厂插件 ID；它们与 Qisi module/transaction/CAN 编号无关。

## 当前标准 CAF 配置树

以下类型来自本地固定的上游 `iAP2-rewrite` 源码 `CafTypeCatalog`：

| 项目 | 标准 type | 本应用实例 IID | 数据/控制来源 |
| --- | --- | --- | --- |
| Climate accessory | `0x01000001` | 100 | HS5 前排空调 |
| Temperature service | `0x11000002` | 101 / 111 | 两个前排温度实例 |
| TargetTemperature characteristic | `0x31000017` | 102 / 112 | 主驾/副驾目标温度读取；17.5–32.5℃、0.5℃步长控制 |
| Fan service | `0x11000007` | 121 | 前排风量 |
| FanLevel characteristic | `0x31000012` | 122 | 风量读取与 0–7 档控制 |

树包含实际服务和可读写特征，不发送编造的初始温度/风量。当前参考源码未提供左右位置/zone 的完整标准描述；两个实例在 HS5 适配层有稳定主副驾对应，iPhone 如何布局这两个实例仍需协议交互验证。

车门、后备箱、车速、档位、续航、剩余油量、里程、胎压、环境温度、位置灯、座椅及其余空调状态都已经进入 31 字段本地模型。上游公开 catalog 对部分项目只有 accessory 类型，没有完整 service/characteristic 类型、枚举和单位定义。当前没有把 HS5 的原始字段号当成 Apple 标准 type；本地渲染可直接消费这些已接入的字段。

## 请求、订阅与控制行为

1. 注册请求支持指定 IID 或 `*`。注册响应返回实际有效缓存值；未知或无效 IID 返回非零错误。
2. 每 250 毫秒检查已有缓存，只有已订阅值发生变化才发送 `updateNotify`，不在 CAF 网络线程或 UI 线程调用 Binder。
3. 特征声明 `supportsInvalid=true`。断开/超时后向订阅者推送 `null` 使缓存值失效，不用 0、关门、空调关闭等状态代替未知数据。该无效值在目标 iPhone 上的呈现属于尚待验证的协议行为。
4. 写入先检查 IID、数值格式、有限性、范围和步长，再提交现有类型化车辆命令。标准 fan 特征拒绝浮点格式，温度不会因 Float 四舍五入把非法精度变成合法目标。
5. 多 IID 写入逐项提交。只有数据源返回 `CONFIRMED`，对应 IID 才返回成功 `0`；setter 的 accepted 回执、超时和断连都不是成功。
6. CAF 控制请求目前没有可核对的命令类型，返回明确失败；不会伪造一个成功控制响应。
7. 同一数据流中已收到的写事务 ID 保留至流关闭。请求重传可重发已有响应，不会再次下发车控。最多保留 4,096 个写事务，达到上限后拒绝新写操作。
8. 退订仅停止该数据流的对应通知。关闭取消句柄，已实际发出的车控无法撤销，但后续批量操作不会继续，旧流的结果不会发送到新流。

当前失败错误码统一为非零通用 OSStatus `-1`，不把 Qisi 原始返回值冒充 CAF 的细分错误分类。

## 验证

回归测试在 `Hs5CafVehicleModelTest` 与 `Hs5CafVehiclePluginTest`：覆盖真实配置树、31 字段本地模型、边界格式、有效/失效读数、独立订阅/退订、确认后响应、事务不重发、逐项批量操作、超时、关闭取消及插件生命周期。执行结果以本轮统一构建记录为准。

这些测试不操作车辆；本轮尚未通过实车确认车控、iPhone CAF 界面或完整 Ultra 联动。
