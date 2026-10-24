# Autunn Play 官网

官网源码在 `site/`，使用原生 HTML、CSS 和 JavaScript。颜色、标志和卡片样式沿用 App，支持浅色、深色及跟随系统；外观选择保存在浏览器本地。当前带实际下载文件的本地预览包在仓库外的 `../outputs/web/FAW-Play-official/`。

## 下载来源

App 优先请求同源 `GET /v1/updates/com.shihab.diplay/stable`。接口返回 `{manifest, signature}`；`manifest` 是 base64url 编码的 UTF-8 JSON，包含包名、版本号、最低 Android 版本、文件大小、SHA-256、签名证书摘要、更新说明和 `downloadPath`。下载路径必须匹配 `/v1/updates/com.shihab.diplay/stable/artifacts/{sha256}.apk`。网页校验字段及路径后显示实际发布版本；APK 发布仍由既有管理员更新流程负责，App 内的更新签名校验不变。

`site/downloads.json` 是下载目录，`schema` 为 `1`，包含 `app` 和 `firmware`。源码默认 `app: null`，固件保留已经核查的型号、版本、大小和校验值，但 `downloadUrl: null`。没有实际交付文件时，网页展示“下载暂未开放”；不会依据源码版本或本地 APK 推断线上已发布。固件是完整 `3222` 外层 ZIP，适用范围以目录内的 `model` 和更新说明为准。

外部静态交付包可以带本地 App 备用下载及固件下载。有效的线上 App 清单优先于本地备用项；仅本地提供的 App 会标为“本地交付版本”。App 首次安装与同签名覆盖升级使用同一完整 APK；固件 ZIP 是独立下载项。

## 验证和准备交付包

以下命令在仓库根目录执行。页面验证和静态打包使用 Python 标准库，无需 Node、npm 或前端框架；读取 APK 元数据需要 Android SDK 的 `aapt`。

```sh
python3 scripts/build_site.py
python3 scripts/build_site.py --output ../outputs/web/FAW-Play-official
```

第一条验证源码并更新旧语言入口到中文主页；第二条输出不含二进制文件的静态目录。带实际文件时，使用真实输入路径替换示例：

```sh
python3 scripts/build_site.py \
  --output ../outputs/web/FAW-Play-official \
  --app-apk /path/to/FAW-Play-release.apk \
  --firmware-zip /path/to/HS5-3222-full.zip \
  --aapt /path/to/android-sdk/build-tools/version/aapt
```

脚本读取 APK 的实际包名、版本及最低系统要求，计算大小和 SHA-256；固件必须与源码目录中的大小和 SHA-256 一致。输出目录会得到独立的 `downloads.json` 与 `downloads/` 文件，不改源码目录中的发布状态。APK 和固件必须输出到 Git 仓库外。可用 `--app-notes /path/to/notes.txt` 指定 UTF-8 App 更新说明。

`--link-files` 可在同一文件系统上使用硬链接，减少大文件复制；它适合本地预览，目标文件与原文件共享 inode。不要编辑、覆盖写入或 `chmod` 硬链接交付包内的二进制文件，否则原文件也会改变。硬链接不是符号链接，无需为它加 `rsync --copy-links`。

部署前应复制到新的独立目录，再给目标副本设置网页进程可读权限。例如在有权写入 `/srv` 的部署环境执行：

```sh
website_release_dir=$(mktemp -d /srv/faw-play-web.XXXXXX)
rsync -r ../outputs/web/FAW-Play-official/ "$website_release_dir/"
find "$website_release_dir" -type d -exec chmod 755 {} +
find "$website_release_dir" -type f -exec chmod 644 {} +
```

这里使用新目录和 `rsync -r`，不加 `-H`、`--link-dest` 或 `-a`，让交付文件成为独立副本；权限调整只作用于这些副本，避免继承原 APK 的 `0600` 权限。将网页服务器指向新的目录后，保留原文件作为构建输入。

本地预览可以运行：

```sh
python3 -m http.server 8787 --directory ../outputs/web/FAW-Play-official
```

访问 `http://localhost:8787/`。此静态预览没有更新 API，使用已准备的本地文件；预览不等于线上发布。

## 与授权服务同源提供

`authorization-server` 的 `processResources` 将 `site/index.html`、`assets/**` 和 `downloads.json` 打入 JAR。`/`、`/index.html` 及所需的具体官网资源允许匿名 GET，管理 API 继续要求管理员角色。官网仅加载同源脚本、样式、图片和请求，页面及目录使用 `no-cache`。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew -p authorization-server bootJar
```

其他主机使用自己的 Java 17 或更新 JDK。产物为 `authorization-server/build/libs/autunn-play-authorization-1.0.0.jar`，替换服务 JAR 需通过既有部署流程；构建命令不会部署。Dockerfile 同样复制 `site/`，`Dockerfile.dockerignore` 仅增加官网源码和资源的例外，继续排除密钥及其他目录。

JAR 不包含 APK 或固件 ZIP，也不直接提供 `/downloads/`。若要在同一站点提供完整固件及本地备用 APK，反向代理需把 `/downloads/` 和准确的 `/downloads.json` 路径交给外部静态目录，其余请求仍交给授权服务。例如将下列 `root` 替换为前面准备的独立副本目录，并接入现有 HTTPS 入口：

```nginx
server {
    listen 80;
    server_name _;
    root /srv/faw-play-web;

    location = /downloads.json {
        try_files $uri =404;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options "nosniff" always;
    }

    location /downloads/ {
        autoindex off;
        try_files $uri =404;
        types {
            application/vnd.android.package-archive apk;
            application/zip zip;
        }
        default_type application/octet-stream;
        add_header Content-Disposition "attachment";
        add_header X-Content-Type-Options "nosniff" always;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
    }
}
```

如果全部页面由静态服务器提供，使用交付目录作为 `root`，以 `try_files $uri $uri/ =404` 提供页面，并将 `/v1/updates/` 代理到 `http://127.0.0.1:8080`。两种方式都保持更新请求同源，无需开放跨域访问。单独托管 GitHub Pages 的源码目录不会自动提供更新 API 或未上传的下载文件。

验收时检查两种主题、下载目录显示、实际文件下载、校验值，以及未登录的管理 API 仍返回 `401`。本文给出准备与配置方法；本次没有部署或发布线上网站。
