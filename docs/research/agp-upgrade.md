# AGP 版本升级调研

调研日期：2026-10-31

## 建议

若这次只做上游 PR #28 所指的窄范围升级，建议把根 `build.gradle` 中 `com.android.application` 从 `9.4.0` 更新到 `9.4.1`，保留 Gradle Wrapper `9.7.1`。这是同一 minor 线的已发布补丁版，改动范围最小。官方 API 参考页在 2026-10-26 将 `9.4.1` 列为当前版本；Google Issue Tracker 也记录了一个 R8 修复随 AGP `9.4.1` 发布。

截至调研日，`9.5.0` 也已是正式版，Google Maven 元数据和对应 POM 均能确认该版本已发布。其官方发布说明支持最高 API 37、最低 Gradle 9.6.0 和 JDK 17，因此与本项目给出的 API 37、Gradle 9.7.1、JDK 21 配置相符。若目标是采用最新稳定版，`9.5.0` 是可选版本；不过 Android Studio 兼容表最后更新于 2026-09-24，仍只列出 Quail 4 支持到 AGP 9.4，且早于 9.5.0 发布。未确认开发者的 Studio 版本前，`9.4.1` 是更保守的窄范围选择。

## Wrapper 判断

无需为 AGP `9.4.1` 或 `9.5.0` 改 Wrapper。Android 官方表列出 AGP 9.4、9.5 的最低 Gradle 版本均为 `9.6.0`；仓库现有 `9.7.1` 高于最低要求。AGP 发布说明列出的最低 JDK 是 17，本项目 JDK 21 满足该要求。

仓库当前根 `build.gradle` 声明 AGP `9.4.0`，`gradle/wrapper/gradle-wrapper.properties` 指向 Gradle `9.7.1`。本次仅核对配置和官方发布资料，没有下载构建依赖或运行构建。

## 发布可用性核验

只读请求 Google Maven 元数据时，`lastUpdated` 为 `20261028141942`，版本列表包含 `9.4.1` 和 `9.5.0`；两个版本对应的 `gradle-<version>.pom` 请求均返回 HTTP 200。因此，`9.4.1` 并非仅见于 PR 的版本号，且 `9.5.0` 已在 Maven 仓库发布。

## 来源

- [Android Gradle plugin 9.4.0 发布说明](https://developer.android.com/build/releases/agp-9-4-0-release-notes)：API 上限、Gradle 最低版本、JDK 最低版本。
- [Android Gradle plugin 9.5.0 发布说明](https://developer.android.com/build/releases/agp-9-5-0-release-notes)：9.5.0 正式版及兼容信息。
- [Android Gradle plugin 版本和兼容表](https://developer.android.com/build/releases/about-agp)：Gradle 最低版本、Android Studio 与 AGP 兼容范围；页面标注最后更新于 2026-09-24。
- [AGP API 参考版本列表](https://developer.android.com/reference/tools/gradle-api)：截至页面最后更新 2026-10-26，`9.4.1` 标作当前版本。
- [Google Issue Tracker：R8 修复随 AGP 9.4.1 发布](https://issuetracker.google.com/issues/506912893)。
- [Google Maven 元数据](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)，以及 [9.4.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom) 和 [9.5.0 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.0/gradle-9.5.0.pom)：用于核对实际发布版本和 POM 可访问性。
