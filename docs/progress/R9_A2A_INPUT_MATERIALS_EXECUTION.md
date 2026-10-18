# R9-A1/A2a 执行记录

本阶段已上传[PR157](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/157)，包含代码、测试和相关文档，base依赖PR156。最终891项小程序测试及微信/H5通过，远程六项CI以当前head为准，尚未全部结束，保持草稿。

2026-10-18。按用户“规划后实施、每阶段上传全部代码和文档”的要求，先将本会话已完成的三角色预览上传PR156，再建立 `codex/r9-a2a-local-input-materials` 阶段分支。前序未合并时本阶段PR以预览分支为base，不把上传推定为合并或部署。

## 已完成

1. A1：冻结[详细设计](../superpowers/specs/2026-10-18-local-input-materials-design.md)与[执行清单](../superpowers/plans/2026-10-18-local-input-materials.md)，明确本机采集与A2b上传、B2真实识别的边界；补UI历史状态提示。
2. A2a：实现原生能力装配、本机材料状态流、共享组件、AI和档案页接入；材料不串入原有服务端正文，不扩大文件上传权限。
3. 完成[验收](../testing/LOCAL_A2A_INPUT_MATERIALS_ACCEPTANCE.md)：891项前端、微信/H5/预览构建及模拟器实际页面截图；阶段代码、测试、设计、API边界、使用及进度文档同批交付。

## 测试发现与处理

- 首轮测试夹具把PDF默认结果误设成图片，并把缺失size写成了默认1024；修正夹具后检查真实大小读取与PDF路径，不放宽实现校验。
- 自检增加录音终止事件去重，防已排队的旧回调重复加入材料；增加回归测试。
- 本机文件只接受原生本地路径，禁止远程地址或UNC；试听销毁与设置返回同样复核当前上下文。

## 下一步

A2a仅本机采集完成，整A2尚未销项。下一A2b受控媒体上传：先定义音频/PDF服务端验证、扫描、归属、用途授权、幂等与签发，再接页面；不把本机文件声明当安全依据。最终CI以本阶段PR当前head为准，未完成的后端和Compose任务不提前写成功。
