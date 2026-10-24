# 红旗 HS5 接口与使用手册

交付日期：2026-10-15。面向 HS5 Android 9 车机、DiPlay 与 xcertplay 的开发和联调人员。

**先打开 [可搜索接口目录 index.html](index.html)，再阅读 [可直接浏览/打印的使用手册](使用手册.html)（[Markdown原件](01-接口使用手册.md)）。** HTML 自带数据，可直接离线打开，无需服务器、安装依赖或联网。

| 文件 | 内容 |
| --- | --- |
| [使用手册.html](使用手册.html) | 合并说明、全部章节与示例入口的离线阅读/打印版 |
| [01-接口使用手册.md](01-接口使用手册.md) | 接入步骤、车辆读取、空调座椅控制、导航/HUD/显示、方向盘、音频、无线、系统服务及错误处理 |
| [02-模块与完整合约.md](02-模块与完整合约.md) | 55 组模块编号及完整方法签名、事务号；仪表数据模型与 CarPlay 常量 |
| [03-权限与实车验收.md](03-权限与实车验收.md) | 权限层次、只读诊断、场景验收和待确认项 |
| [04-证据与复核.md](04-证据与复核.md) | 材料版本、提取方法、证据索引、复核命令与验证结果 |
| [catalog/interfaces.json](catalog/interfaces.json) | 完整声明目录，含方法、字段、常量、来源和证据定位 |
| [catalog/methods.csv](catalog/methods.csv) | 方法检索表（UTF-8 BOM，可用 Excel 打开） |
| [catalog/binder-transactions.csv](catalog/binder-transactions.csv) | 从 Stub 常量取得的事务号，未按方法顺序猜测 |
| [catalog/module-map.csv](catalog/module-map.csv) | 模块编号、管理器、Binder 接口及反汇编原文位置 |
| [catalog/fields-and-constants.csv](catalog/fields-and-constants.csv) | 字段和静态整数常量；字段排列不代表 Parcel 顺序 |
| [examples/README.md](examples/README.md) | 可编译只读示例，以及当前项目调用源码快照的使用方法 |
| [tools/hs5_diagnostics.py](tools/hs5_diagnostics.py) | 手动运行的只读 ADB 诊断工具；本次未连接或改变车机 |
| [验证报告.json](验证报告.json) | 本次目录、示例及交付检查结果 |
| [SHA256SUMS.txt](SHA256SUMS.txt) | 逐文件完整性清单 |

这里“完整”指对**本包所列恢复材料及选定车端命名空间的声明进行完整导出，并整理已有使用证据**。它不是红旗官方全车型 SDK、全车 ECU/CAN/UDS/DoIP 数据库或 Apple CarPlay/MFi 授权规范。3222 的 Qisi/FAW、既有 system-analysis 框架、3092 的无线权限证据分别标记来源，不合并成同一实车的通过结论。

证据分为静态声明、原厂实现/调用、当前 App 接入和实车验收。目录默认仅为静态声明；自动生成的事务号也不等于参数语义或权限已经全部核实。正文明确标出的车控、导航等条目具有进一步实现证据；本次交付没有新增 HS5 实车验收。

本包是三套源码交付的补充资料，没有修改应用、服务端源码或此前源码归档。附带应用源码片段遵循原项目 AGPL-3.0-only 等原有许可，见 [示例说明](examples/README.md)。
