# Vanta SMB Android

用于在 Android 手机或平板上通过 SMB 访问 Vanta 光谱仪文件，并在检测到新 JSON 文件时扫码重命名。原生 Java 项目，当前版本 **1.0.1**，重点回归 Android 11（API 30）。安装包可从本仓库 Releases 下载。

## 功能

- 默认设备地址 `192.168.10.1`，默认用户名/密码均为 `vanta`
- 支持连接光谱仪热点，或在同一局域网内使用
- 可从 SMB 服务器根目录浏览共享，也可直接填写 `共享名/目录`
- 主界面每 4 秒检查当前目录；新 JSON 非空且连续两次大小、修改时间不变后，依次提示扫码
- 也可点击任意 JSON 文件手动触发扫码
- 二维码按 `|` 分隔，使用第二段作为新文件名，并保留 `.json`
- 支持 QR 原始字节段的 UTF-8/GB18030 解码，以及常见 Latin-1 中文乱码纠正
- 重命名前确认，不覆盖同名文件
- “扫码自检（无需连接设备）”用于独立验证相机与中文解析，不修改远程文件

示例二维码：

```text
XX材料有限公司|SUP001|PO2024001|MAT001|11|316|热轧|ASTM|1200*600*20|H2024001
```

会把所选 JSON 重命名为 `SUP001.json`。

## 构建

使用 **JDK 17、Android SDK Platform 35**。项目自带 **Gradle 8.9 Wrapper**，AGP 版本为 8.7.3。Android Studio 可直接打开项目，命令行通过 `ANDROID_HOME` 或不提交的 `local.properties` 配置 SDK 路径：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

最低 Android 7.0（API 24），目标 API 35。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。连接真实设备/模拟器后运行 `./gradlew connectedDebugAndroidTest`。GitHub Actions 会构建 APK，并执行 Android 11/15 生命周期测试及 Android 11 实际相机预览测试。

## 使用

1. 手机连接光谱仪热点，或让手机与光谱仪处于同一局域网。
2. 保持默认 IP/账号密码，按实际设备填写共享名或目录；不知道共享名可留空。
3. 点击“连接并浏览”，进入 JSON 输出目录。
4. 等待新 JSON 出现，或点击已有 JSON，然后扫码。
5. 确认解析出的文件名后执行远程重命名。

> Android 可能提示当前热点“无法访问互联网”。请保持连接；SMB 只需要局域网连通。

首次进入目录时已有的 JSON 通过点击文件手动扫码；“稍后”跳过自动提示，但仍可手动处理。**扫码和进入后台期间暂停轮询；只监控当前目录，不递归、不在后台持续监控。** 文件稳定性检查不等于设备提供的写入完成信号，重命名前还会复核文件是否变化。

## 接手开发

- `MainActivity.java`：SMB 浏览、轮询和扫码/重命名流程。
- `PortraitCaptureActivity.java`：扫码权限、预览、生命周期与错误返回；历史类名保留，支持旋转。
- `SmbClient.java`：连接、列表、Unicode 路径及不覆盖重命名。
- `JsonTracker.java`：多文件队列、稳定检测与本次重命名去重。
- `ScanTextParser.java`：中文解码、第二段及文件名校验。

修复了扫码时缺少 AndroidX Core 导致的崩溃、Wrapper 版本不匹配、中文 SMB 路径错误、多文件漏提示及扫码期间继续弹窗问题。详见 [代码审查报告](docs/CODE_REVIEW.md) 和 [同事复测说明](docs/TESTING.md)。

调试 APK 可侧载；正式发布应由维护者配置签名密钥。仓库不包含设备数据、本机 SDK 路径或签名密钥。若仓库为私有，需要在 GitHub Settings → Collaborators 邀请同事后才能访问。
