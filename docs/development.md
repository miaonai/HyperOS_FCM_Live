# 开发说明

## 项目结构

Java 源码目录为 `HyperFCMLive/src/main/java/io/github/howard20181/hyperos/fcmlive/`。

| 文件或目录 | 内容 |
| --- | --- |
| `Hooker.java` | 系统框架与电量与性能中的 Hook |
| `Prefs.java` | 通过 libxposed 远程配置同步放行策略 |
| `FcmDetector.java` | 根据应用组件识别 FCM |
| `MainActivity.java` | 应用选择、搜索和筛选界面 |
| `HyperFCMLive/src/test` | 策略、组件识别和 Android 界面回归测试 |
| `hiddenapi` | 隐藏 API 声明与桥接 |

应用使用 Android Views。模块声明的最低 libxposed API 为 101，目标 API 为 102。

## 构建与测试

需要 JDK 21、Android SDK 37，Gradle 版本由仓库中的 Wrapper 指定。

未配置发布签名时，可以运行测试、Lint 和 Debug 构建：

```powershell
.\gradlew.bat :HyperFCMLive:testReleaseUnitTest :HyperFCMLive:lintRelease :HyperFCMLive:assembleDebug
```

配置签名后构建 Release：

```powershell
.\gradlew.bat :HyperFCMLive:assembleRelease
```

APK 输出到 `HyperFCMLive/build/outputs/apk/`。Release 使用 R8 压缩，不启用 debuggable。

版本名在 `HyperFCMLive/build.gradle` 中设置，版本代码取当前 Git 提交数量。构建时应保留完整 Git 历史。

## 发布签名

推荐使用 GitHub Actions 和仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEY` | 发布用 JKS 文件的 Base64 |
| `KEY_STORE_PASSWORD` | 密钥库密码 |
| `ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

本地签名可通过环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 配置，也可以在不入库的 `local.properties` 中设置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，并配置 `sdk.dir`。

后续版本使用相同发布密钥以支持覆盖安装。缺少签名配置时，Release 构建会失败；密钥及凭据不应提交到仓库。

## 持续集成

GitHub Actions 运行单元测试、Lint、构建和 APK 验证，检查发布证书及 debuggable 标记。测试与 Lint 报告通过 `validation-reports` artifact 提供，签名 APK 和验证记录通过 `hyperos-fcm-live-release` artifact 提供。

主分支通过验证后自动更新 Nightly 标签并发布 APK。新 APK 上传并校验 SHA-256 成功后，清理 Nightly 中的旧版 APK。独立分支可以在 Actions 中手动触发构建，不更新 Nightly。
