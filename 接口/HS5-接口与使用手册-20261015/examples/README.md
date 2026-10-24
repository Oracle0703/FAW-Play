# 示例和现有实现快照

本目录用于理解调用及复用代码，不是第四套可独立安装的App。除只读Java示例外，项目文件仍依赖原工程的Android配置、资源、授权/导航/会话类型，应在对应源码工程内使用，不可简单把目录当独立Gradle模块编译。

| 文件/目录 | 来源及使用方法 |
| --- | --- |
| [Hs5ReadOnlyProbe.java](Hs5ReadOnlyProbe.java) | 本次整理的基本类型只读示例。绑定QISIAdapterService后，在工作线程构造并调用；上层负责ServiceConnection、重连和解绑 |
| [xcertplay-vehicle/Hs5VehicleData.kt](xcertplay-vehicle/Hs5VehicleData.kt) | 当前xcertplay共享连接入口；acquire/observe/execute的完整用法见主手册 |
| [xcertplay-vehicle/Hs5VehicleBinderRpc.kt](xcertplay-vehicle/Hs5VehicleBinderRpc.kt) | 根模块查询、descriptor检查、Parcel收发/回收与错误处理 |
| [xcertplay-vehicle/Hs5ComfortProtocol.kt](xcertplay-vehicle/Hs5ComfortProtocol.kt) | ATC/SCCU/EOL读写和回调。部分注释早于后续UI语义核查，具体含义以本包ui-semantics证据为准 |
| [xcertplay-vehicle/Hs5DrivingProtocol.kt](xcertplay-vehicle/Hs5DrivingProtocol.kt) | 模块15只读getter、回调及当前无效值过滤边界 |
| [xcertplay-vehicle/Hs5VehicleClient.kt](xcertplay-vehicle/Hs5VehicleClient.kt) | 线程、订阅、轮询、连接代次、控制校验 |
| [xcertplay-vehicle/Hs5VehicleState.kt](xcertplay-vehicle/Hs5VehicleState.kt) | 32字段、快照、五秒时效和command/result类型 |
| [diplay-navigation/HongqiNavigation.java](diplay-navigation/HongqiNavigation.java) | DiPlay当前HUD/仪表消息、地图状态与所有权清理 |
| [diplay-navigation/HongqiInstrumentGuidance.java](diplay-navigation/HongqiInstrumentGuidance.java) | 原生七字段Parcel、距离/剩余时间和环岛转换 |
| [diplay-navigation/HongqiNavigationExitListener.java](diplay-navigation/HongqiNavigationExitListener.java) | 原厂HardKeyQuit监听 |
| [diplay-navigation/FawFactoryTurnCode.kt](diplay-navigation/FawFactoryTurnCode.kt) | 厂商NEW_ICON映射参考，不跨接口套用 |
| [diplay-system/HongqiWheelControls.java](diplay-system/HongqiWheelControls.java) | 原厂硬按键、临时独占及view38焦点生命周期 |
| [diplay-system/FawVendorInstaller.kt](diplay-system/FawVendorInstaller.kt) | 原厂安装服务识别、签名/版本门禁和受保护暂存；实际安装仍需原工程的完整更新链 |

拷贝文件保持原字节，来源、包内路径和SHA-256见 [copied-source-index.json](../evidence/copied-source-index.json)。源文件中的历史注释不是新的实车通过声明。附带应用代码保留AGPL-3.0-only等原有声明，许可全文见 [LICENSE-AGPL-3.0.txt](LICENSE-AGPL-3.0.txt)；原厂恢复的接口声明/片段属于取证材料，不宣称拥有或重新许可OEM SDK。
