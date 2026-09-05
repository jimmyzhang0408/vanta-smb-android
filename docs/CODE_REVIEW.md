# 代码审查与扫码崩溃修复

日期：2026-09-05。审查基线：`6cefeea`（原 1.0.0）。

## P1：扫码启动崩溃

原版在 Android 11 / API 30 和 Android 15 / API 35 的 Robolectric Activity 启动测试中均失败：

```text
java.lang.NoClassDefFoundError: androidx/core/content/ContextCompat
  at com.journeyapps.barcodescanner.CaptureManager.openCameraWithPermission(CaptureManager.java:241)
  at com.journeyapps.barcodescanner.CaptureManager.onResume(CaptureManager.java:230)
  at com.journeyapps.barcodescanner.CaptureActivity.onResume(CaptureActivity.java:41)
Caused by: java.lang.ClassNotFoundException: androidx.core.content.ContextCompat
```

实际使用的 ZXing Embedded 4.3.0 发布 POM 只声明 ZXing Core，缺少扫码权限分支需要的 AndroidX Core。原 APK 可以编译，但运行到权限检查时缺类。修复：显式加入 `androidx.core:core:1.13.1`。回归测试执行 `onResume` 并断言扫码 Activity 保持打开，避免仅创建布局或测试字符串而漏检。

尚未拿到同事设备日志，因此不能断言这是对方设备上唯一的故障；这是本项目中已复现的确定崩溃。

## 其他修复

| 优先级 | 原问题 | 修复 |
| --- | --- | --- |
| P1 | Wrapper 8.7 与 AGP 8.7.3 不匹配 | 更新 Wrapper 为 8.9 |
| P1 | 百分号编码被 jCIFS 当作真实 SMB 路径 | 使用 Unicode 路径，测试实际 UNC 路径 |
| P1 | 同时新增多个 JSON，只提示首个 | 队列逐个提示 |
| P1 | 扫码/后台继续轮询，退出后回调仍可能弹窗 | 前台与扫码流程门控，销毁检查，IO 线程关闭连接 |
| P2 | 仍在写入的文件被提示 | 两次非空稳定检测，重命名前复核大小和修改时间 |
| P2 | 全局 6 秒抑制漏掉真正新文件 | 只忽略自身重命名的目标路径 |
| P2 | 目录/连接/重命名操作交错 | 统一忙状态和禁用冲突操作 |
| P2 | 中文猜测编码不可靠 | QR 原始字节段严格 UTF-8/GB18030 解码，保留混合模式完整文本 |
| P2 | 相机异常和拒绝权限缺少反馈 | 返回中文错误，保护生命周期，增加扫码自检 |
| P2 | 连接密码进入系统备份 | 禁用备份，信息保留在应用私有数据中 |

保留 SMB1–SMB3 协商以兼容旧光谱仪。应用不把设备文件或二维码发送到云端。

## 验证边界

- JVM 测试覆盖 Android 11/15 扫码启动、权限拒绝、队列、中文解码和 SMB 路径。
- 设备测试从主界面进入扫码，等待实际相机预览，取消后重复三次。CI 固定运行 Android 11；结果以对应提交 Actions 报告为准。
- 尚未连接实际光谱仪；共享名、设备 SMB 协议、热点路由及写权限需要现场验证。
- 两次大小/修改时间一致是稳定性启发式，不是写入锁；若厂商提供完成标记或临时文件改名协议，应优先接入。
