# Autunn Play 品牌资源

本次更名使用以直线构成的几何 A 图形，不使用车企车标或下载的第三方图案。矢量原稿为 `autunn-mark.svg`，Android 和网站的不同尺寸资源由 `scripts/generate-brand-assets.py` 使用相同坐标生成。脚本需要 Pillow，仅用于生成资源，不增加应用运行依赖。

名称由项目维护者确定。原创绘制记录不等于商标注册、可注册性或近似检索结论。

当前 Android 包名、签名和授权协议继续沿用，历史内部配置标识不作为对外产品名称。

`0.2.59-AUTUNN2 / versionCode 133` 移除了未被代码引用的旧 `common/src/main/assets/hongqi_shield.png`。设置页使用 `ic_autunn_logo`，CarPlay 默认返回入口使用 `raw/ic_car_home.png`，通知使用白色单色 A，连接准备页使用深绿底 A；均由上述生成脚本绘制。保留现有资源标识以兼容调用，不再保留未使用的车企车标资源。用于检验旧版图标迁移的测试素材不参与 APK 打包。
