# R4b2a 门店位置与车主直线距离

四个接口都要求当前有效角色、账号与资源归属，响应 no-store，不接受查询参数；正文严格拒绝重复键、尾随 JSON、多余字段。门店位置是店长确认，未作平台现场核实。

## 本店维护

GET `/api/merchant/location`：本店店长/店员可读，`data={can_edit,location}`。location 为 null 或恰好 `{lng,lat,coordinate_system,source,version}`；lng/lat 普通小数字符串，coordinate_system=GCJ02，source=MERCHANT_CONFIRMED，version 正整数。can_edit 仅店长为 true。

PUT 同路径，店长写且须 UUID `Idempotency-Key`；`MERCHANT_STAFF_ENABLED=true`。正文恰好 `{lng:"113.2644",lat:"23.1291",coordinate_system:"GCJ02",source:"MERCHANT_CONFIRMED"}`，允许最多七位小数，经度 -180..180、纬度 -90..90。清除时四字段全部 null。响应 data 恰好 `{location}`，不重复套信封。门店行锁串行更新、版本递增、幂等记录和成功审计同事务；重放先复核会话/员工/门店，停用不能靠旧提交键绕过权限。清除保留记录与版本，下一次读取 location=null。

V032 新表按 merchant_id 唯一；已有 merchant.lng/lat 不迁移，不自动猜坐标系。旧 profile 坐标变化不影响已明确确认的位置；地址或门店位置变化后，店长应在位置页重新确认/清除位置。迁移可重复执行，初始化基线44表加迁移至V032为72表。

## 附近报价

POST `/api/service/project/{id}/nearby-merchants` 是只读查询，不需要幂等键，也不产生位置/业务审计。正文恰好：

```json
{"lng":113.2644,"lat":23.1291,"coordinate_system":"GCJ02","sort":"distance_asc","page":1,"page_size":20,"region_code":null}
```

lng/lat 是有限数字，范围同上；sort 支持 distance_asc、price_asc、price_desc，page 1..1000000、page_size 1..100，region_code=null 或六位字符串。精确区域匹配复用 R4b1。返回 `data={items,total,page,page_size}`，items 继承旧报价七字段并增加 `distance_meters`（非负整数或 null）、`location_source`（MERCHANT_CONFIRMED 或 null）；未知时两者均 null。不返回门店坐标/联系电话/人员/客户信息。

球面 Haversine 半径6371008.8米，四舍五入到整数米。只使用明确来源的 GCJ02 位置。distance_asc 按已知距离升序、未知最后、报价ID升序；价格排序按当前版本报价金额及报价ID，仍展示距离。所有上线门店有效报价包含在内，没有坐标不从结果中删除。每个查询的计数/行共享读取事务；位置/报价更新后下一次刷新生效，跨请求分页不保证冻结快照。

## 门店详情距离

POST `/api/service/merchant/{id}/distance`，正文恰好 `{lng:113.2644,lat:23.1291,coordinate_system:"GCJ02"}`；有效车主和上线门店，data 恰好 `{merchant_id,distance_meters,location_source}`，与列表使用同一距离公式。详情独立主动定位，不从列表 URL/导航参数接收精确位置。

## 微信与错误边界

只在点击附近/详情距离时调用 `getLocation(type=gcj02)`，本地12秒超时，网络15秒超时。坐标不写本地缓存，账号变动、隐藏/离开及恢复普通报价时清除；迟到定位/网络响应不能恢复旧账号/页面的数据。授权失败、取消或超时可继续选择省市区和按价格查看。微信清单声明 scope.userLocation 与 requiredPrivateInfos；正式后台类目/API申请、隐私声明和真机精度仍按 R9 验收。参考 [微信 getLocation 官方文档](https://developers.weixin.qq.com/miniprogram/dev/api/location/wx.getLocation.html)，2026-10-13 使用 gstack 浏览核对。

400 参数错误、401 会话/账号失效、403 角色无权、404 停用/删除对象、503 未配置/开关关闭或数据库失败。只读 POST 精确位置不进入 URL、日志或写审计；网关与生产 APM 不应采集这两个正文，R9 部署时核验实际日志配置。定位和距离不代表驾车路线或预计到达时间。
