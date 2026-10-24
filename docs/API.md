# Autunn Play 接口清单

本清单按当前源码核对，共 **41 个服务端 API**：16 个业务及健康检查接口、22 个管理接口、3 个管理员登录接口。另列官网数据资源、外部服务、本地 CarPlay 协议和仪表调节工具，分别保留各自的鉴权与传输边界。核对日期：2026-10-15；这是源码契约核对，未向线上服务发送请求。

## 地址与通用约定

| 地址模板 | 配置来源与用途 |
| --- | --- |
| `{API_BASE}` | Android 构建配置 `AUTUNN_LICENSE_SERVER_URL`；网页使用同源相对路径。正式 Android 构建要求 HTTPS，仅调试构建可明确允许本机 HTTP。 |
| `{OIDC_ISSUER}` | 服务端 `OIDC_ISSUER`；供 JWT issuer 验证及浏览器登录确认使用。 |
| `{OIDC_PROTOCOL_BASE}` | 从 `OIDC_JWKS` 的 `/protocol/openid-connect/certs` 目录解析；用于服务端与 Keycloak 交换表单。可由部署配置指定内部服务地址。 |
| `{MFI_BASE}` | 用户配置的远程 MFI 服务地址；客户端接受 HTTP 或 HTTPS，可另配 Bearer 令牌。 |
| `{AIRPLAY_PEER}` / `{AIRPLAY_RECEIVER}` | Bonjour/iAP2 发现或协商得到的本地设备地址、实际监听端口。 |
| `{TUNER_BASE}` | 本机仪表调节面板 `http://127.0.0.1:{port}`，默认端口 8765。 |

以下业务、管理、登录表中的 URL 均以 `{API_BASE}` 为前缀。除 APK 上传外，POST/PUT 请求使用 UTF-8 JSON；成功响应默认是 JSON，时间字段是 Unix 秒，大小字段是字节。路径 ID 通常为 UUID，`appId` 必须在服务端允许列表内。

鉴权类型：

- **公开**：不要求账户 JWT，仍受请求频率及字段验证约束。
- **设备证明**：一次性 `challengeId` + 设备 P-256 私钥生成的 `signature`，服务端验证用途、应用及设备绑定。公开路由不等于免验证。
- **能力令牌**：扫码流程中的 `browserToken` 或 `pollToken`，只授权对应会话。
- **账户 JWT**：`Authorization: Bearer {accessToken}`，验证签名、issuer、时效及配置的 API audience。
- **管理员 JWT**：账户 JWT 还须包含 `realm_access.roles` 中的 `license-admin` 角色。

## 业务 HTTP API（16 个）

| 方法 | URL | 主要传参 | 功能与主要响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| GET | `/health` | 无 | 进程健康探针；`{status: "ok"}`。不检查数据库或外部登录服务。 | 公开 |
| POST | `/v1/challenges` | `purpose`、`appId`、`publicKeySpki`；可选 `offlineDays` | 获取一次性挑战；返回 `challengeId`、`nonce`、`serverTime`、`expiresIn`。 | 公开 |
| POST | `/v1/licenses/enroll` | `challengeId`、`signature`、`deviceName`；可选 `activationCode` | 账户授权绑定设备；返回 `license`、`serverTime`。 | 账户 JWT + `enroll` 设备证明 |
| POST | `/v1/licenses/renew` | `challengeId`、`signature`、`license` | 根据已有签名授权票据续期；返回新 `license`、`serverTime`。旧票据可以已超过离线期限，购买授权和设备绑定仍须有效。 | `renew` 设备证明 + 原授权票据 |
| GET | `/v1/licenses/me` | 无 | 获取当前账户的授权、设备列表、累计激活数和永久授权标识。 | 账户 JWT |
| POST | `/v1/licenses/me/devices/{deviceId}/unbind` | 路径 `deviceId`，无请求体 | 撤销当前账户下指定设备；返回 `nextAllowedAt`。 | 账户 JWT |
| POST | `/v1/activation/sessions` | `challengeId`、`signature`、`deviceName` | 发起车机扫码激活；返回 `sessionId`、`pollToken`、`activationPath`、`deviceFingerprint`、`expiresIn`、`interval`。 | `pair` 设备证明 |
| POST | `/v1/activation/details` | `browserToken` | 手机读取扫码会话；返回 `deviceName`、`deviceFingerprint`、`appId`、`expiresAt`、`status`（`pending` / `activated`）。 | 手机能力令牌 |
| POST | `/v1/activation/approve` | `browserToken`、`activationCode` | 手机确认授权码并绑定车机；返回 `{status: "activated"}`。同一已批准会话重试不会兑换另一授权码。 | 手机能力令牌 + 有效设备授权码 |
| POST | `/v1/activation/status` | `sessionId`、`pollToken` | 车机轮询授权结果；返回 `status`（`pending` / `ready`）。 | 车机轮询能力令牌 |
| POST | `/v1/activation/claim` | `sessionId`、`challengeId`、`signature` | 手机批准后由车机领取授权票据；返回 `license`、`serverTime`。 | `activate` 设备证明，且设备/应用与会话一致 |
| POST | `/v1/activation/cancel` | `sessionId`、`challengeId`、`signature` | 停止未批准的激活会话；返回 `status`（`cancelled` / `activated`），已批准的授权不会被此操作撤销。 | `cancel` 设备证明，且设备/应用与会话一致 |
| POST | `/v1/diagnostics` | `challengeId`、`signature`、`metadata`、`report` | 上传设备诊断报告，服务端脱敏后存储；返回 `reportId`、`deviceFingerprint`、`receivedAt`、`sizeBytes`、`truncated`。 | `diagnostic` 设备证明，覆盖元数据和原报告 |
| POST | `/v1/updates/{appId}/stable` | `challengeId`、`signature`、`license`、`action: "check"`、`sha256: ""` | 获取稳定版签名清单；返回 `{manifest, signature}`。未发布为 `404 UPDATE_NOT_FOUND`；响应 `Cache-Control: no-store`。 | `update` 设备证明 + 实时有效购买授权及设备绑定；Android 必须验更新签名 |
| POST | `/v1/updates/{appId}/stable/artifacts/{sha256}.apk` | `challengeId`、`signature`、`license`、`action: "download"`、与路径一致的 `sha256` | 下载实际存在且哈希正确的完整 APK；类型 `application/vnd.android.package-archive`，准确 `Content-Length`，`Cache-Control: private, no-store`。 | `update` 设备证明 + 实时有效购买授权及设备绑定；客户端验证大小、哈希和 APK 签名 |
| POST | `/v1/updates/{appId}/authorize` | `challengeId`、`signature`、`license`、`action: "install"`、`sha256` | 提交安装前重新核验授权和实际 APK；返回 `{status: "authorized", challengeId, action: "install", appId, sha256, serverTime}`。 | 新的 `update` 设备证明 + 实时有效购买授权及设备绑定 |

### 设备证明与激活约束

`purpose` 只允许 `enroll`、`renew`、`pair`、`activate`、`cancel`、`diagnostic`、`update`。`publicKeySpki` 为无填充 base64url 编码的规范 P-256 SPKI 公钥，服务端解码后最多 200 字节。`signature` 为无填充 base64url 编码的 DER ECDSA 签名，使用 `SHA256withECDSA`；挑战仅可消费一次，默认有效 120 秒，配置范围 30–300 秒。

设备签名原文如下，使用 UTF-8、LF 分隔，无末尾换行；`nonce` 必须完整原样签入：

```text
FAW-LICENSE-V1
{purpose}
{nonce}
{appId}
{sha256(publicKeySpkiDecodedBytes)}
{sha256(payloadUtf8Bytes)}
```

各用途的 `payload`：`enroll` 为去除两端空白的授权码（未传为 `""`）；`renew` 为旧 `license` 字符串；`pair` 为本次 `deviceName`；`activate` / `cancel` 为 `sessionId`；`diagnostic` 为下文的诊断元数据签名原文；`update` 为下文的更新操作签名原文。

`offlineDays` 范围 1–90，省略时按旧客户端的 7 天请求；新签和续签离线期限为 `min(请求天数, 配置天数, 30)`，再受购买授权到期时间限制；配置仍为 90 或使用升级前已发出的 90 天挑战也不能签出超过 30 天。默认配置为 30。旧 90 天 JWT仍可验签及联网续期；新版客户端从更新后首次读取缓存起按 `min(exp, iat+30天)` 执行，不从安装时间重新起算。永久购买授权仍须定期续取离线票据。返回票据的 `challenge_id` 应与本次挑战一致，不能只从收到的票据反推预期值。

扫码会话有效 600 秒，返回轮询间隔为 5 秒。`activationPath` 使用 `/activate/#token={browserToken}`，手机令牌放在 URL fragment 中，随后仅通过 POST JSON 发送。`browserToken` 和 `pollToken` 是不同凭据，不得互换。每个未批准会话最多记录 10 次授权码尝试；超过限制返回 `429 ACTIVATION_ATTEMPTS`。

字段长度上限：`challengeId` / `sessionId` 36；设备签名 120；设备名称 100；授权码 200；能力令牌 100；续期授权票据 8192。账户自助解绑默认每 30 天一次，冷却中返回 `429 UNBIND_COOLDOWN`；解绑/撤销不会返还累计激活次数。

### 诊断字段与约束

`metadata` 字段为 `versionName`（非空，≤80）、`versionCode`（1–2147483647）、`manufacturer`（≤80）、`model`（≤100）、`firmware`（≤200）、`androidRelease`（≤40）、`sdk`（1–100）、`truncated`（布尔）。这些文本字段禁止控制字符；允许为空的文本仍须存在。

诊断 `payload` 使用 UTF-8、LF 分隔，无末尾换行：

```text
FAW-DIAGNOSTIC-V1
{versionName}
{versionCode}
{manufacturer}
{model}
{firmware}
{androidRelease}
{sdk}
{truncatedLowercaseBoolean}
{sha256(reportUtf8Bytes)}
```

原报告 UTF-8 上限 1 MiB，整个 JSON 请求上限 2 MiB + 16 KiB，同时最多处理两个上传。服务端进一步删除凭据、原始协议载荷等敏感行，替换设备标识和网络地址；脱敏后空白的报告会被拒绝。每台设备每小时最多 3 份，服务端全局每分钟 5 份、每小时 60 份；报告保留 30 天，总量最多 1000 份或 256 MiB，容量不足时淘汰最旧报告。

### 更新请求与响应结构

三个更新接口的新版客户端请求均携带固定标识 `Autunn: 1`。管理员可在后台“应用更新”页即时开关请求头验证，对所有应用更新统一生效；默认关闭。开启时缺失、错误或重复值返回 `403 UPDATE_HEADER_INVALID`，请求头名称遵循 HTTP 的大小写不敏感规则。关闭只跳过请求头验证，设备证明与实时授权核验始终执行。标识校验失败不消耗挑战。设置文件不可读或损坏时返回 `503 UPDATE_SETTINGS_UNAVAILABLE`，不会静默关闭验证。

三个更新接口均为 POST，拒绝旧匿名 GET；每次操作必须申请独立的 `purpose: "update"` 挑战。请求体固定字段为 `challengeId`、`signature`、`license`、`action`、`sha256`，不得把凭据放入 URL。`license` 是原激活所得票据，不是管理员账户 Bearer token；无需保存原始授权码或重复兑换。

`update` 的 `payload` 是以下四行 UTF-8 文本，使用 LF、无末尾换行；第四行是原始 license 字符串的 UTF-8 SHA-256 小写十六进制。然后仍按上文 `FAW-LICENSE-V1` 设备证明格式对整个 payload 再取摘要并签名：

```text
{action}
{appId}
{sha256}
{sha256(licenseUtf8Bytes)}
```

`action` 只能是 `check` / `download` / `install`。检查时第三行必须为空；下载和安装时必须为实际 APK 的 64 位小写摘要。动作、路径包名、APK 摘要和原始票据都绑定到证明，禁止复用检查证明下载或安装。测试向量：`license="fixture-license.授权"` 的 UTF-8 摘要为 `a23e7356f473a5a228c1d3c6e771c17a8a401fd270c36ac6a223bdca330ec030`。

服务端接受已超过离线有效期的真实旧票据作为在线身份凭据，但每次查询数据库验证购买授权未删除、未到期、license ID与设备绑定一致且设备未撤销；伪造票据和跨设备/跨应用证明仍拒绝。下载和安装授权还校验文件实际存在及完整哈希。安装授权响应绑定本次 challengeId、appId 和 sha256；客户端必须核对。它只授权本次在线安装操作，不会延长离线期，也不代替客户端安装前的实际 APK校验和原有车辆状态门禁。


`manifest` 是无填充 base64url 编码的 UTF-8 JSON，字段如下：

| 字段 | 约束或含义 |
| --- | --- |
| `schema` | 固定为 `1`。 |
| `packageName` | 与请求的 `appId` 及客户端包名一致。 |
| `versionCode` / `versionName` | 正整数版本码 / 非空版本名（≤128）。 |
| `minSdk` | Android API 级别，≥28。 |
| `sizeBytes` | 1–536870912（512 MiB）。 |
| `sha256` / `signingCertSha256` | APK 文件摘要 / APK 签名证书摘要，均为小写 64 位十六进制。 |
| `downloadPath` | 必须严格为 `/v1/updates/{packageName}/stable/artifacts/{sha256}.apk`。 |
| `releaseNotes` | 更新说明，≤4000 字符。 |

`signature` 为 base64url 编码的 DER ECDSA 签名，签名原文是 UTF-8 的 `FAW-UPDATE-V1\n{manifest}`。Android 使用构建时配置的 P-256 公钥验证，再核对预期 APK 签名证书。更新元数据信封上限 64 KiB；下载禁止跳转和非 identity 内容编码。官网不再匿名读取更新清单或提供 APK下载；提示在车机内激活授权码后更新，首次支持此协议的客户端经受控渠道分发。固件目录与下载逻辑保持独立。

## 管理 HTTP API（22 个）

本表全部要求管理员 JWT。`offset` 默认 0、必须非负，`limit` 默认 50、范围 1–100；诊断查询另限制 `offset ≤100000`。

| 方法 | URL | 主要传参 | 功能与主要响应 |
| --- | --- | --- | --- |
| GET | `/v1/admin/me` | 无 | 当前管理员信息；`username`、`serverTime`。 |
| POST | `/v1/admin/activation-codes` | `deviceLimit`、`permanent`；条件字段 `validUntil`；可选 `activationLimit`、`note` | 创建一个设备授权码；返回 `activationCode` 及授权额度、期限、备注。 |
| POST | `/v1/admin/activation-codes/batch` | `count`、`grant`（同单个创建参数） | 批量创建 1–1000 个授权码；返回 `{count, codes}`。 |
| POST | `/v1/admin/activation-codes/search` | `activationCode` | 查询授权码状态及关联授权；返回 `codeHash`、`status`、额度、时间、备注、`redemptionMode`、`license`、`canDelete`。明文授权码仅放请求体。 |
| DELETE | `/v1/admin/activation-codes/{codeHash}` | 路径 `codeHash`（小写 64 位十六进制） | 软删除授权码，专属设备码已兑换时同时撤销对应授权；返回更新后的授权码视图。共享账户码需独立管理其授权。 |
| GET | `/v1/admin/licenses` | 查询 `offset`、`limit` | 分页查询未删除授权，返回授权对象数组。 |
| GET | `/v1/admin/license-summaries` | 查询 `offset`、`limit` | 分页查询授权摘要，返回授权对象、累计激活数、活跃设备数、永久标识。 |
| GET | `/v1/admin/license-count` | 无 | 未删除授权总数；返回 `{count}`。 |
| POST | `/v1/admin/licenses/{id}/reset-activations` | 路径 `id`，无请求体 | 重置累计激活计数时间点，保留既有设备与绑定。 |
| GET | `/v1/admin/licenses/{id}` | 路径 `id` | 授权详情；返回 `authorization`、`devices`、`activationsUsed`、`permanent`。 |
| PUT | `/v1/admin/licenses/{id}` | 路径 `id`；`deviceLimit`、`permanent`、`validUntil`；可选 `activationLimit`、`note` | 修改授权额度、期限和备注；返回授权详情。设备额度不得低于活跃设备数，累计激活额度不得低于已用次数。 |
| DELETE | `/v1/admin/licenses/{id}` | 路径 `id` | 软删除授权；`{status: "deleted"}`。关联票据后续续期/领取将被拒绝。 |
| PUT | `/v1/admin/licenses/{id}/devices/{deviceId}/note` | 路径 `id`、`deviceId`；`note` | 更新设备备注；返回修剪后的 `note`。设备必须属于该授权。 |
| POST | `/v1/admin/licenses/{id}/devices/{deviceId}/revoke` | 路径 `id`、`deviceId`，无请求体 | 撤销指定活跃设备；`{status: "revoked"}`。 |
| POST | `/v1/admin/licenses/{id}/devices/{deviceId}/restore` | 路径 `id`、`deviceId`，无请求体 | 恢复已撤销设备，须有活跃设备额度；`{status: "active"}`。 |
| GET | `/v1/admin/audit` | 查询 `offset`、`limit` | 按时间倒序查看审计记录；数组项含 `actor`、`action`、`target`、`occurred_at`。 |
| GET | `/v1/admin/diagnostics` | 查询 `device`（设备公钥摘要前缀）、`version`（版本名或版本码）、`offset`、`limit` | 查询保留期内报告摘要；含设备指纹、设备备注、版本/系统元数据、接收时间、大小、截断状态。 |
| GET | `/v1/admin/diagnostics/{id}` | 路径 `id` | 获取报告；`{summary, report, reportSha256}`。已过期或不存在时 `404 DIAGNOSTIC_NOT_FOUND`。 |
| GET | `/v1/admin/updates` | 无 | 查询允许应用的发布状态；数组项含 `packageName`、`uploadEnabled`、`signingCertSha256`、`current`。 |
| GET | `/v1/admin/updates/settings` | 无 | 读取更新请求头开关；返回 `{headerValidationEnabled: boolean, headerName: "Autunn"}`，`Cache-Control: no-store`。 |
| PUT | `/v1/admin/updates/settings` | 仅 `headerValidationEnabled`（必填布尔值） | 保存并立即启用开关，返回相同设置结构。设置原子保存到更新目录，重启保留；失败返回 `503 UPDATE_SETTINGS_UNAVAILABLE`，客户端应重新读取确认结果。 |
| POST | `/v1/admin/updates/{appId}/stable` | 路径 `appId`；`multipart/form-data` 文件部分 `apk`；可选 `releaseNotes` | 发布完整 APK，返回未编码的更新清单对象并记录审计。 |

授权参数：`deviceLimit` 为 1–100，`activationLimit` 为 1–100000，`note` 长度 ≤500。创建时不传累计额度，默认等于设备额度；更新时不传则保留现值。`permanent: true` 时必须省略或置空 `validUntil`；非永久授权必须提供正整数到期时间，创建时要求晚于当前服务端时间。创建接口生成 `redemptionMode: device` 的授权码；旧账户兑换接口只接受现有 `account` 模式码。新码明文仅在创建响应返回，服务端保存其摘要，查询不会恢复明文。

授权对象字段：`id`、`subject`、`deviceLimit`、`validUntil`、`activationLimit`、`note`。设备对象字段：`id`、`licenseId`、`keyHash`、`publicKey`、`name`、`enrolledAt`、`revokedAt`、`note`。授权码状态可能为 `unused`、`active`、`expired`、`deleted`、`invalidated`。

发布前须配置预期 APK 签名证书。上传最大 512 MiB，更新说明 ≤4000 字符；服务端检查实际包名、APK 签名证书、版本码与已有发布。禁止版本降级；同版本码只能对应同一 APK 内容；并行发布冲突为 `409 UPDATE_BUSY`。典型拒绝包括 `UPDATE_NOT_CONFIGURED`、`INVALID_APK`、`UPDATE_PACKAGE_MISMATCH`、`UPDATE_SIGNER_MISMATCH`、`UPDATE_DOWNGRADE`、`UPDATE_VERSION_CONFLICT`、`UPDATE_TOO_LARGE`。

## 管理员登录 API（3 个）

使用 Keycloak 设备授权流程，管理员密码只在 Keycloak 页面输入。以下路由不要求已有 JWT；所获账户令牌还须通过管理员角色验证后才能调用管理 API。

| 方法 | URL | 主要传参 | 功能与主要响应 |
| --- | --- | --- | --- |
| POST | `/v1/admin-login/start` | 无请求体 | 请求登录会话；返回 `deviceCode`、`userCode`、`verificationUri`、`expiresIn`、`interval`（至少 5 秒）。 |
| POST | `/v1/admin-login/poll` | `deviceCode`（≤4096） | 查询浏览器确认结果；`{status: "pending"}` / `{status: "slow_down"}`，或 `{status: "ready", accessToken, refreshToken, expiresIn}`。 |
| POST | `/v1/admin-login/refresh` | `refreshToken`（≤16384） | 刷新账户令牌；返回 `status: "ready"` 及新令牌、有效期。 |

登录错误：`401 LOGIN_EXPIRED`（过期或无效授权）、`403 LOGIN_DENIED`（拒绝确认）、`502 LOGIN_UNAVAILABLE`（上游不可用或响应无效）。令牌必须留在请求体或 Authorization 头中，不应出现在 URL、日志、文档示例中。

## 页面、官网数据及静态文件

| 方法 | URL | 主要传参/响应 | 功能与鉴权 |
| --- | --- | --- | --- |
| GET | `/`、`/index.html` | HTML | 公开官网页面。 |
| GET | `/activate/`、`/activate/index.html` | HTML；手机令牌来自浏览器 fragment | 公开扫码激活页面，业务调用仍验证 `browserToken`。 |
| GET | `/admin/`、`/admin/index.html` | HTML | 公开登录入口；管理数据仍需管理员 JWT。 |
| GET | `/downloads.json` | `{schema: 1, app, firmware}` | 公开静态目录。`app` / `firmware` 可为 `null`；网页始终忽略 app.downloadUrl，仅为已经准备的固件开放下载。 |
| GET | `/downloads/{fileName}.apk` | 历史 APK 路径 | 本轮关闭此公开旁路；当前站点不生成此文件。部署方须停用既有静态副本并清理代理历史缓存，软件更新改由设备在线验权接口提供。 |
| GET | `/downloads/{fileName}.zip` | ZIP 二进制 | 外部静态交付目录中的完整固件文件，提供方式同上。 |
| GET | `/assets/{fileName}`、`/activate/{fileName}`、`/admin/{fileName}` | 允许列表中的 JS/CSS/PNG | 页面资源仅对 `SecurityConfiguration` 中明确列出的文件开放；模板不表示整个目录公开。 |

目录 `app` 字段含 `packageName`、`versionCode`、`versionName`、`minSdk`、`sizeBytes`、`sha256`、`downloadUrl`、`fileName`、`releaseNotes`；`firmware` 使用 `model` 替代包名/Android 版本字段。可下载 URL 必须是同源 `downloads/` 下单一合法文件名，且扩展名与类型一致。有效的线上稳定版清单优先于目录中的本地备用 App。实际固件元数据是交付文件的型号、版本、大小和摘要，应从真实文件维护。

旧 `site/content.json` 为语言内容静态源数据，当前官网不请求它，不是业务 API。官网资源的构建与部署边界见 [WEBSITE.md](WEBSITE.md)。

## 外部服务

### Keycloak/OIDC

这些接口由外部身份服务提供，授权服务只做代理或读取验证材料，不定义其服务端实现。

| 方法 | URL 模板 | 主要传参 | 功能/响应 |
| --- | --- | --- | --- |
| POST | `{OIDC_PROTOCOL_BASE}/auth/device` | URL 编码表单 `client_id=faw-license-admin`、`scope=openid profile` | 获取 `device_code`、`user_code`、`expires_in`、`interval`。由登录 start 代理调用。 |
| POST | `{OIDC_PROTOCOL_BASE}/token` | 表单 `client_id`、`grant_type=urn:ietf:params:oauth:grant-type:device_code`、`device_code` | 轮询设备授权；返回 token 或 `authorization_pending` / `slow_down` 等 OAuth 错误。 |
| POST | `{OIDC_PROTOCOL_BASE}/token` | 表单 `client_id`、`grant_type=refresh_token`、`refresh_token` | 刷新账户 token。与上行是同一 HTTP 接口的另一授权类型。 |
| GET | `{OIDC_PROTOCOL_BASE}/certs` | 无 | Spring JWT decoder 从配置的 JWKS 地址读取验签公钥。 |
| GET | `{OIDC_ISSUER}/device?user_code={userCode}` | 查询 `user_code` | 浏览器打开管理员登录确认页；后续认证行为由 Keycloak 自身定义。 |

上游登录请求禁止跟随跳转，连接超时 5 秒、单请求超时 15 秒，响应最多 64 KiB。浏览器确认链接从受信 issuer 构造，不使用上游返回的任意 URL。

### 远程 MFI HTTP（3 个）

三项均发送 `Accept: application/json`，有请求体时使用 `Content-Type: application/json; charset=utf-8`；配置令牌时另发送 `Authorization: Bearer {token}`。客户端串行执行证书/签名操作，证书读取后缓存，reset 会清除缓存。

| 方法 | URL | 主要传参 | 功能与主要响应 |
| --- | --- | --- | --- |
| GET | `{MFI_BASE}/mfi/certificate` | 无 | 读取认证证书；`protocolMajor`（0–255）、`type`（`mfi` / `baa`，省略按 `mfi`）、标准 Base64 的 `certificate`、64 位十六进制 `certificateSha256`。 |
| POST | `{MFI_BASE}/mfi/sign` | 标准 Base64 的 `challenge`、UUID `requestId` | 由远程认证设备签名；返回标准 Base64 的 `signature`。同一逻辑请求重试复用 `requestId`。 |
| POST | `{MFI_BASE}/mfi/reset` | `{}` | 请求重置远程认证设备；任意 2xx 为成功，客户端不要求特定响应字段。 |

默认连接/读取超时分别为 5/10 秒；证书与签名请求最多尝试 2 次，仅在网络 I/O 错误、HTTP 408/429/5xx 时重试；reset 不重试，不跟随 HTTP 跳转。challenge 解码后 1–128 字节，证书和签名非空且最多 65525 字节，HTTP 响应最多 2 MiB。错误细节来自响应 `detail`，缺省以 HTTP 状态表示。

`baa` 证书包由两个大端 32 位长度字段和对应 leaf/intermediate 字节组成，客户端验证长度和证书包 SHA-256 后转换为 iAP2 认证载荷。这些编码及缓存规则属于 MFI 认证模块，独立于授权业务 API。

## 本地 CarPlay/AirPlay 协议边界

这部分是设备间 HTTP/RTSP 风格控制消息及动态端口媒体传输，不是云端 REST API。控制监听首选 7000，冲突时可用 7001–7010 或系统分配端口，实际值经 Bonjour/iAP2 发布；不应把固定端口写入业务客户端。

| 方法/方向 | 本地路径或会话目标 | 主要传参/载荷 | 功能与响应 |
| --- | --- | --- | --- |
| GET，接收端→手机 | `{AIRPLAY_PEER}/ctrl-int/1/connect`，HTTP/1.1 | `Host`、`User-Agent: AirPlay/{sourceVersion}`、`AirPlay-Receiver-Device-ID` | Bonjour 发现后的连接探测，促进手机发起接收端会话。 |
| POST，手机→接收端 | `/pair-setup` | 配对状态/公钥等二进制 TLV8 | 执行配对协商；返回配对二进制载荷。 |
| POST，手机→接收端 | `/pair-verify` | 配对验证二进制 TLV8 | 验证会话并派生控制通道密钥；返回配对二进制载荷。 |
| POST，手机→接收端 | `/auth-setup` | MFI SAP 二进制认证载荷 | 执行 MFI 认证；有效时返回八位字节流，缺少认证器或载荷无效时 400。 |
| GET（可带协议载荷），手机→接收端 | `/info` | 可选 binary plist，描述请求的显示区/能力 | 返回 binary plist 的设备、显示和音频能力信息。 |
| SETUP，手机→接收端 | 会话 URI，由手机提供 | binary plist：初始设备/`timingPort`/`features`，或 `streams[]` | 协商事件、计时、保活端口或媒体流端口；返回 binary plist；无会话使用授权为 403，无效 SETUP 为 400。 |
| RECORD，手机→接收端 | 会话 URI，由手机提供 | 会话上下文 | 激活会话；成功 200，无会话使用授权为 403。 |
| TEARDOWN，手机→接收端 | 会话 URI，由手机提供 | 可选 binary plist `streams[]` | 释放指定流；未指定有效流类型时关闭全部媒体流；返回 200。 |
| POST，双向控制/事件通道 | `/command` | binary plist `type`、`params`；远程控制可含 `X-Apple-StreamID` 与 `params.data` | 交换 UI、显示区、电话/Siri、HID 或流远程控制消息；接收端成功 200，非法显示区命令可返回 400。事件通道按协商密钥加密，使用 CSeq 关联响应。 |
| POST，手机→接收端 | `/feedback` | 会话上下文 | 保活/媒体反馈；200，可含 binary plist 媒体反馈数据。 |

`/pair-setup`、`/pair-verify`、`/auth-setup`、`/info` 的上述方法为协议使用方式；当前分发器按路径后缀匹配，不单独限制其方法。`SETUP` / `RECORD` 在分配媒体资源或激活前执行 App 授权检查。设备配对认证与产品功能授权是不同检查。

媒体面另包含动态 TCP 屏幕/数据/事件/保活通道、UDP 音频/音频控制/麦克风上行和 NTP 计时消息；其端口从 SETUP 或相关流响应取得，无固定云端 URL。USB iAP2、NCM、Lockdown、蓝牙与本地 ADB 使用各自协议和系统权限，不能通过 HTTP API 层替代。

## 本机仪表调节工具（2 个 API，另有入口页面）

工具源于 `scripts/cluster-tuner/`，通过 ADB 广播与配套调节 APK 交互。仅监听 127.0.0.1，校验精确 Host；若有 Origin 必须与本机工具地址一致。POST 还需 `X-Autunn-Session` 会话头，值从本次页面获取。

| 方法 | URL | 主要传参 | 功能与响应 |
| --- | --- | --- | --- |
| GET | `{TUNER_BASE}/` | 无 | 返回含当前会话凭据的本机调节页面。 |
| GET | `{TUNER_BASE}/api/devices` | 无 | 查询 ADB 设备；`{devices: [{serial, state, name}]}`。 |
| POST | `{TUNER_BASE}/api/layout` | `serial`、`op`（`get` / `set` / `reset` / `original` / `content`）；按操作提供 `target`、`x`、`y`、`width`、`height`、`scale` 或 `content` | 读取/修改仪表布局或内容，返回配套 APK 的协议 2 布局响应。 |

POST JSON 最大 4096 字节。`target` 为 `tbt` / `ete`；位置 `x=0–1919`、`y=0–719`，大小 `width=80–1920`、`height=40–720`，比例 `scale=50–150`。`content` 为 `MAP_WITH_CUSTOM_CARD` / `INSTRUMENTS` / `TURN_CARD`。设备必须在线且已授权；参数/ADB 错误为 400 `{error}`，来源或会话错误为 403 `{error}`，未知路径为 404 `{error}`。

## 服务端错误与频率限制

业务错误统一为 `{error: "错误码", message: "简述"}`。客户端按 `error` 决定行为，避免直接向用户展示上游 HTML 或内部服务信息。

| HTTP 状态 | 常见错误码 | 含义 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`、`INVALID_APK` | 字段、编码、分页或载荷无效。 |
| 401 | `UNAUTHORIZED`、`INVALID_PROOF`、`INVALID_LICENSE`、`LOGIN_EXPIRED` | 账户或设备认证失败。 |
| 403 | `FORBIDDEN`、`NO_LICENSE`、`LICENSE_EXPIRED`、`LICENSE_REVOKED`、`DEVICE_REVOKED`、`DEVICE_LIMIT`、`ACTIVATION_LIMIT`、`INVALID_ACTIVATION_CODE`、`LOGIN_DENIED` | 角色、购买授权、设备状态或额度不满足。 |
| 404 | `NOT_FOUND`、`CODE_NOT_FOUND`、`DIAGNOSTIC_NOT_FOUND`、`UPDATE_NOT_FOUND` | 资源不存在或不可提供；更新 404 在客户端表示没有发布版本。 |
| 409 | `CHALLENGE_INVALID`、`ACTIVATION_PENDING`、`ACCOUNT_ALREADY_LICENSED`、`CODE_SHARED_LICENSE`、更新发布冲突码 | 挑战已使用/过期、会话未批准或当前业务状态冲突。 |
| 410 | `ACTIVATION_EXPIRED`、`ACTIVATION_CANCELLED` | 扫码会话过期或被取消，应重新生成。 |
| 413 | `DIAGNOSTIC_TOO_LARGE`、`UPDATE_TOO_LARGE` | 报告或上传 APK 超限。 |
| 429 | `RATE_LIMITED`、`ACTIVATION_ATTEMPTS`、`DIAGNOSTIC_RATE_LIMITED`、`UNBIND_COOLDOWN` | 请求、扫码尝试、诊断上传或自助解绑受限。 |
| 502 / 503 | `LOGIN_UNAVAILABLE`、`UPDATE_UNAVAILABLE`、`UPDATE_STORAGE` | 上游登录服务或更新数据/存储不可用。 |

所有 `/v1/` 路径使用每进程分钟窗口：每个直接连接来源最多 120 次请求，其中 challenge 最多 20 次；全局最多 5000 次请求，其中 challenge 最多 1000 次。仅信任 socket 来源，不信任 Forwarded/X-Forwarded-For；多用户共用反向代理时会共享该来源额度。统一限流响应为 429，带 `Retry-After: 60`；额外业务限制按前文处理。

## 源码对应关系

- 路由与 DTO：`authorization-server/src/main/java/com/shilapi/license/` 下的 `LicenseController`、`ActivationController`、`Administration`、`DiagnosticController`、`UpdateController`、`UpdateAdministration`、`AdminLogin`。
- 鉴权、错误与限制：同目录的 `SecurityConfiguration`、`ApiErrors`、`RequestLimits`、`DiagnosticBodyLimit`，业务服务及 `Crypto`。
- Android HTTP 公共入口：`shared/src/main/java/com/shilapi/xcertplay/api/ApiUrls.kt` 负责业务服务地址验证及同源 URL 构造；同目录 `HttpTransport.kt` 负责连接/读取超时、禁止跳转、响应大小限制、请求编码和连接释放。
- Android 业务边界：`shared/.../licensing/LicenseApi.kt` 保留设备证明和业务错误映射；`shared/.../updates/UpdateTransport.kt` 保留 APK 流式下载与完整性验证，`shared/.../updates/UpdateManifest.kt` 保留签名清单验证。
- MFI 边界：`shared/.../mfi/RemoteMfiAuthenticationClient.kt`。
- 本地协议边界：`shared/.../airplay/AirPlaySession.kt`、`shared/.../network/CarPlayBonjour.kt`、`AirPlayPortSelector.kt` 及流实现。
- 网页数据消费：`site/assets/site.js`、`site/downloads.json`；本机工具：`scripts/cluster-tuner/tuner.py`。

进一步的授权、更新和官网说明见 [PRODUCT_AUTHORIZATION.md](PRODUCT_AUTHORIZATION.md)、[REMOTE_UPDATES.md](REMOTE_UPDATES.md)、[WEBSITE.md](WEBSITE.md)。
