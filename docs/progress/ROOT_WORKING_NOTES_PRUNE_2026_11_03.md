# 根目录工作笔记移出仓库（2026-11-03）

2026-11-03。起因：门面精简后按"无关紧要的文档不上传仓库"复查全仓文档。本阶段只做判定与一次移除，未改动任何被 CI 校验的生成物，未删除任何功能四件套文档，也未改动契约、迁移或接口。

## 扫描方法

两遍判定：先统计 markdown 入链，再对所有零入链文件回查脚本、构建与约定引用。只做入链一遍会把 Postman 集合、被脚本读取的 JSON 误判成垃圾。

- 跟踪文件 2675 个，其中 `docs/` 1157 个 / 19.2 MB；md 843、json 271。
- `.cache/`、`node_modules/`、`dist/`、`target/`、`build/` 的跟踪数均为 0，没有该忽略而漏传的目录。

## 本次移除

根目录 `findings.md`（152 KB / 964 行）、`progress.md`（141 KB / 1009 行）、`task_plan.md`（129 KB / 1114 行），合计 422 KB / 3087 行。

判定依据：

- 是追加式工作笔记：以 `# 2026-10-26 合并后核查` 一类阶段标题分段，含未勾选复选框，记录当时的判断过程，不是当前功能或接口清单。
- 没有任何 markdown 链接指向它们；全仓仅 2 处正文提及。
- 其结论已有成文版本：`CURRENT_STATUS.md` 记现状与缺口，`README_HISTORY_ARCHIVE.md` 记历史流水。
- 它们位于仓库根目录，是访客打开仓库看到的第一屏文件名，但三个名字对读者没有指向性。

处置：`git rm --cached` 从版本库移除，本地文件保留并写入 `.git/info/exclude`（仅本机生效，不影响其他克隆）。移除后仓库不再包含这三份文件。

## 保留判断（看起来可删但不删）

| 对象 | 保留原因 |
| --- | --- |
| `docs/api/openapi.json`、`docs/testing/postman/`、`docs/sql/init.sql`、`docs/sql/migrations/V001__baseline.sql`、`docs/progress/S0_TRACEABILITY.csv` | CI 两处 `git diff --exit-code` 校验重新生成结果，删改都会直接失败 |
| `docs/superpowers/` 规格 137 / 计划 96 | 目录无索引页、60 个文件零入链，但 `docs/api/*` 几乎每个契约都链接对应规格，删除即造成契约页断链，也违反每功能"规格/契约/运维/复现"四件套约定 |
| `docs/testing/evidence/2026-10-30/current-api-original-aggregation-failed.json` | 名字含 failed，但同目录 `README.md` 明确说明保留修复前原报告，属审计证据 |
| `docs/progress/history/` 5 份英文快照 | 已被 `README_HISTORY_ARCHIVE.md` 覆盖，仍属历史留痕且仅 68 KB |
| 根目录 `汽车健康管家平台分阶段开发计划.md` | 被 `docs/SPEC.md`、`docs/progress/CURRENT_STATUS.md`、`docs/progress/README.md` 引用 |
| 其余 80 个零入链文件（179 KB） | 体积不到 `docs/` 的 1%，其中 44 个是阶段计划，删除会削弱可追溯性 |

`汽车健康管家平台全栈开发交付文档.md` 本来就不在版本库内，`.git/info/exclude` 早已排除。

## 连带修改与遗留

- `docs/testing/LOCAL_R7_COMPLETION_ACCEPTANCE.md` 原有"检查脚本调用错误及修正见 `findings.md`"指向被移除文件，改为链接 `docs/progress/` 下 R7 各阶段记录，并保留"首跑失败与命令误用不计成功、以修复后重跑为准"这一实质约束。
- 根 `README.md` 第 862 行仍有一句"仓库根目录的 `findings.md`…是逐日记录"。该文件正由门面双语化 PR 整份重写，两份同时改必然冲突，故本阶段不改；重写后同一段落会随上一版长 README 落进 `README_FULL_ARCHIVE_2026-11-03.md`，届时在同一处补一句后记说明文件已移出即可。判断现状始终以 `CURRENT_STATUS.md` 与代码为准。
- 这三份笔记记录的部分 PR 编号、CI 快照与轮次细节不再随仓库发布；本地保留可供维护者查阅。

## 验证

- 排除生效后 `git status` 仅显示三处删除，本地文件仍在磁盘。
- 相对链接检查覆盖本次改动的文档，无新增断链。
- 未触碰 `.github/workflows/ci.yml` 校验的任何路径。
