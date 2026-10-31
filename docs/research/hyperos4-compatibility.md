# HyperOS 4 兼容修复来源

核对日期：2026-10-31。

## 实际作者

- Howard20181 的 [Support OS4 提交 d20b204](https://github.com/Howard20181/HyperOS_FCM_Live/commit/d20b2048e414af1ad28394d11d657848cac08430) 位于 [PR #23](https://github.com/Howard20181/HyperOS_FCM_Live/pull/23) 的历史中，包含最初的 HyperOS 4 适配。
- [PR #30](https://github.com/Howard20181/HyperOS_FCM_Live/pull/30) 的作者是 793354162，提交为 `492015b367f60c1dfe60c0068cea7a0cc9daaeea`。
- [PR #31](https://github.com/Howard20181/HyperOS_FCM_Live/pull/31) 的作者是 E3N-glotm，提交为 `e307f28ab9437e8cb17e9613e47227b08cd8e63c`。PR 正文明确注明其 PowerKeeper 修复包含 #30 的工作。
- zuohl 的应用白名单工作来自 [PR #21](https://github.com/Howard20181/HyperOS_FCM_Live/pull/21)，相关提交已在当前分支历史中。

截至核对日期，#30、#31 均为未合并的上游 PR。本分支按实际采用的代码记录作者，不将 fork 的合并、构建者视为这些修复的作者。billtv 仍是本仓库的 fork 来源，README 的来源链接保留这一事实。

## 需要移植的行为

根据 [#30 的问题描述及补丁](https://github.com/Howard20181/HyperOS_FCM_Live/pull/30/files)，PowerKeeper 4.2.00 移除了部分旧方法。`NetdExecutor.initGmsChain` 和各旧版 `GmsObserver` 方法均须独立探测，缺失时继续注册其他存在的方法；同时处理 `updateFrameworkGmsNetStatus(boolean)`，将限制参数置为 `false`。

根据 [#31 的补丁](https://github.com/Howard20181/HyperOS_FCM_Live/pull/31/files)，Greeze 的 UID 查询助手和限制触发方法应为可选，连接广播可沿用被调用进程名作为后备。ListAppsManager 兼容新旧字段名，并对其白名单方法进行去优化。DomesticPolicyManager 仅放行 FCM/GCM 相关 action；ProcessPolicy 返回不可修改列表时，应复制后补充 Google Play 服务条目，避免修改原列表。

移植时保留本分支的 `hookE` 注册身份、热重载处理，以及手动与自动 FCM 放行策略。系统接口回归测试直接执行 Hooker 的注册与回调，使用新旧 ROM 方法签名作为输入。

## 验证范围

[#31 的真机报告](https://github.com/Howard20181/HyperOS_FCM_Live/pull/31) 针对特定 HyperOS 4 / Android 17、PowerKeeper 4.2.00 固件；其作者说明了额外后台策略模块和缺少长时间深度待机 A/B 测试的限制。该报告可作为接口变化的来源，不等同于本分支已完成相同真机测试。本分支的 CI 验证范围是构建、签名、注册接口及回调行为。
