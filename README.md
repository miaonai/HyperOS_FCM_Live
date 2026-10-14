# Hyper FCM Live

修复国行 HyperOS 对 FCM 连接广播和应用唤醒的限制。需要 Root 及支持现代 libxposed API 的 LSPosed；模块声明的最低 API 为 101，目标 API 为 102。启用模块可能增加耗电。

## 使用

1. 安装签名的 Release APK，在 LSPosed 中启用模块。作用域为系统框架和电量与性能（`com.miui.powerkeeper`），然后重启手机。
2. 打开 Hyper FCM Live，勾选允许 FCM 唤醒的应用。选择会自动保存；无法连接 LSPosed 时会在保存时提示。
3. 可按名称、包名搜索，或选择“仅显示 FCM”。全选和清除只影响当前列表，隐藏应用的选择保持不变。“显示系统应用”和“仅显示 FCM”会自动记忆，下次打开时恢复。

应用自动跟随系统浅色／深色主题。桌面图标支持自适应形状，以及 Android 13 起的单色主题图标（是否显示由启动器决定）。

仅以国行 HyperOS 为适配目标。模块不能替代 Google Play 服务、网络连通性、应用的 FCM 注册和通知权限。具体 HyperOS 版本的 Hook 兼容性仍需真机验证。

## “包含 FCM”的含义

应用在后台读取已安装包的组件信息，检测：

- `com.google.firebase.iid.FirebaseInstanceIdReceiver` 或旧版 `com.google.android.gms.gcm.GcmReceiver`；
- 声明 `com.google.android.c2dm.intent.RECEIVE` 的广播接收器；
- `FirebaseMessagingService` 或声明 `com.google.firebase.MESSAGING_EVENT` 的服务。

查询包括停用组件，意图查询包含已停止的应用。只在当前 Android 用户的可见应用中检测；不会扫描其他用户的应用数据。仅包含 Analytics 的 `AppMeasurementReceiver` 不会被当作 FCM 证据。

“包含 FCM”是静态组件检测，不代表已注册 FCM、正在使用 FCM 或推送一定可达。“未检测到 FCM”也不能排除定制实现。检测结果不会自动修改白名单。

参考：[kooritea/fcmfix](https://github.com/kooritea/fcmfix)、[FirebaseMessagingService 文档](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService)、[Android 深色主题文档](https://developer.android.com/develop/ui/views/theming/darktheme)。

## Release 构建

推荐使用 GitHub Actions。仓库使用 JDK 21，Gradle Wrapper 与 Android SDK 版本以构建文件为准。

在仓库 Settings → Secrets and variables → Actions 中设置四个签名 Secret：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEY` | 发布用 JKS 文件的 Base64 |
| `KEY_STORE_PASSWORD` | 密钥库密码 |
| `ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

妥善备份密钥库和密码。后续版本必须使用同一密钥才能覆盖安装。Release 构建缺少签名配置会失败，不会回退到调试签名。

在 Actions → Android CI → Run workflow 选择要构建的分支。构建完成后下载 `hyperos-fcm-live-release` artifact，解压即可安装其中的 APK。主分支构建还会更新 `nightly` 预发布；独立分支手动构建只上传 artifact。

本地构建先安装 JDK 21 和对应 Android SDK，并在不入库的 `local.properties` 中配置 `sdk.dir`、`storeFile`、`storePassword`、`keyAlias`、`keyPassword`，然后运行：

```powershell
.\gradlew.bat :HyperFCMLive:testReleaseUnitTest :HyperFCMLive:lintRelease :HyperFCMLive:assembleRelease
```

APK 输出位于 `HyperFCMLive/build/outputs/apk/release/`。Release 使用 R8 压缩，`debuggable=false`；版本名含 `beta` 与 Release 构建类型是两个独立概念。

如果已安装的旧包使用其他签名，Android 会拒绝覆盖安装。请先记录白名单，卸载旧版后安装，再在 LSPosed 中重新启用；卸载可能清除配置。
