# Hyper FCM Live

修复国行 HyperOS 对 FCM 连接广播和应用唤醒的限制。需要 Root 及支持现代 libxposed API 的 LSPosed；模块声明的最低 API 为 101，目标 API 为 102。启用模块可能增加耗电。

## 使用

1. 安装签名的 Release APK，在 LSPosed 中启用模块。作用域为系统框架和电量与性能（`com.miui.powerkeeper`），然后重启手机。
2. 打开 Hyper FCM Live，等应用扫描和已保存的选择都读取完成后，列表一次显示，已开启的应用排在前面。加载期间可以修改搜索和筛选，最终列表按最新条件显示；未连接 LSPosed 或选择读取失败时显示对应提示。通过胶囊开关选择允许 FCM 唤醒的应用，行按应用身份平滑换位。修改在后台顺序保存；保存失败时恢复最近成功保存的选择并提示。
3. 点击右上角搜索图标，按应用名或包名查找。三点菜单提供“仅显示 FCM 应用”和“显示系统应用”，两个筛选条件会自动记忆；菜单按文字宽度展开，用右侧勾选标记表示启用状态，主界面不显示数量统计。
4. “全选 FCM 应用”经确认后，将当前用户全部检测到的 FCM 应用加入手动白名单，包括系统应用和被搜索、筛选隐藏的应用，保留其他手动选择。“清除所有选择”经确认后清空全部手动选择并关闭自动放行。空白名单不再表示全部放行。
5. “自动放行 FCM 应用”独立于手动选择，开启后后台检测并放行包含 FCM 的应用，也会在安装或更新应用后重新检测。自动管理的应用开关显示为开启且不可手动修改；关闭自动放行后恢复原有手动选择。后台扫描完成前，新检测的应用可能暂未被自动放行。
6. MoreCircle 菜单还提供“打开 FCM Diagnostics”和“关于”。Diagnostics 由 Google Play 服务提供；不可用时会显示提示。关于窗口显示应用名、当前安装版本、GPLv3 开源声明、可点击的 GitHub 链接和贡献者。菜单没有暗色遮罩；关于和确认窗口从底部进入，也不使用遮罩，可下滑关闭。

界面参考 [XiaoTong6666/Sui](https://github.com/XiaoTong6666/Sui) 的 MIUIX 风格，自动跟随系统浅色／深色主题。Android 12 及以上让顶部栏与列表共用同一帧内容，统一进行 GPU 背景模糊，较低版本使用实色背景。搜索由右上角放大镜展开为胶囊输入框，展开后图标与输入框共用背景；列表隐藏滚动条，扩大筛选范围时新应用在旧行下方自然露出。底部确认窗口采用居中标题和左右等宽的取消、确认按钮。底部适配透明手势导航区，末行保留安全间距。桌面图标支持自适应形状，以及 Android 13 起的单色主题图标（是否显示由启动器决定）。

仅以国行 HyperOS 为适配目标。模块不能替代 Google Play 服务、网络连通性、应用的 FCM 注册和通知权限。具体 HyperOS 版本的 Hook 兼容性仍需真机验证。

## “包含 FCM”的含义

应用在后台读取已安装包的组件信息，检测：

- `com.google.firebase.iid.FirebaseInstanceIdReceiver` 或旧版 `com.google.android.gms.gcm.GcmReceiver`；
- 声明 `com.google.android.c2dm.intent.RECEIVE` 的广播接收器；
- `FirebaseMessagingService` 或声明 `com.google.firebase.MESSAGING_EVENT` 的服务。

查询包括停用组件，意图查询包含已停止的应用。只在当前 Android 用户的可见应用中检测；不会扫描其他用户的应用数据。仅包含 Analytics 的 `AppMeasurementReceiver` 不会被当作 FCM 证据。

“包含 FCM”标签显示在应用名称之后。它是静态组件检测，不代表已注册 FCM、正在使用 FCM 或推送一定可达。没有标签也不能排除定制实现。自动放行默认关闭；开启时，检测结果参与有效放行策略，但不会覆盖手动白名单。自动检测使用系统主用户的包信息，不跨用户扫描应用数据。

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

在 Actions → Android CI → Run workflow 选择要构建的分支。构建完成后下载 `hyperos-fcm-live-release` artifact，解压即可安装其中的 APK。主分支构建通过测试、Lint 和 APK 签名验证后，会自动将 `nightly` 标签推进到本次构建的提交，并上传 APK 到 Nightly 预发布；独立分支手动构建只上传 artifact。

本地构建先安装 JDK 21 和对应 Android SDK，并在不入库的 `local.properties` 中配置 `sdk.dir`、`storeFile`、`storePassword`、`keyAlias`、`keyPassword`，然后运行：

```powershell
.\gradlew.bat :HyperFCMLive:testReleaseUnitTest :HyperFCMLive:lintRelease :HyperFCMLive:assembleRelease
```

APK 输出位于 `HyperFCMLive/build/outputs/apk/release/`。Release 使用 R8 压缩，`debuggable=false`；版本名含 `beta` 与 Release 构建类型是两个独立概念。

如果已安装的旧包使用其他签名，Android 会拒绝覆盖安装。请先记录白名单，卸载旧版后安装，再在 LSPosed 中重新启用；卸载可能清除配置。
