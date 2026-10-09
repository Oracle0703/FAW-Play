# FAW Play 仪表调节工具（NAVI30）

适用于 NAVI30 配套车机包和已适配的红旗 HS5 东软 Android 9 车机，兼容 TUNE2～TUNE5 的布局调节。在 Mac 本机运行，不上传数据，不需要 root。旧 TUNE 包的“恢复默认”仍使用各自 APK 的默认值。

## 使用

1. 覆盖安装使用原包名和原签名的 NAVI30 配套车机包，打开 FAW Play，保留配对和设置。模拟器包使用独立 `.hudtest` 包名，不用于替换车机安装。
2. 车机开启「工程模式 → 东软工程模式 → 开发模式 → ADB 开关」，连接电脑 USB。
3. 停稳、没有通话时，连接 CarPlay 并开始导航；三指左滑开启仪表地图。
4. 解压工具，双击 `启动仪表调节.command`，保持终端窗口打开。浏览器打开 `http://127.0.0.1:8765`。
5. 面板选择车机并读取参数。两项分别是「转向提示」和「到达／里程信息栏」。选择后调整位置、宽度、高度和字号，自动同步、保存并刷新已有仪表提示。
6. 调好后点击「导出当前参数」，把 JSON 文件发给开发者固化正式版。终端按 Control+C 退出工具。

TUNE2 起取消 TUNE1 单独拆出的剩余时间框，剩余时间恢复到整条信息栏内。TUNE1 已调整的转向提示位置与比例继续保留；旧的独立剩余时间框参数不套用到信息栏。

TUNE3 将转向提示、箭头底框和整条信息栏改为小圆角矩形，采用更醒目的白色箭头、加粗距离文字、柔和道路文字和紧凑居中的信息栏；保留现有位置、宽高、字号缩放。调节协议仍为 2，TUNE2 的参数继续有效。

TUNE4 按实际显示文字的宽度，将箭头和两行文字作为整体在转向提示框内水平居中。提示框坐标、宽高、字号以及信息栏参数均保留。

TUNE5 新增仪表内容切换：地图＋当前可调提示框、CarPlay 原生仪表组合、CarPlay 原生导航卡片。连接期间通过已有仪表 `showUI` 和关键帧请求切换；原生模式不叠加本地卡片，布局参数仍保留，切回第一项即可恢复。没有会话时保存为下次连接的选择；在线命令未能发送时不覆盖已保存的选择。命令发送成功仅代表传输成功，路口／立交图片、车道引导的真实内容由 iPhone 和导航应用决定。没有可靠字段定义时不解析 `0x5204` 车道消息。

## 独立参数

- X/Y：提示框左上角，X 向右、Y 向下。使用 **1920 × 720 仪表投屏画布**参考，不是仪表物理面板规格，也不是 iPhone 截图尺寸。
- 宽度：80～1920 像素；高度：40～720 像素。分别调整，互不联动。
- 字号／图标：50%～150%。独立改变文字和转向图标大小，不改变已设置的提示框长宽，图标不拉伸。
- 框太窄时文字省略，框太矮时可能裁切；可减小字号。靠近屏幕边缘时 App 将整个框限制在画布内。
- 转向提示：箭头、下一路口距离和道路名称。
- 到达／里程信息栏：整条到达时刻、剩余时间、剩余里程信息。没有额外的「47 分」框。
- 「恢复本项默认」恢复选中项。「恢复 NAVI29 原布局」再点一次确认，清除调节参数，恢复原位置和大小。
- 参数存于 App 独立配置文件，重启和断开 ADB 后仍保留；卸载或清除 App 数据会删除。覆盖安装 NAVI29 原包可退回旧布局。

NAVI30 把最终确认的布局写入 APK 默认参数：转向提示为 X=1396、Y=81、457×164、字号／图标 69%；到达／里程信息栏为 X=90、Y=622、450×45、字号 80%。新安装及没有本机自定义参数的用户直接使用此布局，普通发布构建的绘制效果也不再依赖 ADB 调节开关。已有有效的本机调节参数继续优先；「恢复本项默认」恢复该项新版默认，「恢复 NAVI29 原布局」仍恢复历史布局。

## 直接使用 ADB

读参数：

```sh
adb shell am broadcast --include-stopped-packages -n com.shihab.diplay/com.shilapi.xcertplay.ClusterLayoutReceiver -a com.shihab.diplay.action.CLUSTER_LAYOUT --es op get
```

例如，把整条信息栏放在 X=150、Y=540，宽 850、高 100，字号 100%：

```sh
adb shell am broadcast --include-stopped-packages -n com.shihab.diplay/com.shilapi.xcertplay.ClusterLayoutReceiver -a com.shihab.diplay.action.CLUSTER_LAYOUT --es op set --es target ete --ei x 150 --ei y 540 --ei width 850 --ei height 100 --ei scale 100
```

调整转向提示时将 `--es target ete` 改为 `--es target tbt`。省略 width／height 的旧 ADB 命令继续按原来的等比例方式计算尺寸；新版面板每次均发送宽度与高度。

恢复 NAVI29：

```sh
adb shell am broadcast --include-stopped-packages -n com.shihab.diplay/com.shilapi.xcertplay.ClusterLayoutReceiver -a com.shihab.diplay.action.CLUSTER_LAYOUT --es op original
```

多台设备连接时在 `adb` 后加入 `-s 车机序列号`。成功响应包含 `ok:true`、`protocol:2` 和已保存的参数；没有该响应不能视为修改成功。

切换为 CarPlay 原生仪表组合（TUNE5）：

```sh
adb shell am broadcast --include-stopped-packages -n com.shihab.diplay/com.shilapi.xcertplay.ClusterLayoutReceiver -a com.shihab.diplay.action.CLUSTER_LAYOUT --es op content --es content INSTRUMENTS
```

原生卡片使用 `TURN_CARD`，恢复地图＋可调提示框使用 `MAP_WITH_CUSTOM_CARD`。回复包含已保存的 `content`、命令已送达的 `displayedUrl` 和 `sessionRunning`。超时后先重新读取状态，因为在途命令可能随后完成。

## 无 iPhone 的模拟器演示

构建 `./gradlew -PfawClusterTuning=true :mobile:assembleDebug`，将 `mobile/build/outputs/apk/debug/mobile-debug.apk` 安装到模拟器，启动：

```sh
adb -s emulator-5554 shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.ClusterNavigationPreviewActivity
```

演示自动循环模拟路口、立交出口和匝道图片，以及车道引导、转向距离、到达／里程信息。可选择三种内容、切换场景或暂停演示。地图＋提示框使用正式的本地卡片绘制类；原生模式中的图片和布局为示意画面，未经过真实 iPhone 视频接收／解码，不代表高德实际输出或其真实视觉样式。不会向车辆写入模拟导航。调节示例参数只保存于独立调试包。

普通 `assembleDebug` 不包含 CarPlay 认证资源，不能作为连接 iPhone 的车机测试包。需要真实车机包时按 `docs/BUILD.md` 使用已有授权的外部认证和签名输入构建。

## 启动与安全

Python 3 标准库，无需安装依赖。自动查找 PATH、Android SDK 环境变量及 `~/Library/Android/sdk/platform-tools/adb`。双击无法运行时在工具目录终端执行 `python3 tuner.py`；自定义 ADB 路径用 `--adb /实际路径/adb`；端口占用时用 `--port 8766`，或退出旧的工具终端后重开。

调节接口由 `fawClusterTuning` 编译开关启用，NAVI30 配套包已开启；发送者必须持有系统 `DUMP` 权限。普通第三方 App 不能调用；Mac 面板仅绑定本机地址。当前面板支持协议 2 的 TUNE2／TUNE3／TUNE4／TUNE5／NAVI30；原生内容切换在 TUNE5 起且设备适配时启用。TUNE1 的旧接口不支持独立宽高，会明确报接口版本不匹配。关闭编译开关仅关闭调节接口，不改变 NAVI30 的默认布局绘制。
