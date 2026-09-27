# 项目文档索引与修订约定

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-21 | 初版：确立修订约定与文档索引 |
| 第 2 次 | 2026-09-21 | 同步各文档的最新修订号；`WritePathVerifier` 状态更新为「开发中·已验证第 1 步」 |
| 第 3 次 | 2026-09-22 | 新增根目录 `AGENTS.md` 到索引（SQL 书写规范与项目约定的权威出处）；同步 `orm-layer.md` 至第 7 次 |
| 第 4 次 | 2026-09-22 | 同步 `orm-layer.md` 至第 8 次、`service-layer.md` 至第 4 次；新增 `SampleDataInitializer` 到开发期临时类清单 |
| 第 5 次 | 2026-09-22 | 同步 `orm-layer.md` 至第 9 次、`service-layer.md` 至第 5 次；新增 `WordMarkVerifier`、`TransactionRollbackProbe` 到开发期临时类清单 |
| 第 6 次 | 2026-09-22 | 同步 `orm-layer.md` 至第 10 次、`service-layer.md` 至第 6 次、`backlog.md` 至第 2 次；新增 `AnswerVerifier` 到临时类清单 |
| 第 7 次 | 2026-09-26 | 同步 `orm-layer.md` 至第 11 次、`service-layer.md` 至第 7 次；新增 `EndToEndVerifier` 到临时类清单 |
| 第 8 次 | 2026-09-26 | 同步 `orm-layer.md` 至第 12 次、`service-layer.md` 至第 8 次、`backlog.md` 至第 3 次；新增 `TranslationVerifier` |
| 第 9 次 | 2026-09-26 | 新增 `architecture-overview.md`（现状盘点、代码阅读路线、设计亮点、技术债）到索引 |
| 第 10 次 | 2026-09-27 | 新增 `api-layer.md`（API 层设计，第 1 次）到索引 |
| 第 11 次 | 2026-09-27 | 同步 `api-layer.md` 至第 2 次（springdoc 已验证 + 实测结果）、`backlog.md` 至第 4 次（新增 B-12） |
| 第 12 次 | 2026-09-27 | 同步 `api-layer.md` 至第 3 次（**接口全部完成，53 项冒烟测试通过**）、`service-layer.md` 至第 9 次、`backlog.md` 至第 5 次（新增 B-13 / B-14）、`../AGENTS.md` 至第 4 次（本次起为 AGENTS.md 建立修订记录表）；第三节新增 `tools/api-smoke.ps1` |
| 第 13 次 | 2026-09-27 | 新增 `idea-classpath-troubleshooting.md`（第 1 次）：记录「IDEA 模块 classpath 为空导致 100 个『程序包不存在』」这一真实故障的原因、排查方法、修复步骤与验收标准 |
| 第 14 次 | 2026-09-27 | 【纠正】同步各文档修订号：`backlog.md` 至第 6 次（新增 B-15 / B-16）、`api-layer.md` 至第 4 次（**纠正接口数 8→7**）、`../AGENTS.md` 至第 6 次（同上一处纠正）。上一版索引里 `backlog.md` 仍写第 5 次、`api-layer.md` 仍写第 3 次，是漏同步 |

---

## 一、文档索引

| 文档 | 内容 | 当前修订 |
|---|---|---|
| **`../AGENTS.md`** | **项目协作约定：SQL 书写规范、常用命令、易错点** —— 被 DSH 自动加载，是规范的权威出处 | 第 6 次 |
| **`architecture-overview.md`** | **项目现状与代码阅读指南**：已实现/待实现、从哪开始看代码、设计亮点、技术债 | 第 1 次 |
| `database-connection.md` | 数据库连接实现：依赖 → 配置 → 自动装配 → 验证 | 第 2 次 |
| `orm-layer.md` | ORM 层设计：选型理由、映射约定、实体清单、**SQL 书写规范**、日志实现 | 第 12 次 |
| `service-layer.md` | Service 层设计：职责划分、事务、upsert、词形归一化、实施进度 | 第 9 次 |
| `backlog.md` | 待办与待优化清单 | 第 6 次 |
| `api-layer.md` | API 层设计：接口清单与契约、统一响应体、**三条安全红线**、认证占位、实测结果 | 第 4 次 |
| **`idea-classpath-troubleshooting.md`** | **故障排查手册**：IDEA 报「程序包 XXX 不存在」的原因、排查方法、修复与验收 | 第 1 次 |

---

## 二、修订约定（必读）

### 2.1 每篇文档必须有「修订记录」表

位置：文档标题与正文之间。格式：

```markdown
## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-21 | 初版 |
| 第 2 次 | 2026-09-22 | 新增 X 章节；修正 Y 的错误结论 |
```

**规则**：
1. **每次改动文档都必须新增一行**
2. 变更内容要写**具体改了什么**，不写「优化了一下」「更新内容」这类模糊描述
3. **纠错必须显式写明"纠正"**，例如「纠正错误结论：`<<BLOB>>` 与 log-impl 无关」

### 2.2 本次修订新增/修改的章节要加行内标记

在该章节标题正下方加一行：

```markdown
### 3.4 词形归一化

> 🔖 **第 2 次修订新增**
```

或

```markdown
> 🔖 **第 2 次修订修改：补充了 XXX 的实测数据**
```

**规则**：只保留**最近 2 次**修订的行内标记。更早的标记应清理掉——
历史完整保留在「修订记录」表里，正文不必层层堆积。

> 说明：本约定从 `service-layer.md` 第 2 次修订起执行，**早于此的章节不追溯补标**。

### 2.3 其他写作要求

- 文档要能**独立阅读**，不依赖聊天记录
- 严格区分「**已实测验证**」与「**推测/未验证**」，前者附证据（日志、命令输出）
- 结论发生变化时，不要直接改写旧结论——**保留纠正痕迹**，写明原结论错在哪
- 数据类结论（行数、耗时、覆盖率）要标注**测量日期**

---

## 三、开发期临时类清单

以下类属于开发期辅助工具，功能稳定后应删除或加 `@Profile("dev")` 限制：

| 类 | 作用 | 状态 |
|---|---|---|
| `DatabaseConnectionChecker` | 数据库连通性自检 | 保留 |
| `MyBatisPlusCompatibilityChecker` | MyBatis-Plus 装配自检 | 保留 |
| `MyBatisPlusQueryChecker` | 查询链路自检 | 保留 |
| `EntityMappingChecker` | 实体 ↔ 表 映射自检 | 保留 |
| `InflectionProbeChecker` | 词形归一化方案验证 | 结论已确认，**可删** |
| `WritePathVerifier` | 写入链路验证 · 阶段 1（单表写入 7 项）+ 阶段 2（跨表事务 4 项） | 保留 |
| `WordMarkVerifier` | 写入链路验证 · 阶段 3（划词标记 8 项，含事务回滚与生成列） | 保留 |
| `AnswerVerifier` | 写入链路验证 · 阶段 4（作答与判分 11 项） | 保留 |
| `EndToEndVerifier` | 写入链路验证 · 阶段 5（端到端主流程 9 项，含聚合重算纠偏） | 保留 |
| `TranslationVerifier` | 写入链路验证 · 阶段 6（翻译题链路 8 项，解决 B-11） | 保留 |
| `TransactionRollbackProbe` | 事务回滚探针：普通 try-catch 测不出回滚，需要事务边界内的抛异常 | 保留，事务行为确认后可删 |
| `SampleDataInitializer` | 示例数据（自编文章 + 题目 + 演示用户），幂等 | **题库抽取完成后应删除** |

### 开发期辅助脚本（不在应用进程内）

| 脚本 | 作用 | 备注 |
|---|---|---|
| `tools/ecdict_import.py` | ECDICT 词库导入 `word` 表 | 已导入 36884 词 |
| `tools/api-smoke.ps1` | **API 全链路冒烟测试（53 项断言）** | 第 12 次修订新增。会临时创建两个测试用户并在结束时删除；⚠️ **必须存为 UTF-8 with BOM**，否则 PS 5.1 按 GBK 读会报 ParserError |

> ⚠️ **写自检类 / 测试脚本时的通用要求**：不要断言全局绝对值
> （「该用户的 `mark_count` 必须是 1」），那等于假设没有别人写同一个用户的数据，
> 手工测一次就会误报。应断言**自己造成的增量**，或把结果过滤到自己创建的 session/用户上，
> 并在结束时清理干净。踩坑记录见 `docs/api-layer.md` 9.7。
