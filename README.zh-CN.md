# Autunn Play

独立开发与维护的安卓车机连接软件。有线与无线 CarPlay 播放栈使用 xcertplay；配件 MFi 仍使用既有 DiPlay 配置（`DIPLAY_AUTH_ASSETS_DIR` 下的 `offline-mfi/identity.pk8` 与 `certificate.p7b`），不改用 xcertplay 的 `local-auth`。并提供与具体车机、固件相关的仪表地图、HUD 和方向盘操作适配。

本产品与中国一汽、红旗品牌及 Apple 无隶属或官方授权关系。车型名称仅用于说明兼容性，CarPlay 为 Apple Inc. 的商标。

## 当前版本

`0.3.2-AUTUNN20 / versionCode 151`，客户端重构车机验收版。覆盖连接、三屏、音频、启停、设置、授权和更新的职责整理，修复 Android 9 连接恢复、输出故障隔离及日志开关持久化；详见[客户端架构与维护边界](CLIENT_ARCHITECTURE.md)。3212、3222 的实际连接耗时、仪表和音频表现需分别验收。Android 包名保留为 `com.shihab.diplay`，使用原发布签名覆盖升级；调试包使用独立后缀。

当前适配对象包括部分红旗 HS5 安卓车机，不代表所有年款、系统和固件都适用。最低 Android 9；连接模式和车辆接口能力以具体车机为准。历史上游 BYD 适配记录与当前产品的验收范围不同。

## 安装、升级与授权

- APK 安装在安卓车机上。首次安装请通过项目提供方的受控渠道取得安装包。
- 已安装版本使用原“关于 → 检查更新”入口，更新仍由服务端核验设备授权，并遵守后台 Autunn 请求头开关设置。
- 同包名、同签名覆盖升级保留原有授权、设置及配对资料。不要为改名卸载、清除数据或重新生成设备身份。
- 基础 CarPlay 与需要授权的增强功能按原规则工作；离线授权最长 30 天。
- 新版服务端生成的兑换码采用 AUTUNN- 前缀。原兑换码、授权有效期和设备额度继续按原规则使用，无需重新发码。

## 开发与维护

- [构建说明](docs/BUILD.md)、[授权构建](docs/PRODUCT_AUTHORIZATION.md)、[接口清单](docs/API.md)
- [客户端架构](CLIENT_ARCHITECTURE.md)、[版本变更记录](docs/RELEASE-NOTES-NEXT.md)
- [更新协议](docs/REMOTE_UPDATES.md)、[品牌资源](branding/README.md)、[本次更名说明](docs/RELEASE-NOTES-AUTUNN1.md)
- [第三方声明](docs/THIRD_PARTY_NOTICES.md)、根目录 LICENSE 及 docs/licenses 中的原许可应随源码保留。

普通源码构建不包含发布私钥、正式授权配置或 CarPlay 认证资料。正式构建由 scripts/build-licensed-apk.py 显式接收本机受控输入，不应将这些材料写入源码或对外源码包。

原 DiPlay 说明保存在 [历史英文说明](docs/UPSTREAM-DIPLAY-README.md)与[历史中文说明](docs/UPSTREAM-DIPLAY-README.zh-CN.md)。其中的版本、下载渠道及实车记录属于上游历史，不是当前发行版说明。
