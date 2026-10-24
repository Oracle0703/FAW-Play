# Autunn Play 授权服务

独立的 Java 17 / Spring Boot 服务，使用 PostgreSQL 持久化授权和设备绑定。它不加入 Android Gradle 模块，不影响原来的 Android 构建。车机通过设备二维码与手机兑换码确认完成激活，用户无需账号或邮箱。Keycloak 仅用于管理员登录；APK 不保存管理员凭据或客户端秘密。

已实现：单次随机挑战、Android Keystore P-256 公钥绑定、兑换码激活、设备数量事务限制、最多 30 天 ES256 离线票据、设备撤销/恢复、管理员额度和购买有效期管理、30 天一次的账号自助解绑、审计记录、有界请求限流和签名远程更新清单。首次激活必须联网；网页或管理 CLI 默认创建 1 台设备的兑换码。

2026-10-09 已完成 AUTH3 无邮件扫码激活，升级 JAR 已写入 NAS `/volume1/docker/FAW-Play/license-api/server.jar`，用户已完成镜像重建和容器启动；公网激活页、设备二维码和签名取消已核对。Lucky 继续提供 HTTPS。管理员 realm 已关闭邮件验证和自助注册。AUTH2 是历史账号模式，AUTH3 公网激活与 HS5 安装仍待验收。以下本地 Compose 和 Caddy 为通用模板，NAS 以交付的 Lucky 配置为准。更新发布说明见 [应用内远程更新](../docs/REMOTE_UPDATES.md)。

这里的设备额度限制的是已绑定且未撤销的设备数。离线票据有效期间，撤销不能立即传到车机；离线时也不能严格限制同时在线数量。已经流出的旧 APK 不能通过这个新服务追回。

## 品牌更名兼容（2026-10-17）

页面展示改为 Autunn Play；仅新生成的兑换码使用 AUTUNN- 前缀。原 FAW- 兑换码继续按完整明文摘要查找、激活和管理，无需改写记录或重发。数据库结构、授权签名、受信任 APK 证书、realm/client IDs 和更新请求契约保持原值。

已有 Keycloak 不重新导入 realm 文件。需要更换其登录页名称时，只在管理界面修改 Realm 的 Display name 和客户端的 Name；保持 Realm name、Client ID、audience、用户、角色和密钥不变。模板中的显示名称只影响新建环境。

历史运维路径和协议常量可能保留旧名称，它们是兼容标识，不是对外产品品牌。已有环境应使用本次交付的更新工具，不能执行以下初始化步骤。

## 本地启动

需要 Docker Compose v2；此本地模板的服务器与 Keycloak 均只监听 `127.0.0.1`。当前工作站未安装 Docker，没有在此执行模板容器启动；用户的 NAS 部署和公网 HTTPS 已有单独证据。

```sh
cd authorization-server
sh scripts/bootstrap-local.sh
docker compose up -d --build
```

初始化脚本生成随机的数据库及 Keycloak 管理员密码，保存到被忽略的 `.env`，不向终端输出密码。它生成 P-256 PKCS8 私钥和 SPKI 公钥到被忽略的 `secrets/`。重复执行不会覆盖已有密钥或密码。`secrets/android-public-key.txt` 是可以放入 APK 构建配置的 **base64url SPKI 公钥**；私钥只能留在服务端。Compose 使用生成密钥时的 UID/GID，以便非特权进程读取权限为 0600 的密钥文件。

1. 打开 `http://localhost:8081/admin/`，使用 `.env` 中的 Keycloak 引导管理员登录。选择 `faw-play` realm。
2. AUTH3 不要求终端用户账号、邮箱或 SMTP。首次导入已关闭邮箱验证与自助注册；已有 realm 应通过管理接口更新设置，不能靠重启覆盖。
3. 创建一个日常授权管理员的 `faw-play` realm 账号，分配 realm role `license-admin`。Keycloak master 管理员与这个授权管理员是两个不同身份。
4. 打开授权 API 的 `/admin/` 管理网页，或使用下方 CLI 生成兑换码。用户在手机扫码页面填码，服务绑定车机公钥；同一码与同一把密钥重新激活不重复占额度。清数据或重装会产生新密钥，并占用新的设备额度。

realm 文件仅在首次导入时创建配置；已有 realm 不会因为重启容器自动覆盖。后续账号、SMTP、角色和 client scope 的变更应通过 Keycloak 管理接口处理。

首次导入的 `faw-play` 默认启用简体中文，保留英文选择。已有服务在对应 realm 的 Realm settings → Localization 启用 Internationalization，Supported locales 添加 `zh-CN`，Default locale 选择简体中文并保存。管理后台管理员属于 `master` realm，应在 `master` 也启用该设置；管理员账号的语言偏好可设为 `zh-CN`，退出并重新登录后生效。更新导入 JSON 不会改变已有 realm。

真实 Keycloak 26.4 本地登录验证发现，仅定义自有 client scopes 会丢失默认 `basic` scope 的 subject mapper，导致 access token 缺少 `sub`。导入文件已在两客户端共用的 `faw-license-audience` 加入正式 provider `oidc-sub-mapper`，设置 `access.token.claim=true` 和 `introspection.token.claim=true`，以 Keycloak user ID 提供稳定账号身份；现有 realm 应只补该 mapper，不能重导覆盖其他用户设置。静态配置回归同时检查 subject、API audience、realm role mapper 和两客户端必选 scope。

本地 Android 测试可用 `adb reverse tcp:8080 tcp:8080` 和 `adb reverse tcp:8081 tcp:8081`，让测试设备访问开发电脑的 `localhost`。HTTP 只能用于明确配置的 debug 构建；正式 APK 应配置真实 HTTPS 域名。账号/服务域名配置请配合 Android 授权构建说明。

`GET /health` 是进程存活检查，不表示 SMTP、Keycloak、数据库或外部网络完整可用。

## 管理操作

### 网页生成兑换码

访问 `https://license.example.com/admin/`（本地模板为 `http://localhost:8080/admin/`）。点击「管理员登录 → 继续登录」，在新窗口使用 faw-play realm 的 license-admin 管理员账号确认，返回管理网页。选择设备数量、累计激活次数和永久或按天授权，点击「生成兑换码」，再复制发给用户。有效期由服务端时间计算，从生成时开始；默认 1 台、1 次累计激活、永久授权；按天授权从生成时间计算。

Keycloak 自带管理后台用于账号和角色管理；这里的管理网页由授权 API 提供。它沿用现有 `faw-license-admin` 的 Device Authorization Grant，无需新建 client、修改 CORS、增加容器或更新 App。浏览器密码只提交到 Keycloak；访问令牌保留在网页内存，刷新令牌存放在当前标签页的 sessionStorage。刷新页面通过 Keycloak 刷新令牌并重新校验 license-admin 权限，恢复登录；明文兑换码仍会清除。点击退出清除标签页会话，关闭标签页也结束该页面会话；不使用 localStorage。服务端仍校验 JWT 签名、issuer、API audience 和 license-admin 角色，Keycloak 的单点登录会话独立保留。

兑换码明文只在生成时返回，不提供历史明文查询。所有管理页、登录代理与管理 API 均设置 no-store、no-referrer 和 CSP。NAS 的登录代理使用现有 Docker 内部 Keycloak 地址，浏览器登录链接仍指向公开 HTTPS auth 域名。

### 搜索和删除卡密

管理员登录后，在「搜索与删除卡密」粘贴完整卡密。支持查找升级前已经生成的卡密，显示未激活、已激活、已过期、关联授权已作废或已删除状态，以及备注、额度、激活次数和关联设备。数据库只保存卡密指纹，因此使用完整卡密精确查询，不提供明文恢复或片段搜索。

点击「删除此卡密」后需要再次确认。未激活卡密停止兑换；已激活的设备模式卡密同时作废其专属授权，关联设备不能继续领取或续期票据。卡密指纹、兑换关系、设备历史和审计记录保留，删除后仍可搜索。新票据最多 30 天；旧 90 天票据保留服务端兼容，新版客户端立即按签名 iat+30 天限制。旧账号模式的已兑换卡密可能关联共享授权，不能通过卡密删除连带撤销整个账号，应从授权详情管理。

搜索使用 `POST /v1/admin/activation-codes/search`，请求体为 `{"activationCode":"完整卡密"}`；删除使用 `DELETE /v1/admin/activation-codes/{codeHash}`。两者均要求 `license-admin` 权限，搜索结果不返回卡密明文，明文也不进入 URL 或审计目标。

V5 迁移只给 `activation_codes` 增加可空的 `deleted_at` 字段；已有卡密、购买授权、设备、额度、有效期和备注保持原值，不需要重新发码或激活。更新服务端 JAR 即可使用，不需要重装 App。

### 命令行

CLI 只依赖 Python 3 标准库，保存的访问/刷新令牌在 `secrets/admin-session.json`，权限为 0600。它采用浏览器授权，不接受管理员密码、不打印访问令牌，并拒绝所有 HTTP 重定向，避免管理 Bearer token 被转发到其他地址。

```sh
python3 scripts/admin.py login
# 以下示例有效期为 epoch seconds；运行时换成实际购买到期时间。
python3 scripts/admin.py create-code --devices 1 --days 365
python3 scripts/admin.py licenses
python3 scripts/admin.py view LICENSE_UUID
python3 scripts/admin.py update LICENSE_UUID --devices 2 --valid-until 1893456000
python3 scripts/admin.py revoke LICENSE_UUID DEVICE_UUID
python3 scripts/admin.py restore LICENSE_UUID DEVICE_UUID
python3 scripts/admin.py audit
```

生产使用：`python3 scripts/admin.py --api https://license.example.com --issuer https://auth.example.com/realms/faw-play login`。后续命令需相同参数，或设置 `LICENSE_API_URL` 和 `OIDC_ISSUER` 环境变量。管理员密码、Bearer token、私钥不要进入命令输出、工单或源码。

兑换码由安全随机数生成，数据库仅保存 SHA-256 摘要；明文只在创建接口的响应中出现一次。新兑换码为 device 模式：手机扫码后确认绑定的设备，输入兑换码创建授权，无需用户账号。同一码允许绑定管理设置的设备数量，同一密钥重复激活不重复计数；额度用完时拒绝新密钥，已撤销设备须管理员处理。首次兑换后以许可证的实时有效期/额度为准，管理员可调整。`--days 365` 表示生成后 365 天到期，默认 1 台；`--valid-until` 可指定绝对到期秒数。迁移前的 account 模式码保留原语义，不能用于匿名扫码，应在升级后生成新码。

管理 API 全部要求 OIDC Bearer token 的 `aud` 包含 `faw-license-api`，且 realm role 为 `license-admin`。

| 操作 | API |
|---|---|
| 创建兑换码 | `POST /v1/admin/activation-codes`，JSON `{deviceLimit:1,validUntil:到期秒数}` |
| 列出授权 | `GET /v1/admin/licenses?offset=0&limit=50` |
| 查看设备 | `GET /v1/admin/licenses/{licenseId}` |
| 调整额度/有效期 | `PUT /v1/admin/licenses/{licenseId}`，JSON `{deviceLimit,validUntil}` |
| 撤销/恢复设备 | `POST /v1/admin/licenses/{licenseId}/devices/{deviceId}/revoke` 或 `/restore` |
| 审计记录 | `GET /v1/admin/audit?offset=0&limit=50` |
| 账号查看自己的授权 | `GET /v1/licenses/me`，任意已登录账号，仅返回自己的授权 |
| 账号自助解绑 | `POST /v1/licenses/me/devices/{deviceId}/unbind`，账号每 30 天最多一次，返回 `nextAllowedAt` |

降低设备额度前必须先撤销超过新额度的设备。自助解绑会撤销旧设备，旧密钥不会自动重新激活；需要恢复旧设备时，由管理员检查额度后恢复。管理员可直接处理换车机或售后迁移。审计不保存兑换码、授权 JWT 或私钥。

## Android API 合同

AUTH3 扫码激活接口（均无需终端用户 OIDC）：

1. 设备申请 `purpose=pair` 挑战，私钥签名 payload 为原始 `deviceName`，POST `/v1/activation/sessions` `{challengeId,signature,deviceName}`。返回 `sessionId,pollToken,activationPath,deviceFingerprint,expiresIn,interval`；10 分钟有效，轮询间隔 5 秒。
2. App 用已配置的 HTTPS API 基址加固定 `activationPath=/activate/#token=...` 生成二维码。二维码关联已证明持有私钥的 App、公钥和名称；手机页面从服务器获取名称和密钥摘要前 16 位识别码，由用户与车机屏幕核对。身份不取自可修改的 URL 参数。
3. 手机 POST `/v1/activation/details` `{browserToken}` 获取车机信息，再 POST `/v1/activation/approve` `{browserToken,activationCode}`。兑换码只经 HTTPS 请求体提交；明文码及两种随机会话令牌均不入库，二维码令牌置于 URL fragment，不进入 HTTP 地址或 Referer。
4. 设备 POST `/v1/activation/status` `{sessionId,pollToken}`，返回 pending 或 ready。手机二维码令牌与设备轮询令牌独立，手机不能领取车机票据。
5. ready 后申请 `purpose=activate` 的新挑战，私钥签名 payload 为 `sessionId`，POST `/v1/activation/claim` `{sessionId,challengeId,signature}`。核对同一公钥和 App 后，返回绑定本次挑战的签名票据 `{license,serverTime}`。截取二维码不能在其他设备领取授权。
6. 停止扫码可使用 `purpose=cancel` 签名 payload=`sessionId` 调用 `/v1/activation/cancel`。这是尽力取消；已提交的购买绑定不回滚。若手机已确认而车机没有收到票据，重新扫码并用同一码可恢复同一设备授权，不占新额度。
7. 续期仍是 `/v1/licenses/renew`：申请 `purpose=renew` 挑战，以旧 JWT 为 payload；不需要账号或兑换码。首次权益事务锁兑换码，后续锁许可证，防止并发超额；失败的兑换事务不留下账号/许可证，失败输入次数在事务外累计，每个二维码最多 10 次。

挑战接口为 POST `/v1/challenges` `{purpose,appId,publicKeySpki}`，默认 120 秒过期，错误签名也消费挑战。公钥为 base64url、无 padding 的 X509 SPKI DER P-256；设备证明签名为 base64url DER ECDSA。旧 `/v1/licenses/enroll` 继续保留账号模式兼容，受 OIDC 保护，不接受新 device 模式码。管理员 CLI 使用 `faw-license-admin` 的标准 Device Authorization Grant；master 后台管理员不等于 faw-play 的 license-admin 用户。

签名输入，无最后的换行：

```text
FAW-LICENSE-V1\n<purpose>\n<nonce>\n<appId>\n<hexLowerSHA256(SPKI bytes)>\n<hexLowerSHA256(payload UTF8)>
```

票据是 ES256 JWS。必须验证服务端公钥签名、固定 issuer `faw-play-license`、`aud` 等于实际 packageName、设备摘要匹配、时间范围和 `features` 包含 `carplay`。Claims 为 `iss,aud,sub,license_id,device_id,device_key_sha256,challenge_id,features,iat,nbf,exp`。其中 `challenge_id` 是本次成功消费的挑战 UUID，客户端接受在线新票据时必须核对它等于本次请求的 `challengeId`，再以已签名的 `iat` 校准可信时间锚点。这样能纠正车机误设的未来墙钟，同时防止旧票据响应复活；同秒签发也可通过挑战绑定辨别。过期时间为 `min(签发时间+协商天数,购买有效期)`。新版客户端在 `/v1/challenges` 中提交 `offlineDays: 30`，服务端以 `min(请求天数, license.offline-days, 30)` 决定签发期，即使既有配置仍为 90 或使用旧 90 天挑战也最多签发 30 天。字段允许 1–90，省略时保留旧客户端的 7 天。服务端把所选天数与 256 位随机数编码进不透明 `nonce`，设备证明签名仍覆盖完整 nonce，无需改变续期／领取接口或数据库。升级前已经发出的无前缀挑战按 7 天处理。旧 7 天票据保持原到期时间。旧 90 天票据仍能验签和联网续期；新版客户端更新后立即将缓存有效期取 `min(exp, iat+30天)`，按原签发时间计算，联网成功后领取新的最多 30 天票据。

默认允许的 packageName：正式 mobile `com.shihab.diplay`，debug `com.shihab.diplay.hudtest`，automotive `com.shilapi.xcertplay`。生产 Compose 默认去掉 debug，亦可显式设置 `LICENSE_ALLOWED_APP_IDS`。

错误响应统一 `{error,message}`：

| 状态 / error | 意义 |
|---|---|
| `401 UNAUTHORIZED` | 账号登录缺失或 access token 签名/issuer/audience/期限不合法 |
| `401 INVALID_PROOF` | 设备证明错误、跨设备或跨 appId |
| `401 INVALID_LICENSE` | 票据签名、issuer/audience/期限等校验失败 |
| `403 NO_LICENSE` | 账号无有效授权记录 |
| `403 DEVICE_REVOKED` | 设备已撤销，客户端应删除缓存票据 |
| `403 LICENSE_REVOKED` | 票据对应授权已替换，客户端应删除缓存票据 |
| `403 LICENSE_EXPIRED` | 购买已到期 |
| `403 DEVICE_LIMIT` | 活跃设备额度已满 |
| `403 INVALID_ACTIVATION_CODE` | 兑换码无效、已用或已过期 |
| `409 CHALLENGE_INVALID` | 挑战过期或重放 |
| `409 ACCOUNT_ALREADY_LICENSED` | 已授权账号不应再次兑换初始授权码 |
| `429 RATE_LIMITED` / `UNBIND_COOLDOWN` | 请求限流 / 自助解绑冷却 |

网络失败或 5xx 不应清除仍有效的离线票据；已开始的 CarPlay 会话应由 Android 在下次启动边界处理授权变化。

## 生产部署准备

获得两个 HTTPS 域名后设置 DNS A/AAAA 到服务器，在 `.env` 加入 `API_DOMAIN`、`AUTH_DOMAIN`、`TLS_EMAIL`。使用：

```sh
docker compose -f compose.yml -f compose.production.yml up -d --build
```

Caddy 自动申请/续期 TLS 证书，Keycloak 使用生产 `start` 和明确的外部 issuer；数据库未暴露端口，应用/Keycloak 的直连端口仅本机可见。开放 80/443，让应用使用 `https://API_DOMAIN`、OIDC issuer `https://AUTH_DOMAIN/realms/faw-play`。使用已有 Lucky 的 NAS 不需要另部署 Caddy。上线销售前仍需验收管理员角色、真实兑换码购买授权与车机激活/续期；AUTH3 不需要 SMTP。

私钥应备份到受控密钥存储，生产可以用 Docker secret/外部 secret manager 提供相同文件路径。Docker 构建上下文过滤了私钥、`.env`、测试、无关工程文件；运行时只读挂载密钥目录。不要把现有数据库换成新密码后直接启动：PostgreSQL 首次初始化后不会因 `.env` 改动自动改数据库口令。请通过数据库管理流程轮换。

当前仅支持一把授权签名公钥。贸然替换它会让现有 APK 无法验新票据；正式轮换应先实现双公钥过渡并更新 APK，再切换签名。保留数据库和密钥的独立备份。

内置限流以 socket peer 为准，不信任 `Forwarded`/`X-Forwarded-For`。每进程每分钟最多 1,000 个挑战/5,000 个 API 请求，每 peer 20 个挑战/120 个请求，peer 状态有界并按分钟清理，挑战按分钟清理。反向代理后所有客户端共享其 peer 限额；多实例或大规模使用应在可信网关增加共享限流，按受信网络拓扑设计来源识别，再调整这里的额度。登录页面同时启用了 Keycloak 暴力破解保护。

软件授权不等于可信硬件远程证明。服务端不把 APK 自报证书摘要当作真实签名证明；Keystore 公钥证明只表明请求方能使用这把密钥。车机可能没有硬件 Keystore、GMS 或 Play Integrity，root/修改系统也可能改变客户端行为。对纯本地 CarPlay 功能，攻击者仍可修改 APK 跳过本地门禁；混淆/加固增加成本，无法保证不可破解。已经放进原 APK 的 MFi 共用资料也不能用授权票据重新变成秘密。

## 构建和测试

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ../gradlew -p . test bootJar
python3 -m unittest discover -s scripts -p 'test_*.py'
node --test scripts/test_admin_web.cjs scripts/test_web_api.cjs
```

默认测试使用 H2 PostgreSQL 模式和临时随机测试签名密钥，不需要 Docker、Keycloak 或生产密钥。授权集成测试覆盖激活码摘要、30 天/购买到期边界、旧 90 配置硬封顶30天、超过 90 天的配置及签名票据拒绝、旧 7 天票据验签及续期兼容、HTTP 离线期协商与 nonce 篡改拒绝、签发配置降低后的 90 天票据续期、挑战重放和过期、错误签名和 P-256 限制、跨设备/跨包名、伪造/错误 audience、撤销后续期、并发额度、重装占额度、管理员角色、账号归属、自助解绑冷却、HTTP 无账号续期、限流、数据库清表保护以及独立 OpenSSL ES256 fixture 经 Java 验签和签名挑战绑定。另有 1 项 Keycloak scope 配置回归和 1 项 Python 本地 HTTP 回归，后者验证管理 Bearer 请求不会跟随重定向。

H2 通过不能替代 PostgreSQL 的锁和部署验证。有 PostgreSQL 时，可向**独立的测试数据库**设置 `TEST_DATABASE_URL=jdbc:postgresql://…/faw_license_test`、`TEST_DATABASE_USER`、`TEST_DATABASE_PASSWORD`，再设置显式确认 `ALLOW_DATABASE_TEST_RESET=I_UNDERSTAND_THIS_CLEARS_TEST_DATA`，执行 `../gradlew -p . test --rerun`。测试任务在提供外部数据库 URL 时始终实际执行，避免复用先前 H2 或另一数据库的结果；`--rerun` 也可显式要求重跑。realm JSON 已声明为测试输入，配置变化会触发 scope 回归。测试启动前检查该确认值与数据库名（必须以 `test_` 开头或包含独立 `_test` 后缀/片段），否则拒绝连接和执行清表。测试会迁移数据库并在每项测试前清空授权表，绝不能指向生产数据。

2026-10-09 已分别在 H2 与实际 PostgreSQL 16.10 上通过上述 15 项授权回归，实际 PostgreSQL 使用 Maven 发布的嵌入式二进制和本任务新建、仅监听 loopback 的隔离 `faw_license_test`，包含真实事务行锁下的并发设备额度验证。AUTH2 新增 5 项更新服务测试，覆盖签名域、原始字节、文件哈希/大小、符号链接、路径、数据类型与重复字段；本轮 H2/realm/更新测试共 21 项通过，NAS 的 JAR 与经测试的构建一致。构建 `bootJar` 和 Python 重定向回归也通过。新增更新测试未重新运行 PostgreSQL；它们不访问数据库。SMTP、真实用户授权和真实车机验收仍未完成。

## 2026-10-10 永久授权与设备管理

`valid_until = NULL` 明确表示永久购买资格，API 需提交 `permanent: true` 并省略 `validUntil`；普通期限提交 `validUntil`，不得同时提交永久和截止时间。永久购买仍签发最多 30 天的离线票据，联网刷新验证，不签发无期限 JWT。`license.offline-days` 默认为 30；1–90 的既有配置仍可加载，但新签/续签硬上限为30。服务端验签继续接受历史最多90天的票据，允许旧票据在离线期过后凭设备证明在线续期；新版客户端立即执行 iat+30天上限。新版票据只授予 `hud`、`instrument-map`、`steering-wheel`，新版 App 的基础 CarPlay 不要求授权。新版 App 可验证旧 `carplay` 票据；旧 AUTH3/AUTH4 App 需升级后才能使用新的功能票据。

管理网页新增已兑换授权列表和设备详情，可编辑授权备注、每台设备备注、同时设备额度、累计激活次数上限、永久/限期，并撤销或恢复设备。累计次数按首次绑定的不同公钥计算：同一公钥重复激活不扣次数，撤销或恢复不改累计数，重装产生新密钥扣一次。次数上限不能少于已用次数，同时额度不能少于未撤销设备数。备注最长 500 字符，只对管理员公开，网页按文本渲染。

V3 Flyway 迁移保留已有授权、设备和截止时间；旧授权不新增累计次数上限，管理员可自行设置。只需加载新版授权 API JAR，不需重建 Keycloak/PostgreSQL，也不需要改 Lucky。没有编辑现有购买数据或生产密钥。

新增管理接口：`GET /v1/admin/license-summaries`（分页汇总）、`PUT /v1/admin/licenses/{id}/devices/{deviceId}/note`。现有授权详情增加 `activationsUsed`、`permanent`，授权对象增加 `activationLimit`、`note`。创建和修改授权兼容旧 JSON 的有限期限格式。CLI 支持 `create-code --permanent --devices 1 --activations 3 --note "客户备注"`。

刷新回归：`node --test scripts/test_admin_web.cjs`。后端测试覆盖永久授权跨年续期与撤销、累计额度并发、重复激活、撤销不返还、备注权限/归属、旧数据库迁移。


## 网页上传与授权删除（2026-10-10）

管理员使用同一登录会话访问 `/admin/#updates` 的「应用更新」页面：选择应用和完整 Release APK，填写更新说明，勾选发布确认，再点击「上传并发布」。页面显示上传进度、错误原因、当前版本与发布结果。文件最大 512 MiB，说明最多 4000 字符；上传连接超时为 15 分钟。停止上传或网络中断后应刷新发布状态，文件传完后服务器可能已经开始发布。

- `GET /v1/admin/updates`：允许的包名、是否已配置可信证书及当前清单。
- `POST /v1/admin/updates/{appId}/stable`：multipart 的 `apk` 文件及 `releaseNotes` 字段，返回已发布清单。
- `DELETE /v1/admin/licenses/{id}`：删除已兑换授权，返回 `{"status":"deleted"}`。网页在授权详情提供二次确认。

这三个接口要求 `license-admin`；无需增设管理员密码或共享上传密钥。服务端使用固定版本 Android 官方 apksig 读取实际 APK 信息并验签，拒绝错误包名、证书、调试/split APK、minSdk < 28、降级及同版本不同文件。versionName 必须直接声明为文本，不能是资源引用。完全相同的 APK 可以安全重传并修改更新说明。JSON 更新签名与 APK 发布证书是不同用途，不能互换。

配置 `UPDATE_SIGNING_CERTIFICATES=允许的包名=原APK证书SHA256`，多项以英文逗号分隔。不配置证书的应用只能查看，不能上传；首次上传也必须匹配预先信任的证书。`UPDATE_DIRECTORY` 必须持久化为可写目录，所有者须匹配容器 UID/GID。Lucky 上游保持现有地址，但请求大小需容纳 APK、请求/响应超时应容纳上传和验签。

发布在目录内生成临时快照，验签后保存哈希命名的完整 APK，最后原子替换清单。旧 APK 保留，正在下载的设备不受影响；旧清单校验失败时拒绝覆盖。网页发布与本地 `publish-update.py` 共用 POSIX 文件锁；运行服务期间需使用这版发布脚本，避免旧脚本的 flock 与 Java FileLock 不互通。服务器保持既有更新签名与APK证书，但当前检查和下载已改为设备授权 POST，安装前还需独立授权；旧版无此协议，首次新版必须受控分发。

V4 增加 `deleted_at`，删除采用持久化注销记录：管理列表/详情隐藏该授权，全部设备和已批准扫码会话停止获取新票据，旧兑换码永久失效，不返还累计次数；历史授权/设备/审计留存数据库。它不会删除 Keycloak 用户。新票据最多30天；历史90天票据在旧客户端中仍按原到期时间处理，新版客户端立即按iat+30天限制。新生成的兑换码可让同一设备重新扫码获得新授权；旧的账号式授权删除后不允许直接复用该账号的旧授权。

本轮验证结果和 NAS 操作见项目 `outputs/reports/current/FAW-Play-管理页上传与授权删除-20261010.md`。不自动发布 APK、不修改生产授权数据，也不自动重建 NAS 容器。


## 购买入口与批量授权码（AUTH6，2026-10-10）

手机激活页新增购买二维码和链接，原图解码为 `https://wzyp.cn/item/lwbqa1`，只打开购买页面，不传递车机激活凭据。App 授权页显示独立购买卡片；买到码后仍需扫设备激活二维码并输入码。

管理员「生成兑换码」可设置生成数量 1–1000，下面的设备额度、累计次数、期限、备注应用于**每个码**。结果逐行显示并支持复制全部、导出 CSV（UTF-8 BOM，备注按文本转义）。明文不持久化，刷新/退出后不能再查看，需及时保存；每码的 CREATE_CODE 审计目标仍为哈希。

`POST /v1/admin/activation-codes/batch`，请求：

```json
{"count":10,"grant":{"deviceLimit":1,"activationLimit":1,"permanent":true,"note":"经销商批次"}}
```

响应为 `{"count":10,"codes":[...]}`，每项与原单码响应字段相同。普通用户无权生成；数量/嵌套授权字段校验失败不创建码；有效批次在一个事务创建全部码和审计，中途失败整体回滚。按天期限继续从生成时开始计算，`grant.validUntil` 为服务端时间基准下的 UNIX 秒数。原单码接口保持兼容，不需要数据库迁移或 Keycloak 修改。

本轮 H2 和独立 PostgreSQL 测试运行各 60 项通过，包含批量独立兑换、最大数量和事务回滚；网页实际生成、复制、导出、单码兼容和明文清理通过。NAS 新 JAR 已准备，启用仍需重建 license-api，详见工作区 AUTH6 交付报告。

全部服务端、网页、外部服务及本地协议契约见[API 接口清单](../docs/API.md)。网页共享请求入口为 `site/assets/api.js`，页面负责业务状态和文案。


## 30 天离线与授权更新（2026-10-15）

后台“应用更新”页新增“验证 Autunn 请求头”开关，默认关闭。新版客户端在更新检查、下载和安装前授权中均发送 `Autunn: 1`；开启后缺失、错误或重复标识会被拒绝，关闭时只跳过标识检查，原有设备证明及实时授权核验始终保留。开关对所有允许的应用统一生效，保存后立即对后续请求生效，无需重启。

设置原子保存在现有更新目录的 `.update-request-settings.json` 中，重启保留；升级时连同更新目录保留、备份，不改数据库。后台保存响应不确定时会重读服务端状态，无法确认则显示待确认并禁用。设置异常返回 `503 UPDATE_SETTINGS_UNAVAILABLE`，不会自动跳过验证。管理员专用 `GET/PUT /v1/admin/updates/settings` 的参数见 API 文档。应先更新客户端，再按需开启请求头校验；关闭开关不会恢复旧匿名 GET 接口。

检查、下载和提交安装分别申请一次 `purpose: "update"` 挑战，并按 [API](../docs/API.md) 中的固定四行 payload签名。接口为 `POST /v1/updates/{appId}/stable`（check）、`POST /v1/updates/{appId}/stable/artifacts/{sha256}.apk`（download）、`POST /v1/updates/{appId}/authorize`（install）。每次实时核验购买授权、到期时间和设备绑定/撤销状态；下载后撤销也不能继续申请安装。旧票据离线过期不妨碍有效购买用户联网核验。官网不再提供匿名更新或静态APK旁路，固件下载保持原逻辑。

本次没有新增或修改 Flyway迁移，不更换密钥，不改授权码、购买资格、设备、额度、备注或历史记录。升级实际已部署的服务前，先备份现有数据库、Keycloak、密钥、环境文件、更新目录和当前JAR/镜像，并验证备份可恢复；在隔离的数据库克隆与原签名配置副本中核对既有记录快照、旧90天票据续期为30天以及撤销/删除路径。清表型集成测试只能用单独的空测试库，不能指向生产库或用来验收真实数据保留的克隆。

沿用现有 Compose project 名、服务名、挂载、数据库卷、数据库连接和原有签名文件，仅按既有流程替换 API程序；不要执行 `docker compose down -v`、重建/清空数据库、修改卷名或生成替换密钥。支持新更新协议的首版客户端需受控分发，不能保留最新APK的匿名旧接口；部署后检查授权记录总数及字段、原用户续期和受保护更新。本次仅完成本地修改与验证，未连接、修改或部署线上环境。
