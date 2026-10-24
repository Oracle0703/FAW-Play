# Autunn Play 官网

官网源码在 `site/`，使用原生 HTML、CSS 和 JavaScript。颜色、标志和卡片样式沿用 App，支持浅色、深色及跟随系统；外观选择保存在浏览器本地。新品牌静态站点的示例输出目录为仓库外的 `../outputs/web/Autunn-Play-official/`。

## 下载来源

App更新需要车机持有设备私钥和已经激活的授权票据，通过受保护POST接口分别检查、下载和申请安装授权。官网不请求匿名更新GET、不提供APK直链，也不把 `downloads.json` 中的 app.downloadUrl变成下载按钮。页面引导用户在车机内激活授权码后更新；首次支持此协议的客户端通过受控渠道分发。

`site/downloads.json` 保持 `schema: 1`、`app` 和 `firmware` 结构；`app` 可提供版本说明，但任何下载地址均被忽略。源码默认 `app: null`。固件保留已核查的型号、版本、大小和校验值，准备实际文件前 `downloadUrl: null`；网页继续只接受同源 downloads目录下的完整ZIP。固件是独立于App的完整3222外层ZIP，适用范围以model和说明为准。

## 验证和准备交付包

以下命令在仓库根目录执行。页面验证和静态打包使用 Python 标准库，无需 Node、npm 或前端框架。

```sh
python3 scripts/build_site.py
python3 scripts/build_site.py --output ../outputs/web/Autunn-Play-official
```

第一条验证源码并更新旧语言入口到中文主页；第二条输出不含二进制文件的静态目录。带实际文件时，使用真实输入路径替换示例：

```sh
python3 scripts/build_site.py \
  --output ../outputs/web/Autunn-Play-official \
  --firmware-zip /path/to/HS5-3222-full.zip
```

固件必须与源码目录中的大小和SHA-256一致。输出目录会得到独立的 `downloads.json` 与 `downloads/` 文件，不改源码发布状态；固件必须输出到源码外。`--app-apk` 已停用并会在写入文件前明确报错，不能用静态打包绕过设备授权。首次新版APK应在受控渠道交付。

`--link-files` 可在同一文件系统上使用硬链接，减少大文件复制；它适合本地预览，目标文件与原文件共享 inode。不要编辑、覆盖写入或 `chmod` 硬链接交付包内的二进制文件，否则原文件也会改变。硬链接不是符号链接，无需为它加 `rsync --copy-links`。

部署前应复制到新的独立目录，再给目标副本设置网页进程可读权限。例如在有权写入 `/srv` 的部署环境执行：

```sh
website_release_dir=$(mktemp -d /srv/faw-play-web.XXXXXX)
rsync -r ../outputs/web/Autunn-Play-official/ "$website_release_dir/"
find "$website_release_dir" -type d -exec chmod 755 {} +
find "$website_release_dir" -type f -exec chmod 644 {} +
```

这里使用新目录和 `rsync -r`，不加 `-H`、`--link-dest` 或 `-a`，让交付文件成为独立副本；权限调整只作用于这些副本，避免修改原始输入文件权限。将网页服务器指向新的目录后，保留原固件作为构建输入。

本地预览可以运行：

```sh
python3 -m http.server 8787 --directory ../outputs/web/Autunn-Play-official
```

访问 `http://localhost:8787/`。此静态预览没有更新 API，使用已准备的固件文件；预览不等于线上发布。

## 与授权服务同源提供

`authorization-server` 的 `processResources` 将 `site/index.html`、`assets/**` 和 `downloads.json` 打入 JAR。`/`、`/index.html` 及所需的具体官网资源允许匿名 GET，管理 API 继续要求管理员角色。官网仅加载同源脚本、样式、图片和请求，页面及目录使用 `no-cache`。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew -p authorization-server bootJar
```

其他主机使用自己的 Java 17 或更新 JDK。产物为 `authorization-server/build/libs/autunn-play-authorization-1.0.0.jar`，替换服务 JAR 需通过既有部署流程；构建命令不会部署。Dockerfile 同样复制 `site/`，`Dockerfile.dockerignore` 仅增加官网源码和资源的例外，继续排除密钥及其他目录。

JAR 不包含 APK 或固件 ZIP，也不直接提供 `/downloads/`。若要在同一站点提供完整固件，反向代理需把 `/downloads/` 和准确的 `/downloads.json` 路径交给外部静态目录，其余请求仍交给授权服务。例如将下列 `root` 替换为前面准备的独立副本目录，并接入现有 HTTPS 入口：

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
        if ($uri !~ \.zip$) { return 404; }
        autoindex off;
        try_files $uri =404;
        types {
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

切换前由部署方移出静态根目录中的旧APK文件，并关闭/清理代理历史公开APK缓存。验收两种主题、固件下载及校验值、官网没有APK下载链接，匿名更新GET与未登录管理API均拒绝；再用已激活设备验证受保护更新。本文给出准备与配置方法；本次没有部署或发布线上网站。
