# 设备授权与防二次打包

2026-10-10 AUTH5：仅 HUD、仪表地图和方向盘键控需要授权；基础 CarPlay 可直接连接和使用。服务端新增永久购买、累计首次激活次数和设备备注管理。AUTH5 APK 为 `0.2.18-FAW-AUTH5 / versionCode 92`，从隔离源码快照构建，包含已更新的 UI；三个模块的主源码与当前工作区一致。部署先安装 AUTH5，再加载新版服务端。

当前 mobile 工作树与本轮交付包为 `0.2.23-FAW-AUTH6 / versionCode 97`，automotive 保持原版本，本轮没有构建 automotive 包。AUTH6 增加购买入口及服务端批量生成，保留 CONNECT3 的连接/仪表修复和既有三功能授权。NAS 服务 JAR 已准备，需重建 license-api；本轮仅做模拟器与隔离服务验证，手机/HS5 验收和静默安装仍待完成。历史测试仅代表其对应版本。

## 使用和授权策略

车机的「设置 → 授权」显示真实授权状态。已授权车机显示离线票据到期时间和“再次联网验证授权”；未授权车机显示二维码占位及“生成设备激活二维码”“再次联网验证授权”两个按钮。点击生成后才显示真实二维码，等待期间可用“取消本次扫码”停止。手机扫码显示设备名称与识别码，用户核对后输入你提供的兑换码并确认；车机自动领取票据，用户无需账号或邮箱。默认每码一台、一次累计激活，永久购买；管理员可调整设备额度、累计首次激活上限和永久/按天有效期。二维码绑定已通过私钥证明的设备及 App，默认 10 分钟有效，实际期限显示服务端返回值；同一码同一设备重复激活不重复占用额度。Keycloak 仅负责管理员登录。

主题切换保留当前二维码和后台等待流程。离开授权页或销毁 Activity 会停止等待并尽力取消本次服务端会话；若手机已经确认，已验证的有效票据仍保存。取消扫码不撤销已有授权，界面没有关闭授权检查的开关。开发版本和服务配置缺失分别明确显示其真实状态。

管理员可通过授权 API 的 `/admin/` 网页登录并单次或批量（1–1000 个）生成兑换码，复制全部或导出 CSV，设置同时设备额度、累计激活次数、备注及永久/按天期限。已兑换授权可查看设备、编辑授权/设备备注、期限与额度，撤销或恢复设备。刷新会通过当前标签页 sessionStorage 的刷新令牌恢复登录，退出清除会话；明文兑换码不恢复。仍使用现有 faw-play 的 license-admin 管理员，Keycloak 自带后台用于管理账号与角色。新功能需在 NAS 加载新版 JAR 后使用；新版 App 接受旧票据，旧 App 不认识新的三功能票据，所以先升级 AUTH5；详见授权服务 README。

首次激活需要联网。设备在 Android Keystore 创建 P-256 密钥，服务端绑定公钥；私钥不导出，优先使用设备提供的硬件保护，不强制 StrongBox。重装、清除数据、换设备会生成新密钥，占用新额度，需要管理员迁移。客户端不依赖 IMEI、MAC 或 ANDROID_ID。

服务器通过数据库事务限制已绑定、未撤销设备数，不是同时在线数。累计次数按首次绑定的不同公钥计算；重复激活不扣次数，重装或新设备扣一次，撤销/恢复不返还或增加累计数。旧授权迁移保留原截止时间与额度，不强加累计上限。永久购买以 NULL 截止时间保存，新签和续签票据最多有效30天；有限购买的票据不超过购买到期时间。原90天请求与配置仍兼容，但签发硬上限为30；未声明新离线期限的旧版仍领取七天票据。旧90天票据服务端仍能验签和联网续期，新版客户端更新后首次读缓存即按 `min(exp, iat+30天)` 执行，不能从安装时重新起算。联网自动续期；撤销通常在收到明确撤销响应时生效，离线撤销最迟受票据到期限制。纯离线情况下无法实时严格限制并发。基础 CarPlay 不受购买授权影响。增强功能在授权过期或明确撤销后停止输出，HUD/仪表清理已接管状态，方向盘按键归还原厂；网络失败时有效离线票据仍可使用。

## 信任边界

- 服务端授权签名私钥只位于服务端。APK 编译时固定 SPKI 验签公钥、HTTPS 地址、OIDC issuer 和 APK 当前签名证书摘要。
- 服务端的随机挑战 120 秒有效、只能使用一次；设备签名绑定操作、nonce、packageName、公钥摘要和请求载荷摘要。
- ES256 票据必须验签，并检查 issuer、实际 packageName、设备密钥摘要、HUD/仪表地图/方向盘键控权限和兼容历史最多90天的签名时间范围；本机可用期另硬限制在签名iat之后30天内。
- 新在线票据的已签名 `challenge_id` 必须匹配客户端刚发起的挑战。只有此时才能用已签名 `iat` 重建时间锚点；未签名 `serverTime` 不能解锁缓存。离线使用单调时间和持久化墙钟下界检测回拨。这不能替代可信硬件远程证明。
- 票据通过 AtomicFile 存在 `noBackupFilesDir`，设备密钥在 Keystore，正常系统备份不会复制这些授权。APK 本地要求单个当前签名证书匹配配置。
- 基础 UI、CarPlayController 与 AirPlay 连接不再设置购买门禁。检查位于 HUD/仪表实际输出与轮控注册/发送处；权限撤销触发已接管输出清理。应用内普通媒体按钮和车机视图焦点不与方向盘授权绑定。
- R8 开启代码与资源优化。`common/consumer-rules.pro` 只保留现有反射、JNI、轮控和厂商接口必须的名称。mapping 是发布追踪材料，保存在构建输出，勿与 APK 公开分发。

本地签名校验、混淆和设备密钥证明提高修改/克隆成本，不能保证纯本地功能不可破解。服务端不把 APK 自报证书摘要当作远程证明。没有 GMS 的车机不强制 Play Integrity。商业加固需单独选择供应商、费用和兼容范围，目前未购买、上传包或接入第三方加固 SDK。

后续可先评估 360 加固的商业方案，重点确认 Android 9+、arm64/arm32、后台服务、JNI 和车机厂商接口的兼容性。加固工具不得替换发布签名；加固完成后按工具要求先 zipalign，再用原 release keystore 签名，核对证书与 APK 编译摘要一致，然后重测授权、CarPlay、声音、HUD、后台自动连接和离线恢复。加固后的包需要单独车测，原包编译和模拟器结果不能直接沿用。

## 构建

普通 debug 是明确关闭授权的源码开发包，不能作为正式授权产品分发。`-PfawLicenseDebug=true` 可测试完整授权；此时也必须配置验签公钥和真实 debug APK 签名证书摘要。本机 HTTP 只允许 debug 并限于 localhost/127.0.0.1/10.0.2.2；正式 Release 固定启用授权，只接受 HTTPS，不存在通过 Gradle 参数关闭授权的发布路径。

正式发布前需要授权服务器；扫码兑换流程不依赖 SMTP。部署流程见 [授权服务](../authorization-server/README.md)。生产通常使用 API 与登录两个 HTTPS 域名，或同源下相应路径。所有 APK 配置与服务端/Keycloak external issuer 必须一致。

推荐用构建脚本从本地密码文件读取签名口令，它会自动计算当前 APK 签名证书摘要并执行 Release 验证、R8 和 lint：

```sh
python3 scripts/build-licensed-apk.py \
  --server-url https://license.example.com \
  --oidc-issuer https://auth.example.com/realms/faw-play \
  --public-key authorization-server/secrets/android-public-key.txt \
  --keystore /private/path/release.p12 --alias YOUR_ALIAS \
  --password-file /private/path/release.password \
  --auth-assets /private/path/runtime-assets \
  --output /private/path/Autunn-Play-AUTH5-release.apk
```

示例域名仅是占位，必须替换为实际运行并完成 TLS 验证的服务。`--auth-assets` 仍按原有构建约束显式提供 CarPlay 认证资料；省略它会得到不能独立连接 iPhone 的源码包。工作站按需要传 `--java-home` 和 `--android-sdk`。脚本拒绝覆盖输出 APK，不输出口令或私钥。

手动构建所需环境变量为 `AUTUNN_LICENSE_SERVER_URL`、`AUTUNN_LICENSE_OIDC_ISSUER`、`AUTUNN_LICENSE_PUBLIC_KEY_SPKI`（base64url SPKI）、`AUTUNN_LICENSE_SIGNING_CERT_SHA256`（小写 hex），账号 client 默认为 `faw-play-device`，可通过 `AUTUNN_LICENSE_OIDC_CLIENT_ID` 配置。原有 Android keystore 环境变量保持适用。`verifyLicenseRelease` 会检查 HTTPS、P-256 公钥以及证书摘要与实际 release keystore 的一致性；缺配置就失败。

## 验证与尚待验收

自动回归包括伪造票据、错误包名/设备、重放、回拨、重复小回拨、错误在线挑战、过期后在线续期、并发设备额度和撤销。后台默认 H2 测试；实际 PostgreSQL 必须使用独立测试数据库和显式 reset guard。2026-10-09 已通过 shared/common 全量回归、H2 和真实 PostgreSQL 后台回归，以及真实 Keycloak 本地端到端登录。模拟器 APK 已完成实际 Keystore 激活、续期、离线重启、网络失败保留票据、撤销清缓存和下次启动门禁；Release R8 测试包也验证了授权页面入口。

AUTH2 已通过 shared 708 项、common 640 项（合计 1,332 通过、16 跳过），后台 H2 集成及更新校验 21 项、mobile/automotive R8 Release 和 mobile lint。NAS 的 HTTPS discovery、Device Grant、实际 Release APK 公网二维码与无更新响应已检查。AUTH2 的邮件方案已取消。AUTH3 的公网实际兑换与真实 HS5 激活仍待验收；历史 AUTH1 本地激活结果不能当作本轮公网激活结果。车机协议、声音、轮控、HUD 仍需实车测试。当前交付范围见工作区的 `outputs/reports/current/FAW-Play-AUTH2-NAS接入-20261009.md`。

应用内更新入口在「关于 → 检查更新」，签名更新清单与完整 APK 校验使用 NAS 授权公钥和原发布证书。静默安装能力检测、发布步骤和限制见 [远程更新](REMOTE_UPDATES.md)。

项目仍含 GPL-3.0/AGPL-3.0 及第三方许可；发布时保留许可证、版权和对应源码义务。新增服务端授权不自动改变上游允许的再分发权益。

AUTH3 的历史验证和部署记录见工作区 `outputs/reports/current/FAW-Play-AUTH3-扫码兑换-20261009.md`。AUTH4 沿用同一兑换协议，本轮界面、构建及实车验收结果以对应交付记录为准。旧账号授权票据仍可在线续期；新码不用于旧账号绑定入口。


授权页的「购买授权码」使用用户提供的购买链接二维码；「扫码激活」使用服务端生成且绑定设备的短期二维码，两者用途独立。手机激活页也可直接打开购买链接，购买后返回输入授权码。服务端批量字段、事务边界、明文仅显示一次和 NAS 操作见授权服务 README 与本轮 AUTH6 交付报告。

更新检查、下载和安装前在线授权都要求有效购买资格与当前设备证明，基础CarPlay不受该门禁影响。协议见 [API.md](API.md)，现有连接、断连与安装时车辆状态门禁不变。
