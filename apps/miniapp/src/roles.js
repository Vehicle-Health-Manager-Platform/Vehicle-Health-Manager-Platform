export const roles = Object.freeze([
  {
    key: 'owner',
    tag: 'OWNER',
    title: '车主端',
    description: '车辆档案、服务预约、订单与 AI 管家',
    path: 'pages/owner/index',
  },
  {
    key: 'merchant',
    tag: 'MERCHANT',
    title: '商家端',
    description: '订单接车、派工、核销与经营管理',
    path: 'pages/merchant/index',
  },
  {
    key: 'technician',
    tag: 'TECHNICIAN',
    title: '技师端',
    description: '接单、施工拍照、配件与质检签字',
    path: 'pages/technician/index',
  },
])
