# Hyper FCM Live

面向国行 HyperOS 的 LSPosed 模块，解除系统对 Google FCM 连接广播和应用唤醒的限制，让所选应用能够通过 FCM 接收后台推送。

项目源自 [Howard20181/HyperOS_FCM_Live](https://github.com/Howard20181/HyperOS_FCM_Live)，本分支基于 [billtv/HyperOS_FCM_Live](https://github.com/billtv/HyperOS_FCM_Live) 继续维护。

| 项目 | 说明 |
| --- | --- |
| 下载 | [正式版](https://github.com/miaonai/HyperOS_FCM_Live/releases/latest) · [Nightly](https://github.com/miaonai/HyperOS_FCM_Live/releases/tag/nightly) |
| 适用系统 | 国行 HyperOS |
| 测试反馈 | HyperOS 4（Android 17）初步真机测试有效 |
| 运行环境 | Root，以及支持现代 libxposed API 101 及以上的 LSPosed |
| 作用域 | 系统框架（`system`）和电量与性能（`com.miui.powerkeeper`） |

## 功能

- 放行 FCM 连接相关广播，解除电量与性能对 Google Play 服务联网、闹钟和唤醒锁的限制。
- 按应用选择允许 FCM 唤醒的对象，支持全选、清除和自动放行包含 FCM 的应用。
- 按应用名或包名搜索，筛选 FCM 应用和系统应用，并可打开 Google Play 服务的 FCM Diagnostics 检查连接。

管理界面参考 [XiaoTong6666/Sui](https://github.com/XiaoTong6666/Sui) 的 MIUIX 风格，支持浅色、深色主题。

## 安装与使用

使用前请确认 Google Play 服务已安装并启用，网络能够连接 FCM，目标应用已开启通知权限。

1. 从 [Releases](https://github.com/miaonai/HyperOS_FCM_Live/releases/latest) 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域同时勾选“系统框架”和“电量与性能”。
3. 重启手机。
4. 打开 Hyper FCM Live，开启需要 FCM 后台推送的应用。修改会自动保存。

“自动放行 FCM 应用”会根据应用声明的 FCM 接收组件进行识别，也适用于后续安装的应用。关闭自动放行后恢复手动选择。

## 贡献者与致谢

贡献者：Howard20181、billtv、zuohl、miaonai。感谢 [billtv](https://github.com/billtv) 提供 HyperOS 4（Android 17）支持。

FCM 相关实现参考 [kooritea/fcmfix](https://github.com/kooritea/fcmfix)；界面参考 Sui、Miuix 和 InstallerX-Revived，详见[第三方说明](THIRD_PARTY_NOTICES.md)。

## 开发与许可

应用界面和 Hook 实现位于 `HyperFCMLive`，隐藏 API 桥接位于 `hiddenapi`。构建、测试和签名配置见[开发说明](docs/development.md)。

本项目采用 [GPLv3](LICENSE) 开源。
