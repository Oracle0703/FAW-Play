# DiPlay 最新源码交付

当前修改版 **0.2.50-FAW-AUTH1 / versionCode 124**，Release 包名 `com.shihab.diplay`。基于 **2026-10-15（北京时间）** 的 0.2.49 源码交付包修改，目录名保留原快照名称。Android Studio 打开 `DiPlay/`。

本套独立交付 Android 工程，包含 common、shared、mobile、automotive、home、maphost 六个模块、JNI／Opus 源码、资源、测试、构建及原有文档。后台、站点和站点构建脚本已归入独立服务端源码包，Android 构建不依赖它们。原有文档中的 `authorization-server/` 引用应到本次服务端包查阅。

```sh
python3 verify_source.py
cd DiPlay
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug -PfawLicenseDebug=false
```

环境为 JDK 25、Android SDK 37、NDK 28.2.13676358、包内 Gradle 9.5.0 wrapper；最低系统 Android 9／API 28。保留 arm64-v8a、armeabi-v7a、x86_64。Debug 包名为 `com.shihab.diplay.hudtest`，带 `-hud-test` 后缀。

- [构建与配置](docs/构建与配置.md)
- [架构与维护](docs/架构与维护.md)
- [原有构建说明](DiPlay/docs/BUILD.md)
- [软件授权说明](DiPlay/docs/PRODUCT_AUTHORIZATION.md)
- [远程更新说明](DiPlay/docs/REMOTE_UPDATES.md)
- [本次验证记录](verification.json)

本次将离线授权上限改为 30 天，已有票据从签发时间计算，安装新版即执行新限制；更新自动在线核验本机已激活授权，无授权时沿用手机扫码输入授权码。缓存更新包及继续系统确认也须重新在线核验。CarPlay 连接、手动断开和原有安装操作流程保持原行为。旧版不支持新的受保护更新接口，首个新版需受控覆盖安装，并沿用原发布签名。automotive 仍独立声明 **1.2.4-AUTH3 / 1204**，本次未发布 automotive 产物。

当前待发布版还包含固定更新请求标识 `Autunn: 1`，覆盖检查、下载及安装前核验。服务端后台可即时控制是否验证该标识，默认关闭；关闭不跳过原有授权验证。mobile 124 尚未正式发布，本次继续在同一待发布版本内修改。

本次验证范围与结果以 `verification.json` 为准；其中历史记录只反映修改前快照，不代表本版通过。未执行车辆／iPhone 或生产服务验收。

普通 Debug 产物没有独立连接 iPhone 所需的运行认证身份。车测和 Release 需配置外部身份与原发布签名；正式 Release 同时要求产品授权信任配置。本包不含任何实际私钥和生产配置。`SOURCE_PROVENANCE.json` 保留修改前的来源记录，当前文件完整性以更新后的 `manifest.json` 为准；原 ZIP 归档未改写。项目许可证和第三方声明完整保留。
