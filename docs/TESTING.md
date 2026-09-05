# 同事复测与接手

## Android 11 复测

1. 安装 1.0.1，点击“扫码自检”，允许相机权限，应出现预览。
2. 返回再进入，重复三次；切后台再返回，检查预览恢复。
3. 系统设置关闭相机权限，再次扫码并拒绝授权，应提示并回到主界面，不崩溃。
4. 扫 UTF-8 和 GB18030 中文二维码，第二段 SUP001 应得到 SUP001.json；中文第二段也应正确。
5. 缺少分隔符、空第二段、损坏字符和重名文件应得到明确处理。
6. 手机连接光谱仪热点/同一局域网，进入实际共享。已有 JSON 可点击手动扫码。
7. 连续生成两个 JSON，稳定后应依次提示；扫码期间继续生成的文件返回后仍应检测。
8. 确认后远程文件只改名，不改内容、不覆盖。同样检查中文共享路径及中文目标名。

没有实际光谱仪时先做扫码自检；自动测试不能替代真实 SMB 写权限和设备完成写入规则的验证。

## 自动测试

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

生命周期回归测试：ScannerLifecycleTest（API 30/35）；真实相机预览：ScannerDeviceTest。

## 捕获异常

手机开启 USB 调试、连接电脑，执行并复现一次：

```bash
adb logcat -v time AndroidRuntime:E VantaScanner:E '*:S'
```

提供 FATAL EXCEPTION / Caused by / VantaScanner 附近日志和手机品牌、型号、Android 版本。不要只根据“闪退”推断原因。

代码为原生 Java。不要删除 AndroidX Core 显式依赖：ZXing 4.3.0 的发布 POM 不会自动带入它。不同电脑自动生成的调试签名可能不同；安装报签名不一致时需复用旧签名，或在接受清除应用数据后卸载旧测试版。

若仓库为私有，在 GitHub Settings → Collaborators 添加同事；只发送链接不会自动授予权限。
