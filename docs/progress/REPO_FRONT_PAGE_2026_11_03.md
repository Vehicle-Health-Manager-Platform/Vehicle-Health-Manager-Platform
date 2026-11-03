# 门面双语化：英文首页 + 简体中文对照

日期：2026-11-03｜分支：`docs/repo-readme-and-community-health`｜基线：`main` `92ed017`

## 背景与一次返工

本轮最初按"从零重建门面"推进，做到一半发现 `main` 已被另外三个提交（`4cae9c8` 重写 README 并新增治理文件、`d1a8c5f` 依赖升级、`92ed017` 调整徽章）推到了一个完整的中文门面：
README 872 行、`CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、`LICENSE`（MIT / `Vehicle-Health-Manager-Platform` / 2026）、Issue 与 PR 模板、旧 README 已归档到 `docs/progress/README_HISTORY_ARCHIVE.md`。

对照后判断：治理文件、许可、归档这几项**已经具备**，我重建的版本属于重复劳动，且会覆盖掉旁边更好的中文表述。因此放弃原分支内容，重置到 `92ed017` 后只保留 `main` 真正缺的部分。

## main 真正缺的两点

1. **英文首页**。用户要求"以英文版为首页，然后还要简体中文的"，而 `main` 只有纯中文 README。
2. **动态运作流程图**。`main` 的 README 里 5 张图全是 Mermaid，GitHub 渲染为静态 SVG，没有动画。

## 本次改动

| 动作 | 文件 |
| --- | --- |
| `README.md` 改为英文首页，与中文版章节一一对应（11 节、5 张 Mermaid 图全部对应翻译） | `README.md` |
| 原中文 README 原样改为对照版，正文未改写 | `README.zh-CN.md`（`git mv` 自 `README.md`） |
| 顶部加语言切换：`English \| 简体中文` | 两份 README |
| 新增七段主链路的动画 SVG（CSS 虚线流动 + SMIL 数据包位移 + 节点光晕波次，深浅色自适应） | `docs/assets/flow-main.svg` |
| 中文版新增 `1.5 主链路总览（动态图）`，英文版对应 `2.6` | 两份 README |
| 修正中文版 3.2 关于图示的结论 | `README.zh-CN.md` |
| 新增相对链接检查器 | `scripts/check_markdown_links.py` |

### 关于 3.2 那处修正

原文结论是「确实需要动画时只能放仓库内的 GIF」。这个结论只否定了"Mermaid 动画"，没有覆盖第三条路：**仓库内 SVG + `<img>` 引用**，动画写在该 SVG 自己的 CSS/SMIL 里，不依赖 README 的脚本，因此不受 GitHub 过滤 `<script>` 的影响。本次把该段改为陈述三条路径的取舍，而不是断言 GIF 是唯一解；同时明确写清"在不支持动画的渲染器中退化为完整静态图"，避免把未实测的效果说成必然。

## 验证记录

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 双语 README 相对链接 | `python scripts/check_markdown_links.py README.md README.zh-CN.md` | 0 断链 |
| Mermaid 语法（两版共 10 块） | mermaid v11 `parse()` 逐块 | 10/10 通过 |
| SVG 结构 | `ElementTree.parse` | 通过 |
| SVG 渲染 | Edge headless 截图，浅色/深色各一次 | 7 张卡片无裁切，强调色与顶部条一致 |

## 未验证 / 已知边界

- **SVG 动画在 GitHub 网页端的实际播放未实测。** 本地用无头浏览器做过尝试，但 `--virtual-time-budget` 会快进虚拟时钟，无法得到可信的逐帧证据（对照实验中"关掉动画"的样本同样随时间变化，说明那是渲染抖动而非动画），因此没有把它写成"已验证"。
- 跨文中锚点（如 `#15-主链路总览动态图`、`#26-main-flow-at-a-glance-animated`）按 GitHub 的标题锚点规则推导，未在线上跳转验证。
- 英文版是中文版的对等翻译，不是另一套事实来源；**若两者将来出现分歧，以中文版与代码为准**，并应立刻修正，而不是让两份各说各话。
