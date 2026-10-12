# R4b1 商家详情与区域选择交付

2026-10-12。后端[PR #137](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/137)，微信[PR #138](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/138)，本文件随独立收口PR上传，未自动合并。

已补车主商家详情最小投影、有效在售报价/最新版本分页、原项目/本店全部服务；微信原生区域选择及报价精确筛选、恢复全部、空态、排序与分页；登录返回、隐藏/账号/迟到保护，衔接既有预约版本/时段校验。旧未维护区域门店可在全部范围查看，不虚构位置/评分。

本地后端定向19/19、微信388/388及微信/H5兼容构建通过；原生详情及region绑定产物已核对。无新迁移，44/71表保持，OpenAPI156操作。最终最新六项CI、提交号和全量测试数写在对应PR验证正文。[接口](../api/MERCHANT_DISCOVERY.md)、[验收](../testing/LOCAL_MERCHANT_DISCOVERY_ACCEPTANCE.md)。

没有修改任何README或上传原始交付文档。堆叠#121→…→#136→#137→#138→收口，前支合main后改下一支base回main复核；本轮未合并或部署。

F06商家详情/手选区域代码缺口已补，但距离排序/公开评价汇总/AI总结仍欠，原生弹窗与真机交互未验收。下一步[R4b2位置与公开评价](R4B2_LOCATION_REVIEWS_NEXT_PLAN.md)，健康可信来源与评分规则、完整证件/五类提醒、券/邀请/积分、门店技师关联档案工作台和外部接入仍按总队列保留。
