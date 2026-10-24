# Autunn Play 客户端架构与维护边界

适用：152 / 0.3.3-AUTUNN21。目标车机为 Android 9 红旗 HS5，分别验收 3212、3222。范围包括连接、三屏、音频、启停、设置、产品授权与更新；原版参考工程、服务端及管理站点不属于本客户端变更。

## 入口与责任

`mobile` 是实际交付应用。`common` 负责应用使用周期、设置、界面和安装交互；`shared` 负责协议、平台适配、媒体和信任校验。保留现有三个 Gradle 边界，不为每个类额外建立模块。`automotive` 与两个 samples 保留各自入口。

| 责任 | 当前所有者 | 规则 |
| --- | --- | --- |
| 使用意图、启停与恢复 | CarPlayBackgroundSession、SessionRecoveryRecord、HongqiIgnitionMonitor | 服务持有会话，Activity 暂停不等于断开；手动停止、停车和过期回调不能恢复旧连接 |
| 连接参数 | CarPlayRuntimeSettings、CarPlayConfigurationStore | 前台与冷恢复共用设置转协议参数入口；持久显示快照不复制认证私钥或无线凭据 |
| 资源构建 | CarPlaySessionFactory | Controller、媒体和定位资源统一构建；失败按已取得的所有权释放 |
| 蓝牙目标与准入 | PairedIphoneSelector、BluetoothBootstrapEligibility、SelectedPhoneMonitor | 系统链路观察与发起 iAP2 引导分开；已选且配对、缓存证明支持 iAP2 的手机可获得每使用周期一次主动引导机会 |
| 无线组与发现 | WifiP2pGroupManager、P2pChannelRestriction、CarPlayBonjour | API28 服务未就绪为可恢复状态；未确认操作保留清理责任；丢组立即撤销端点有效性；只管理自有资源 |
| 协议会话 | xcertplay 播放栈：CarPlayController、airplay/、iap2/session、transport/ | 有线与无线 iAP2 由 xcertplay 会话承接，session 12 在会话侧完成；事件推进，阶段超时发现停滞；健康 GO 可复用；关闭完成以实际资源释放为准 |
| MFi | DiPlay：`DiPlayBootstrap`、`LocalMfiAuthenticationClient`、`DIPLAY_AUTH_ASSETS_DIR/offline-mfi` | 只使用既有 `identity.pk8` 与 `certificate.p7b`；不改写算法，也不换成 xcertplay `local-auth` |
| 成功策略 | P2pConfigurationMemory、WirelessAddressRecovery | 信道策略仅在认证并出现首帧后确认；实际频率未知不伪造频率；地址族成功提示与本轮切换次数分开 |
| 主屏界面 | CarPlayHostActivity、ConnectionStatusText、ProjectionDecoderCapabilities、AirPlayIconRepository | 窗口、触控与 Surface 归界面；解码能力、图标和状态文案独立；首帧驱动显示 |
| 仪表与 HUD | HongqiNavigation、HongqiNavigationTransport、ClusterMapPresentation | Binder 编码与输出协调分离；HUD 错误不清空健康仪表；原厂导航模式与短暂视频中断分开 |
| 音视频 | AndroidMediaSink、VideoDecoder、AudioRenderer、AudioFocusCoordinator | sink 管流和资源归属，解码/播放各有串行工作者；焦点由单一协调器管理；会话取消仍可立即终止旧音轨 |
| 方向盘、日夜与车辆状态 | HongqiWheelControls、HongqiNightModeSource、HongqiIgnitionMonitor、Hs5Platform | 集中平台身份判断；权限、返回值与固件差异由具体能力核实，不从车型名称推断已验收 |
| 设置 | AirPlayPersistence、AutunnSettingsPage、DiPlaySettingsActivity | 沿用已有键及 Apply/Cancel 规则；运行配置从统一入口生成；初始化不能覆盖用户的调试选择 |
| 产品授权 | ProductLicense、ProductAuthorizationBackend、AutunnAuthorizationPanel | UI 展示及取消与授权请求分离；票据验签、设备/包身份、到期和撤销检查继续有效 |
| 客户端更新 | UpdateRepository、AppUpdates、AutunnVendorInstaller | repository 管清单、下载、缓存、签名及安装授权；AppUpdates 管安装事务；不因已有缓存跳过在线授权 |
| 诊断 | ClientDiagnostics、AsyncDiagnosticLog、SessionLogFile | shared 只发送事件元数据；应用负责限量排队、脱敏、轮转和导出；关键模式切换进入持久记录 |

## 连接与恢复约束

系统蓝牙连接、RFCOMM、iAP2 认证、P2P、手机入网、TCP、AirPlay 会话和首帧分别表示不同进展。网络端点仍在协议需要前准备好；本次未未经协议验证改变网络与认证的执行顺序。

自动入口在原车蓝牙尚未连上时，允许对已选、已配对且确认支持 iAP2 的手机作一次引导。无线电不可用、未知设备、未配对或明确不支持的设备不走此补救。引导开始的标记随使用意图持久保存；缺席手机不会因此反复开启新的连接。手机真实到达后，正常连接预算开始。手动停止和停车屏障优先。

从最近任务关闭应用会结束本轮使用。下次用户重新从启动器进入无显式页面的前台首页时，若自动连接开启且已有手机选择，就开启新的自动使用周期；普通服务重启、蓝牙事件和显式设置页不能解除这个终止状态。在软件内单独选择“断开连接”继续阻止同轮自动重连。旧连接的清理仍必须完成，旧服务回调依据当前使用状态决定是否停止。

TCP 服务包装层显式转发主屏流接受、首个解密画面及输出进展事件；后台接收不依赖前台窗口或 Surface。正式打包清理 shared/common/mobile 编译输出并禁用构建缓存复用，回归测试经过真实服务与 TCP SETUP 路径。

超时、退避和必要的漏事件复查仍保留，但真实成功和失败事件立即推进。P2P 接口无响应不能直接变成永久配置错误；已失效网络也不能继续发布为可用。地址族只保存 IPv4/IPv6 提示，下一次仍从当前接口获取新地址和 scope。

AirPlayResourceModes 分别处理主屏与主音频。changeModes 只表达请求，modesChanged 才确认实际归属；重复 owner 通知不重复打开页面。进入投屏页面只选择屏幕。原厂临时占用使用 borrow/return，永久切换使用 take/release；主音频归原车时停止麦克风并静音主音轨，备用导航通道独立。方向盘 Siri 的按下与松开绑定同一会话。

无线链路失效撤销发现与新会话准入，但不单凭广播关闭已认证 TCP；真实 EOF、读写失败及既有内核探测驱动清理。播放器退出后由活动流后续数据触发恢复；无有效输出的连续恢复最多两次，新 SETUP 重置预算，已结束的流不能被迟到 RTP 复活。

CarPlayLastUserMode 按已认证手机的哈希键保存屏幕、音源与播放意图，熄火清理 PAUSE 不覆盖记录。自动进入仍遵循当前产品偏好。仅恢复已知的原车音源选择；现有接口证据不足以证明应用离线期间音源未变，故不根据历史 playing 单独强发 PLAY。完整跨行程自动播放恢复仍需可验证的原厂当前音源与连续变更证据。

仪表分开处理路线有效、用户显示选择、原厂模块可用、地图模式归属及视频就绪。首次进入需要视频就绪；短暂中断保留原厂模式以便恢复像素。持续恢复失败由 10 秒看门狗释放，显示设备移除、路线结束和用户退出按事件释放。该看门狗不延迟正常进入。地图加载序列按已确认的命令推进，后续命令被拒绝时不重复已完成的加载步骤。

## 验证入口

配置 JDK 和 Android SDK 后，在本目录运行：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug
```

两模块的测试类使用独立 JVM，防止生产单例跨 Robolectric 沙箱污染下一测试类。认证测试仅在测试期间生成合成材料。正式签名构建继续使用 `scripts/build-licensed-apk.py` 的外部输入，认证资产与签名材料不进入普通源码构建。

修改无线或输出恢复时，优先覆盖 Android9ConnectionRecoveryTest、HongqiOutputRecoveryTest、CarPlayPowerRecoveryTest；媒体拆分使用原有音轨、焦点、视频、关闭及取消测试；授权和更新继续运行签名、缓存、请求取消、安装提交边界测试。

## 固件验收

| 场景 | 必须记录的结果 |
| --- | --- |
| 冷启动、短熄火恢复、实际休眠唤醒 | 应用启动、系统蓝牙、RFCOMM、认证、网络、会话、前台及首帧的同轮时间线 |
| 手机缺席后返回 | 一次探测后的等待、到达事件恢复、无反复建组；主动断开不会被自动连接撤销 |
| 三屏持续导航 | 路线不变时模式指令不反复重放；HUD 间歇故障不重置仪表；速度及原车告警持续可见 |
| 音乐、导航、电话与 Siri | 输入间断、重缓冲、焦点及原厂路由分别记录；听感与模拟测试分开 |
| 原车页面往返、倒车及按键 | 会话可保留；进入依实际首帧；原车优先级与按键释放正确 |
| 授权和更新 | 已有设置及激活可读、权限与到期正确；升级身份一致、签名和在线授权检查有效 |

3212 和 3222 分别记录结果。接口源码、ACK、桌面测试及物理效果不能互相替代。本次重构不能单凭重复模式指令的修复就宣称此前整个仪表黑屏已解决，也不能在没有当前音频故障证据时宣称全部断音已经消失。
