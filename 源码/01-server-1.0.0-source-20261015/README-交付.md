# 服务端源码交付

版本声明 **1.0.0**；快照日期 **2026-10-15（北京时间）**。来自 FAW-Play 当前工作树，包含最新未提交的后台和网页改动。JAR 文件名仍为 1.0.0，不能据此断言与历史 NAS JAR 一致。

源码根目录为 `server/`，独立 Java 工程在 `server/authorization-server/`。保持这个相对目录结构：Gradle `processResources` 和 Docker 构建会读取同级 `site/`。本包附完整 Gradle wrapper、管理网页、手机激活页、站点、数据库迁移、Keycloak 模板、Docker／Compose／Caddy、管理 CLI 和测试。

```sh
python3 verify_source.py
cd server
./gradlew -p authorization-server test bootJar
node --test authorization-server/scripts/test_admin_web.cjs authorization-server/scripts/test_web_api.cjs
python3 -m unittest discover -s authorization-server/scripts -p test_admin.py
```

构建基线使用 JDK 25；Java 源码和产物目标为 17，Docker 构建镜像使用 JDK 17。输出为 `server/authorization-server/build/libs/faw-play-authorization-1.0.0.jar`。

- [构建、部署与运维](docs/构建部署与运维.md)
- [架构与维护](docs/架构与维护.md)
- [完整服务端说明](server/authorization-server/README.md)
- [HTTP API 契约](server/docs/API.md)
- [本次验证记录](verification.json)

产品授权、设备额度、批量码、设备替换、累计次数重置、授权总数、远程更新上传、诊断和审计代码已包含。数据库迁移保留 V1–V7。本次新签与续签离线票据硬上限为 30 天，兼容旧 90 天票据及原 7 天请求；更新检查、下载和安装前授权均需设备证明及实时有效授权。后台“应用更新”页可即时开关 `Autunn: 1` 请求头校验，默认关闭，状态保存在现有更新目录，重启保留；关闭时原有授权校验仍保留。

`com.shilapi.xcertplay.hs5` 尚不在源码默认包名允许名单内。正式接入需要显式配置允许项、APK 签名证书和独立更新目录；本次未更改服务端默认行为或 NAS 部署。服务签名私钥、生产密码、数据库和原 APK 发布 keystore 均由持有人另行配置。

`SOURCE_PROVENANCE.json` 保留原始交付的来源记录，修改后的当前文件哈希以 `manifest.json` 为准，原 ZIP 归档未改写。7 个 APK 是合成测试样本，不含产品认证身份；其说明在 `server/authorization-server/src/test/resources/update-apks/README.md`。上游版权及许可证随 `server/LICENSE` 保留。现有部署的数据与密钥保护步骤见完整服务端说明末尾；本次未操作线上环境。
