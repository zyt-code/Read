# 贡献指南

感谢你对 Read 项目的兴趣！本指南介绍如何参与开发：从提 Issue、提交代码到通过 PR 审查的全流程。

---

## 目录

- [行为准则](#行为准则)
- [报告问题](#报告问题)
- [分支策略](#分支策略)
- [代码风格](#代码风格)
- [PR 流程](#pr-流程)
- [PR 自检清单](#pr-自检清单)
- [代码评审](#代码评审)

---

## 行为准则

- 保持友善、专业、尊重他人。
- 讨论聚焦在代码与设计，避免人身攻击。
- 中文 / 英文皆可，但项目的注释与文档以**中文为主**（见 [代码风格](#代码风格)）。

---

## 报告问题

提交 Issue 前请先在仓库内搜索是否已有相关讨论。

### Issue 模板

#### Bug

```
**环境信息**
- 设备：例如 Pixel 6 Pro
- Android 版本：例如 14
- App 版本：例如 1.0 (versionCode 1)

**重现步骤**
1. ...
2. ...

**期望行为**
描述你预期会发生什么。

**实际行为**
描述实际发生了什么，附上日志、截图、视频。

**EPUB 文件特征**（如适用）
- 文件大小、章节数、是否含中文、来源（如 Project Gutenberg、自制等）
- 如能脱敏分享 EPUB，有助于快速定位

**额外信息**
任何你认为可能相关的内容。
```

#### Feature

```
**需求场景**
你在什么场景下需要这个功能？

**期望方案**
你希望应用如何工作？

**替代方案**
是否考虑过其他实现方式？

**相关参考**
其他 EPUB 阅读器是否提供类似功能？给出截图或链接。
```

---

## 分支策略

- **`main`**：主分支，永远保持可构建、可发布的状态。
- **`feature/<short-name>`**：新功能开发分支，从 `main` 切出。
- **`fix/<short-name>`**：Bug 修复分支。
- **`refactor/<short-name>`**：重构分支。
- **`docs/<short-name>`**：文档分支。

命名示例：

```
feature/bookmarks-manager
fix/import-progress-stuck-at-70
refactor/repository-interface-no-uri
docs/architecture-data-flow
```

---

## 代码风格

完整规范见 [docs/development.md#编码规范](docs/development.md#编码规范)，要点摘录：

### 中文业务注释（强制）

> CLAUDE.md 要求："每段代码都要有完整的中文业务逻辑注释"

- **类级 KDoc**：说明类的职责、设计动机。
- **方法级 KDoc**：说明"为什么"、参数含义、副作用、抛出的异常。
- **关键代码段**：复杂业务步骤用 `// Step N:` 标注。
- **风险点**：版本差异、线程安全、SQLite 限制等必须显式标注。

反例：

```kotlin
// 设置字体
fontSize = 18f
```

正例：

```kotlin
// 默认字号 18sp，对应 Material 3 BodyLarge 推荐值，在 5.5-6.7 寸屏上
// CJK 单行可容纳约 18-22 字，长文阅读不易疲劳
fontSize = 18f
```

### Kotlin 风格

- 4 空格缩进，行宽 ≤ 120。
- 不写通配 import。
- 优先 `val`，需要可变时才用 `var`。
- 优先 `data class`，需要继承时才考虑 `open class`。
- 公开 API 用 `internal` / `private` 收紧可见性。
- 不要在 `data class` 中使用 `ByteArray` 而依赖 `equals`（参考 `EpubParser.ParseResult` 的已知坑）。

### 架构红线

- 领域层（`domain/`）禁止 `import android.*` / `androidx.room.*`。
- ViewModel 只能依赖 `BookRepository` 接口，不得直接接触 `BookDao` 或 `BookRepositoryImpl`。
- Compose Screen 不得直接调用 Repository，必须经过 ViewModel。
- 不要在 Repository 中返回 `BookEntity`，统一映射为 `Book`。

### Compose

- 状态尽量上提到 ViewModel；只有纯 UI 临时状态可用 `remember`。
- 列表使用稳定 key：`items(books, key = { it.id })`。
- `Modifier` 参数放在最后，默认值 `Modifier`。
- WebView 等命令式视图用 `AndroidView` 嵌入，并在 `DisposableEffect` 中清理。

### Room

- 修改 `BookEntity` 必须：递增 `AppDatabase.version` → 编写 Migration → 在 `AppModule` 注册 → 提交新的 schema JSON → 编写迁移测试。详情参考 [docs/development.md#room-修改流程](docs/development.md#room-修改流程)。

---

## PR 流程

### 1. Fork & Clone

```bash
# Fork 仓库后
git clone <your-fork-url>
cd read
git remote add upstream <upstream-url>
```

### 2. 同步主分支

```bash
git fetch upstream
git checkout main
git merge upstream/main
```

### 3. 创建特性分支

```bash
git checkout -b feature/your-feature-name
```

### 4. 提交代码

遵循 [docs/development.md#提交信息约定](docs/development.md#提交信息约定)：

```
feat(reader): 增加书签管理面板

支持长按段落添加书签，书签存储到 Room 新表 bookmarks，
通过 ModalBottomSheet 展示所有书签并支持跳转。

Closes #15
```

提交前请运行：

```bash
./gradlew lint test
```

### 5. 推送并提交 PR

```bash
git push origin feature/your-feature-name
```

在 GitHub / GitLab 上创建 PR，目标分支为 `main`，标题遵循提交信息约定。

### 6. PR 描述模板

```markdown
## 改动概述

简述这个 PR 做了什么、为什么需要。

## 改动范围

- [ ] 新增功能
- [ ] Bug 修复
- [ ] 重构
- [ ] 文档
- [ ] 测试

## 影响模块

列出影响的模块或文件，例如：
- `data/repository/BookRepositoryImpl.kt`
- `ui/reader/ReaderViewModel.kt`

## 测试

- [ ] 已添加单元测试（路径：）
- [ ] 已添加集成测试（路径：）
- [ ] 手动验证（描述测试场景）

## 截图 / 录屏

UI 改动请附图。

## 相关 Issue

Closes #N / Refs #N

## 自检清单

详见下方。
```

---

## PR 自检清单

提交 PR 前请逐项确认：

### 代码质量

- [ ] 代码遵循 [Kotlin 编码风格](docs/development.md#编码规范) 和 4 空格缩进
- [ ] 行宽 ≤ 120 字符
- [ ] 没有通配 import
- [ ] 所有新增 / 修改的类、方法都有**完整的中文 KDoc 注释**
- [ ] 关键业务步骤添加了行内中文注释
- [ ] 没有保留 `println` / `Log.d` 调试代码（保留必要的 `Log.w` / `Log.e`）
- [ ] 没有保留注释掉的死代码

### 架构

- [ ] 没有让 `domain/` 包依赖 `android.*` / `androidx.room.*`
- [ ] ViewModel 没有直接访问 `BookDao` 或 `BookRepositoryImpl`
- [ ] Compose Screen 没有直接调用 Repository
- [ ] Repository 接口未返回 `BookEntity`

### 数据库（如有改动）

- [ ] 递增了 `AppDatabase.version`
- [ ] 新增了 Migration 并在 `AppModule.provideDatabase` 注册
- [ ] 提交了新生成的 `app/schemas/.../{N}.json`
- [ ] 添加了迁移测试

### 测试

- [ ] `./gradlew test` 全部通过
- [ ] 新功能 / Bug 修复有对应测试
- [ ] 复杂流程有集成测试覆盖（如适用）

### 构建

- [ ] `./gradlew :app:assembleDebug` 成功
- [ ] `./gradlew lint` 没有新增警告（如有必须说明原因）

### 文档

- [ ] 影响外部读者的设计变更已更新到 `docs/`
- [ ] 新增公开 API 已在相关文档中说明
- [ ] 路线图条目已落实 / 移出 `README.md`

### 提交信息

- [ ] 标题遵循 `<type>(<scope>): <subject>` 格式
- [ ] 标题 ≤ 72 字符
- [ ] 描述说明了"为什么"，而不仅是"做了什么"

---

## 代码评审

### 评审者关注点

- **正确性**：逻辑是否正确？边界情况是否覆盖？
- **可读性**：是否容易理解？中文注释是否充分？
- **架构**：是否打破分层？是否引入不必要耦合？
- **性能**：是否有不必要的 IO / 计算？StateFlow 是否会过度发射？
- **可测试性**：新代码是否便于 mock / 单元测试？
- **风险**：是否有线程安全 / 内存泄漏 / 数据丢失风险？

### 作者回应

- 评审意见请逐条回应（同意 / 反驳 / 已修改）。
- 反驳请给出技术理由。
- 不同意见可以保留，最终由维护者裁定。

### 合并

- 通过评审后由维护者使用 **Squash & Merge** 合并到 `main`。
- Squash 后的提交标题取自 PR 标题，正文取自 PR 描述。

---

## 相关文档

- [README.md](README.md)：项目概览。
- [docs/development.md](docs/development.md)：环境搭建与调试。
- [docs/architecture.md](docs/architecture.md)：架构详解。
- [docs/testing.md](docs/testing.md)：测试策略。
- [docs/data-model.md](docs/data-model.md)：数据模型。
