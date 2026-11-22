# Magpie Mobile Git 工作流与发布规范

本规范自 2026-11-22 起生效。Android 和 iOS 共用一个长期主干 `main`。在单人维护、AI 承担主要开发与验证的模式下，AI 负责交付可验收、可追溯、可回退的改动，并在任务明确授权的范围内集成。需要并行、交接或等待验收时使用短分支，需要协作或合入前的托管验证时使用 PR。远程推送、合并和公开发布遵循[仓库工作约定](../AGENTS.md#验证与交付)。

## 从工程目标选择流程

| 必须解决的问题 | 移动端的选择及理由 |
| --- | --- |
| 下一位接手的 Agent 怎样知道为什么改、测试过什么？ | 一个目标形成一个可独立回退的提交，把行为变化和验证结果留在 Git 历史。即使聊天已结束、分支已清理，仍可从提交查到依据。 |
| 同时工作的 Agent 怎样避免覆盖彼此？ | 并行会话使用独立 worktree，隔离文件和暂存区。分支保存需要继续开发的提交，推送前按最新主干核对差异。 |
| 怎样知道一次改动可以进入主干？ | 先确定用户操作和预期结果，再选择能发现这类错误的检查。AI 可重复执行的检查由 AI 完成。一次人工批准本身无法验证分页有没有丢数据。 |
| 出错后谁会受影响？ | 主干回归可用新提交修复或回退。已分发的安装包需要新版本和渠道操作，因此源码集成和公开发布分别授权、分别验收。 |

## 上游 Magpie 的规则与实践

以下以 2026-11-22 核对的上游提交 `b168277` 为基准。公开记录可以说明它怎样工作，维护者选择这种方式的动机属于推断。

- **主干和贡献入口。** 上游 README 将创建 PR 限于维护者。历史上有从功能分支合入 `main` 的 PR，例如 #523。北京时间 11 月 20 日至 22 日抽取的最近 100 条主干活动，GitHub 均标记为 `push`，其中 `b168277` 的关联 PR 列表为空。当时生效的分支规则列出了禁止删除和非快进推送。由此可见，维护者直接更新主干与使用 PR 这两条路径都存在。[贡献说明](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/README.md#L301-L305) · [PR #523](https://github.com/yetone/magpie/pull/523) · [主干活动](https://github.com/yetone/magpie/activity?ref=main) · [该提交关联的 PR](https://api.github.com/repos/yetone/magpie/commits/b168277b8f525a339cacc1ced1f14c4f9c89b593/pulls) · [生效规则](https://api.github.com/repos/yetone/magpie/rules/branches/main)
- **提交也是验收记录。** 上游要求一个 commit 对应一个目标，标题说明影响区域和可观察行为，正文写明复现来源、怎样验证以及撤去修复后哪个测试会失败。`b168277` 修复了技能调用统计，正文同时留下了测试失败项，并说明哪一种客户端没有实测。记录这些边界，后续 Agent 才能判断一项结论可以沿用到哪里。[提交规范](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/docs/code-standards.md#commits) · [真实提交](https://github.com/yetone/magpie/commit/b168277b8f525a339cacc1ced1f14c4f9c89b593)
- **隔离来自实际事故。** 上游记录过两个会话共用暂存区、提交标题和文件对不上，以及旧工作树覆盖主干新代码的事故。后来形成了独立 worktree、对照最新基线、提交后检查 `git show --stat HEAD` 的约定。反复出现的缺陷也会写成回归测试或调整 CI。例如连续推送曾反复取消主干测试，后续配置让已经开始的主干检查跑完，PR 上过时的检查仍可取消。[工作区事故与约定](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/docs/code-standards.md#L247-L266) · [工程复盘](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/LESSONS.md#red-tests-and-releases) · [测试工作流](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/.github/workflows/test.yml#L3-L16)
- **评审与发布各有可检查的结果。** 评审从子系统契约追到实际调用路径，在待合入的代码上运行测试，PR 头提交发生变化就重新核对。发布由版本标签触发，独立的发布仓库负责签名和分发。上游规范把绿色 CI、标签归属、产物齐全和 Latest 状态列为发布验收条件。[子系统评审](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/docs/subsystems/README.md#review-a-change) · [合并与发布验收](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/docs/code-standards.md#L126-L154) · [发布工作流](https://github.com/yetone/magpie/blob/b168277b8f525a339cacc1ced1f14c4f9c89b593/.github/workflows/release.yml#L1-L19)

AI 能在指定提交上复现问题、运行检查并留下证据时，同一位维护者再批准一次所增加的信息有限。如果两种行为都能通过现有测试，例如权限不足时应隐藏入口还是显示原因，就需要维护者决定预期行为，再把决定写进契约和测试。移动端适合沿用上游的提交与验证方式，具体用例取自两个原生客户端、共享层契约和实际分发渠道。

## 分支与合并

| 任务情形 | 集成方式 |
| --- | --- |
| 目标清楚、适用验收已完成、已获集成与推送授权 | 基于最新 `origin/main` 准备提交，核对后以快进方式更新主干。用于当前任务的本地工作树也可以独立存在。 |
| 并行开发、跨会话交接或关键验收还需设备 | 在独立 worktree 开短分支，留下进度、证据和待验项。可用 `feat/*`、`fix/*`、`docs/*`、`chore/*`，Agent 分支加 `codex/` 前缀。具备集成条件后回到主干。 |
| 需要让托管 CI 在合入前验证、接受外部贡献、讨论有分歧的方案，或仓库规则要求 PR | 走 PR，在描述中写清需要回答的问题。单目标 PR 可 Squash 合并，保留验证摘要和贡献者署名。多个独立目标分开提交，以便分别回退。 |

1. 开工先查 `git status --short --branch`、仓库边界和当前基线。已有改动先确认归属。每个并行会话只操作自己的 worktree、暂存区和日志。
2. 提交标题沿用移动端的 `类型(范围): 行为变化`，例如 `fix(ios): keep the first page when loading more fails`。正文写相关 Issue 或需求、原行为与新行为、实际运行的检查和结果、数据来源与待验证项。修复回归时，用旧实现复现失败，再用同一用例验证修复。简单文档提交可简记链接和渲染检查。
3. 提交后用 `git show --stat HEAD` 核对文件，并检查相对最新主干的完整差异。若远端已经前进，把本次提交移到新基线，复查受影响路径并重跑相关检查。多个 Agent 可以同时开发，集成按主干的实际顺序进行。快进被拒时重新取基线，保留他人的提交。
4. 授权按任务理解。例如维护者明确要求“完成该修复，验证通过后集成并推送到 main”，已覆盖该任务的合并与推送，无需逐个 commit 重复确认。只委托调研或实现时，AI 交付相应的文档、代码和本地验证。需求有产品取舍、必须由持有人操作设备、或要公开分发时，只将待决定或待操作的具体事项交给维护者。[仓库工作约定](../AGENTS.md#验证与交付)

## PR 验收与 GitHub 门禁

直接集成和 PR 使用同一套验收标准。先写清触发条件和预期结果，再按影响面选验证。使用 PR 时标明所测 SHA，代码或集成结果变化后重跑受影响检查。界面改动附脱敏截图，跨页面流程可附短录屏。契约变化把实现、验证和对应文档放进同一项改动，并标明核对过的主机版本、字段和错误响应。

| 改动范围 | 集成前的证据 |
| --- | --- |
| 仅文档 | 渲染改动页，运行 `python3 .github/scripts/check_docs.py` 和 `git diff --check`。 |
| Android | 使用 Gradle Wrapper 构建 Debug、运行适用单元测试，交互改动在模拟器或真机走受影响流程。 |
| iOS | 涉及共享层时先生成匹配此提交的 MagpieShared XCFramework，再用 `magpie-ios` Scheme 构建并运行适用的 XCTest 或界面测试。[iOS 开发说明](../magpie-ios/README.md) |
| 两端、共享逻辑或主机契约 | 用同一验收场景覆盖共享规则和两个原生调用方。涉及真实连接、系统权限或主机数据的改动，按契约补双端真机联调，分别记录设备、系统、主机版本与结果。[验收用例](usage/acceptance.md) |
| CI、签名或打包 | 运行受影响的工作流或打包路径，发布前核对实际分发包。 |

**以受限密钥为例。** 网关对概览返回 `403 gateway_key_held`，仍允许读取该密钥自己的请求。共享测试应能抓出“概览拒绝导致明细也不可用”的回归，原生端要验证用户仍能打开明细。受控响应可以验证这条逻辑，真机访问还涉及系统授权、证书和网关。提交正文分别说明覆盖了哪一层。[共享回归测试](../shared/src/commonTest/kotlin/com/andychen/magpie/shared/usage/UsageControllerTest.kt) · [iOS 桥接测试](../magpie-ios/MagpieUsageTests/UsagePlatformTests.swift) · [功能契约](usage/README.md)

**当前能力（2026-11-22）。** 移动端 `main` 的工作流在 PR 和主干推送上检查文档，构建 Android Debug、Release，运行 Android 与共享模块测试，并在 Android 11 模拟器上验证启动。iOS 由本地 XCTest 提供证据。PR #5 为提交 `1475ca3` 记录了 15 个模拟器测试通过和未签名设备构建通过，同时列出待验证的真机安装与真实网关连接。合入主干的 `282abf3` 正文只概述了实现，后来从 Git 历史追查的人还要回到 PR 找验收记录。这正是 Squash 时应保留验证摘要的理由。[移动端 CI](../.github/workflows/checks.yml) · [iOS PR #5](https://github.com/AndyChen1024/magpie-mobile/pull/5) · [主干提交](https://github.com/AndyChen1024/magpie-mobile/commit/282abf34bd9fd761c347b8d5af43a56ef02c3c1e)

优先补齐的自动化是让 macOS runner 从同一提交构建共享 XCFramework，再运行现有 iOS Scheme，覆盖 Swift 桥接和界面测试。这样涉及共享层的改动就能得到双端反馈，设备持有人可以把时间留给局域网授权、证书信任等确实需要真机的环节。[iOS 测试说明](../magpie-ios/README.md#测试)

同日查询 GitHub，移动端 `main` 显示 `protected: false`，生效的分支规则列表为空。建议保护主干免于强推和删除。直接推送触发的 CI 在集成后报告结果。需要 CI 预先拦截的任务走 PR。AI 在已授权的集成任务中跟进对应提交的检查，失败时定位本次回归，修复或以新提交回退，并重新验证。关键检查未完成的提交暂缓发版。[主干状态](https://api.github.com/repos/AndyChen1024/magpie-mobile/branches/main) · [分支规则](https://api.github.com/repos/AndyChen1024/magpie-mobile/rules/branches/main)

## 版本与发布

- **版本标识。** 正式标签用 `vX.Y.Z`，在整个仓库内唯一。`0.Y.Z` 阶段用 `Y` 表示功能、`Z` 表示修复，在发布说明中写明兼容性变化。从 `1.0.0` 起，`X` 用于不兼容变化，`Y` 用于向后兼容的功能，`Z` 用于向后兼容的修复。双端同批发布时使用同一个标签和用户可见版本号，对应 Android `versionName` 和 iOS `MARKETING_VERSION`。每个平台分别维护递增的上传构建编号，对应 Android `versionCode` 和 iOS `CURRENT_PROJECT_VERSION`。单端补丁在发布记录标明发布平台和另一端当前的发布状态，下一次联合发布再统一版本号。
- **选定候选提交。** 按上述集成流程把本次发布平台的版本号和构建编号写入配置，从 `main` 中选定一个已通过适用检查的提交 SHA。记录该 SHA、平台、构建编号与目标渠道，用该提交的候选包验证安装、启动及受影响的核心流程。若产品用到了真实主机，记录经过验证的 Magpie 版本。
- **标记与分发。** 维护者批准后在通过验收的同一 SHA 上创建带注释的正式标签，按授权发布到相应渠道。优先使用已验收的候选包。若重新打包，复核实际分发包的版本、构建编号、签名和启动。发布记录包含标签、SHA、平台、构建编号、用户能感知的改动、已知限制和渠道状态。Android 和 iOS 的提交、审核和实际上线分别登记。
- **发布后修复。** 已公开标签保持固定。需要交付代码修复时，发布新补丁版本并递增构建编号，必要时先暂停仍在进行的分发。记录故障范围与修复所对应的标签。

### 已发布版本热修

如果 `main` 上的改动都可随补丁发布，修复进入主干后发补丁。如果需要单独维护旧版，就从该平台已发布的标签建立临时 `release/X.Y.x`，在该基线上修复并执行同样的候选包验收。使用仓库内尚未占用、且高于该平台当前发布版本的补丁号。主干尚缺同一修复时，按常规流程将修复回流并验证，注明原补丁提交与版本标签。发布完成且修复已回流后可清理维护分支。
