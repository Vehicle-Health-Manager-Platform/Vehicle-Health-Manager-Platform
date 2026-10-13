# R4b2a 门店位置与直线距离规格

2026-10-13。承接 R4b1 和已通过六项 CI 的规划 PR #141。用户授权规划后实施；本单元完成位置维护、主动定位、可信来源的直线距离与排序，真机留 R9。

## 方案选择

采用独立门店位置记录和主动定位。复用旧 merchant.lng/lat 会将坐标系不明数据误作可信距离；外部地图供应商与路线规划需要新的外部条件，放入后续正式接入。位置记录不自动继承旧坐标，也不从门店地址或行政区中心生成坐标。

## 门店位置

V032 新增 merchant_location，按 merchant_id 唯一，保存 lng/lat、GCJ02 坐标系、MERCHANT_CONFIRMED 来源、版本、维护员工和更新时间。该来源表示店长明确确认，不能称平台核实或现场认证。只有本店有效店长可写，本店店员可读；不能在正文指定别的门店。复用 MERCHANT_STAFF_ENABLED 开关。

GET /api/merchant/location 返回 can_edit、location。location 为 null 或包含 lng/lat（普通小数字符串）、coordinate_system、source、version。PUT 正文恰好 lng、lat、coordinate_system、source：经纬度同时为范围内最多七位小数字符串，坐标系 GCJ02、来源 MERCHANT_CONFIRMED；清除时四字段同时 null。UUID 幂等键、每次重放复核有效身份、本店在营、员工角色；修改/清除与审计同事务。以门店行锁串行写入，版本单调递增；清除保留记录和版本但坐标为空，读取 location=null。旧 profile 经纬度仍作旧资料保存，不用于新距离。

## 车主查询

车主明确点击附近时调用微信 getLocation(type=gcj02)。精确位置只在当前页面内存中，隐藏、卸载、账号变动或恢复普通报价时销毁；不进 URL、导航参数、缓存、日志或审计。拒绝/取消/超时/接口不可用显示具体下一动作，继续手选区域及价格排序；不主动重复弹定位授权。

新增两个只读 POST，无幂等键及业务写入：

- /api/service/project/{id}/nearby-merchants：严格 JSON 包含 lng/lat 数字、coordinate_system=GCJ02、sort（distance_asc/price_asc/price_desc）、page、page_size、region_code（null 或六位字符串）；经纬度必须有限且在 [-180,180]/[-90,90]，分页沿用 1..1000000 / 1..100。有效车主、项目、在售报价和上线门店都需复核。响应为既有报价分页，每行增加 distance_meters 与 location_source；只有有效位置才给非负整数距离及 MERCHANT_CONFIRMED，否则均 null。
- /api/service/merchant/{id}/distance：严格 JSON 只有 lng/lat/coordinate_system；有效车主及上线门店，响应 merchant_id、distance_meters、location_source。详情页重新主动定位，坐标不跨页面传递。

距离使用球面 Haversine（半径 6371008.8 米），四舍五入整数米。列表与详情共用 SQL 表达式与来源条件。距离排序按已知距离升序、未知最后、报价 ID 升序；价格排序按金额/报价 ID，仍展示距离。区域筛选复用精确区划码，缺坐标门店保留并标明未知。不承诺路线、驾车时长或门店坐标已实地验证。分页同一查询内快照一致，门店/报价变化后刷新最新结果，不声称跨请求固定快照。

全部响应 no-store；无登录 401、非对应角色 403、非法 JSON/坐标/查询 400、停用对象 404、未配置/数据库故障 503。不返回门店原坐标、联系电话、人员或客户信息。旧价格报价和门店详情 GET 响应保持兼容。

## 验收

真实 MySQL 验证迁移重放、旧坐标隔离、已知/未知与同点距离、稳定分页/价格/区域排序、清除/重放/停用拒绝和审计失败回滚。HTTP 验证角色、重复字段、异常 JSON、坐标范围、未知参数、单层信封/no-store。微信测试覆盖定位失败/取消、迟到回调、切排序/区域/账号/隐藏、定位超时、响应白名单与脱敏；微信/H5 构建。原生系统授权、定位精度和真机流程保留 R9 独立验收。
