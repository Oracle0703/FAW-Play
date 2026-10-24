# 应用内远程更新

新版客户端的检查、下载和安装前核验均携带 `Autunn: 1`。管理员登录后台后，在“应用更新”页点击“验证 Autunn 请求头”即可切换，保存后立即对后续请求生效，无需重启；默认关闭。关闭时依旧验证设备证明和购买授权。保存结果不确定时页面会重读服务端状态，无法确认则保持待确认并禁用。

设置单独保存在现有 `UPDATE_DIRECTORY/.update-request-settings.json`，沿用原更新目录挂载；未配置目录时为 `./updates/.update-request-settings.json`。请连同更新目录保留和备份，不改数据库。旧版客户端需升级后再开启开关；关闭开关不会恢复匿名 GET 更新接口。设置文件损坏或不可读时返回 503，管理员应恢复文件或重新保存；授权及激活接口不受这个开关影响。

AUTH2 起，在「关于 → 检查更新」查看新版本、下载校验，并请求系统安装。打开应用时最多每 6 小时检查一次更新信息；不会自动下载或自动安装。CarPlay 已连接、正在连接或正在断开时拒绝安装；请停车并断开后操作。

新版源码要求检查、下载及提交安装分别在线核验设备授权。已有授权的车机自动使用本机设备密钥和已签名票据完成验证，无需再次输入授权码；没有授权时沿用授权页的手机扫码输入授权码流程，激活后返回更新页继续操作。更新验证不保存原始授权码，也不以离线授权替代服务端确认。离线授权最长为 30 天，有限购买还受购买到期时间限制；历史 7 天票据不延长，历史 90 天票据在新版中按 `min(exp, iat + 30 天)` 截止，升级不重置起算时间。离线票据过期后，仍可凭原票据和设备密钥向服务端证明设备身份，由服务端检查当前购买有效期、撤销状态及设备绑定。

2026-10-10 更新页交付包为 UI34：`0.2.21-FAW-UI34 / versionCode 95`，来自固定源码快照。UI34 改为横屏双卡片更新页，版本、状态和操作一屏显示，长说明用上一页/下一页完整阅读，主题切换保留下载和阅读状态。历史 AUTH5 已覆盖安装到连接的手机；UI34 的构建和设备验收结果以本轮交付记录为准。授权管理网页现已提供 `/admin/#updates` 的 APK 上传发布入口。启用本轮新版服务及可写映射后，可直接网页发布；也可通过下方工具准备本地更新文件。后续发布 APK 不需要重建容器。AUTH5 授权服务本身的升级仍需另行重建 license-api，不能与 APK 更新发布混为一项。

## 静默安装检测

页面检查实际 `android.permission.INSTALL_PACKAGES` 授权、`DevicePolicyManager.isDeviceOwnerApp` 以及已验证的 HS5 厂商安装服务。普通设备管理员不等于设备所有者，仅在 Manifest 声明权限也不等于获准静默安装。

2026-10-10 已确认 HS5 Android 9 的系统 `PackageInstaller.InstallStart` 另有 `isSystemApp` 校验：普通第三方 App 即使开启“允许安装应用”，仍会被拒绝进入安装界面。UPD2 接入该车机实际自带的 `ltd.qisi.sotasupportapp.SotaSupportReceiver` 应用安装入口。独立普通 UID 的诊断 App 未获 `INSTALL_PACKAGES`，仍通过此入口把测试包从 1 升至 2、从 2 升至 3；系统记录安装来源为厂商服务。另已验证有代码的测试 App 可以升级自身，并接收 `MY_PACKAGE_REPLACED` 后重新打开。这些实验验证厂商接口和系统恢复行为；FAW-Play 从 NAS 下载到实际安装的验收另行记录。

厂商适配严格限定 Android 9、`t1c_acrn` 设备、原 OEM 单证书、系统应用身份、实际启用/导出的组件及安装授权。UPD4 接受两组已核对的 SOTA Support 版本：2731 / `20240905-114725` 与 3337 / `20250210-144658`。2731 来自实车安装验证；3337 来自 3222 固件，两者完整 VDEX、原 OEM 签名与相关组件声明一致，但 3337 尚未完成实车安装验收。版本号和版本名称必须同时匹配；其他版本继续使用标准 Android 安装流程。接口会短暂发送厂商 SOTA 状态通知，但请求只带本应用 APK，资源列表为空、USB 标记为 false，不请求固件或主题升级。

UPD4 修正厂商服务证书兼容读取：只在 `SigningInfo` 缺失时读取 Android 9 的 legacy `signatures`，仍要求唯一证书与已核对原 OEM 证书一致。现代证书存在时优先校验当前签名，错误或空证书不能由 legacy 证书替代。安装接口不依赖 ADB；开启“允许安装应用”也不等于具有系统静默安装权限。UI35 是 FAW-Play 应用版本，不代表用户车机的固件或 SOTA 服务版本。

检测失败时，HS5 更新页显示具体原因；「诊断 → 导出日志」包含 `Application update installer diagnostics`，记录 SDK、非唯一设备型号、SOTA 版本、证书读取来源与原因码，可在没有 ADB 的情况下排查。常见原因码：`VERSION_UNVERIFIED`（版本未核对）、`CERTIFICATE_UNAVAILABLE` / `CERTIFICATE_MISMATCH`（证书读取/匹配失败）、`APP_DISABLED` / `RECEIVER_DISABLED` / `SERVICE_DISABLED`（服务或组件禁用）、`INSTALL_PERMISSION_DENIED`（厂商服务缺少安装权限）、`PACKAGE_NOT_FOUND`（未找到服务）、`QUERY_FAILED`（查询异常）。报告不包含证书内容或异常正文。安装前仍复查这些条件。

更新包先在原私有缓存完成原有发布清单、哈希和 APK 校验，再复制到设备保护存储的专用暂存目录并重新检查哈希。暂存目录只开放路径遍历（0711），APK 只读（0444），其他 UID 无法写入或删除；已通过另一款普通 UID 测试 App 验证。原凭据保护存储目录权限保持原状。在系统更新完成通知或后续读取安装状态时，核对实际版本并清理本次专用暂存文件；读取状态时也会检查 120 秒超时。清理后收回设备保护目录的临时遍历权限。检测到实际目标版本才报告成功；重复安装请求不覆盖正在执行的安装状态。应用不自行激活设备管理、设为设备所有者、调用 root 或修改系统分区。

UPD3 在已验证的 Android 9 车机自更新成功后自动打开主界面 `DiPlayActivity`。旧进程提交厂商安装请求前持久化目标版本和请求标记；目标包通过非导出的 `MY_PACKAGE_REPLACED` 接收器检查该标记、实际安装版本和原发布签名，再清理安装状态并打开主界面。重复通知不会再次打开。没有待完成的应用内厂商更新、安装未达到目标版本或签名异常时不启动。此前 UPD2 草稿恢复更新页；正式 UPD3 按用户要求恢复主界面。手机及较新 Android 不套用 Android 9 的后台启动行为。

未获静默权限时提供系统确认安装入口，并引导用户允许本应用安装更新包。即使请求静默安装，系统仍可能返回需要确认；可点击「继续系统确认」。UPD1 通过 Activity Result 接收安装界面的返回结果；界面取消或拒绝且版本未更新时明确显示未完成安装，保留重试，不再停留在“正在处理”。确认请求失效及确认前重新连接 CarPlay 也显示具体提示。进程重启导致确认入口丢失时重新点击安装即可重新提交；成功自更新后下次打开会核对目标版本。

## 校验与协议

- 客户端先以 `purpose=update` 请求 `POST /v1/challenges`，再用设备密钥签署本次挑战。检查更新使用 `POST /v1/updates/{packageName}/stable`，请求体包含 `challengeId,signature,license,action=check,sha256=""`；在线授权通过后返回 `{manifest,signature}`。未发布返回 `404 UPDATE_NOT_FOUND`，App 显示暂无新版本。
- 更新签名沿用 `FAW-LICENSE-V1` 的 `update` 目的，payload 为 UTF-8 的 `action\nappId\nsha256\nSHA256(license)`，无尾部换行；检查不带哈希，下载和安装绑定 64 位小写 APK SHA-256。每次操作使用新的一次性挑战，票据和签名只放在 POST 请求体中，不放在 URL。
- `manifest` 是原始 JSON UTF-8 的无 padding base64url；`signature` 是 P-256 `SHA256withECDSA` DER 签名的 base64url，签名输入为 UTF-8 `FAW-UPDATE-V1\n` 加 `manifest` 原字符串。
- 元数据字段为 `schema=1,packageName,versionCode,versionName,minSdk,sizeBytes,sha256,signingCertSha256,downloadPath,releaseNotes`。版本须高于当前；单 APK 最大 512 MiB。
- 下载使用 `POST /v1/updates/{packageName}/stable/artifacts/{sha256}.apk`，携带同样的授权字段及 `action=download`、目标 APK 哈希。客户端拒绝重定向，使用构建时固定的 HTTPS API；下载至应用私有缓存，校验大小和 SHA-256 后保存。
- 提交安装前使用 `POST /v1/updates/{packageName}/authorize`，携带 `action=install` 及目标 APK 哈希，核对返回的状态、挑战、动作、包名和哈希后才提交系统或厂商安装请求。复用已缓存的完整 APK 也必须在线验证；断网、购买授权过期或明确撤销时不能更新，原安装权限和 CarPlay 连接状态门禁仍生效。
- 下载后和提交前检查实际 APK 包名、版本、minSdk 与单个当前签名证书。哈希对应发布工具用 Android SDK 验签的原始 APK；最终安装仍由 Android PackageInstaller 验签并检查系统兼容性。不支持 split APK 集合。
- UPD1 修复已在 HS5 Android 9 上复现的 APK 签名读取兼容问题：查询未安装 APK 时同时传入 `GET_SIGNING_CERTIFICATES` 和 `GET_SIGNATURES`。该车机仅使用前者会返回空 `signingInfo`；组合查询能够读取正确的当前签名。仍要求单个当前签名与发布清单及固定证书一致，保留文件哈希、包名和版本校验。
- 服务端读取只读更新目录，拒绝符号链接、越界路径、重复 JSON 字段、错误文件大小和哈希；校验通过后由 NAS 私钥签署清单。客户端只带公钥。

当前采用完整下载，不支持断点续传。更新不会在运行的导航中安装；本地门禁和用户断开操作不能替代厂商的行车安装限制。

上述 POST 协议需要配套新版服务端；旧客户端只会匿名 GET，无法在服务端启用新门禁后自行获取首次升级。首次覆盖安装须使用管理员提供的原签名完整 APK 和设备已有可用的安装途径，或在切换服务前完成客户端升级；新版客户端也不能配合仅支持旧 GET 的服务完成更新。本次源码变更不代表已部署服务或真实授权数据已更新。

## 准备和发布下一版

NAS Compose 已挂载 `/volume1/docker/FAW-Play/license-api/updates` 到 `/run/faw-updates`（启用网页上传时必须可写），新版服务 JAR 包含管理员上传接口。无需为更新增加容器或另设域名，Lucky 的 API 代理保持可用即可。

1. 增加 mobile 的 `versionCode`（UPD4 为 103，后续新版至少 104），使用相同包名、原发布签名和 NAS 公钥构建完整 Release APK。不要更换 Android 发布签名或 NAS 授权密钥。
2. 在有 Android SDK 的构建电脑，使用实际 APK 准备**本地**发布目录。`--signing-cert-sha256` 为原发布证书的 64 位小写 SHA-256，不是 APK 文件哈希：

```sh
python3 authorization-server/scripts/publish-update.py 下一版.apk \
  --directory /你的本地目录/updates \
  --package-name com.shihab.diplay \
  --signing-cert-sha256 原发布证书SHA256 \
  --notes-file 更新说明.txt --sdk /你的AndroidSDK目录
```

脚本以 `aapt` 读取实际版本、包名和 minSdk，以 `apksigner verify` 验签；拒绝错误包名/签名、split APK、降级以及同版本不同 APK。先原子保存不可变哈希文件，最后原子替换 `manifest.json`。

3. 审核并准备发布后，先把生成的 `com.shihab.diplay/stable/artifacts/{sha256}.apk` 复制到 NAS 对应目录，再把新的 `manifest.json` 临时文件放入 `stable/` 并改名替换。所有文件须允许 API 容器 UID 10001 读取。发布 manifest 前必须已有完整 APK，避免客户端看到未完成的发布；可保留旧哈希文件。
4. 在一台已有有效设备授权、使用配套新版客户端的测试设备上，通过「关于 → 检查更新」完成在线授权并获取已签名清单，再下载、确认版本与权限检测，验收安装和更新后的授权/CarPlay。另验证无授权设备会进入原扫码激活流程，以及撤销、购买到期和断网时更新被拒绝。手动查询不受自动检查的 6 小时间隔限制；直接匿名访问更新 URL 不再返回清单或 APK。静默安装仍需车机的实际权限验收。

历史 AUTH2 在工作站 `work/app-nas-20261009/` 准备过本地校验材料，没有向 NAS 的公开更新目录发布 APK。UI34 交付包的 versionCode 为 95，不能用相同版本号触发自身更新；后续发布版本应大于 95。UI34 可以作为旧版本用户的更新包，该轮仅构建本地 APK。历史版本公开读取更新信息且不校验购买授权；当前源码已改为检查、下载和安装均须在线核验设备授权，首次升级兼容限制见上文。HUD、仪表地图和方向盘键控仍按设备授权控制。


## 网页发布

打开 `https://license.example.com/admin/#updates`，使用现有管理员登录，选择 `com.shihab.diplay`，上传原签名 Release APK，填写说明并确认发布。服务端读取实际包名、版本和 minSdk 后验签；发布成功页面显示当前版本，用户在「检查更新」获取更新。手机安装能力仍由 Android 权限决定。

首次启用需更新服务 JAR、将更新目录改为可写并配置 `UPDATE_SIGNING_CERTIFICATES`；NAS 的具体操作见本轮管理页交付说明。更新上传与设备授权后的更新下载共用现有 Lucky API 代理，不增加容器。


AUTH6 `0.2.23-FAW-AUTH6 / 97` 已本地构建，可由管理员上传发布给低版本 App。本轮未线上发布 APK。

UPD3 `0.2.26-FAW-UPD3 / 100` 是本轮交付的厂商安装适配与自动打开主界面的正式发布包，使用原 Android 发布证书与现有 NAS 配置；本地 APK 为 `outputs/apk/latest/FAW-Play-0.2.26-UPD3.apk`。首次升级至此适配需要 ADB 安装引导测试包，或其他已有可用的安装途径。本轮已通过车机应用内更新将 `0.2.22-FAW-UPDATETEST3 / 96` 升到 NAS 发布的 `0.2.23-FAW-AUTH6 / 97`；系统记录安装来源为 `ltd.qisi.sotasupportapp`。旧 97 不包含本轮厂商适配，因此车机当前安装 `0.2.24-FAW-UPDATETEST3 / 98` 引导测试版。管理员上传正式 100 后，在车机断开 CarPlay 并通过更新页下载、静默安装；验收 98 → 100 以及安装完成后自动打开主界面。完整 NAS 更新到 100 的恢复验收仍待实际发布后完成；本轮未线上发布 APK。

UI35 `0.2.27-FAW-UI35 / 101` 在 UPD3 基础上优化授权页面，包含相同的厂商安装与自动打开机制。可发布 `outputs/apk/latest/FAW-Play-0.2.27-UI35.apk` 给较低版本，并在车机验证安装后主界面恢复。本轮界面预览使用本地测试后端，未修改 NAS 的授权数据或更新发布。

UPD4 `0.2.29-FAW-UPD4 / 103` 在 NAVI36 基础上修正上述检测拒绝路径，补充不需要 ADB 的诊断信息。旧 UI35 若已经检测失败，上传新版不会改变旧 App 的安装能力：首次覆盖安装需要该车机原有可用的安装途径。不能将本地回归通过或 3337 静态核对表述为客户实车已经修好；须读取客户实际诊断并验收安装、更新后自动打开和正常授权。
