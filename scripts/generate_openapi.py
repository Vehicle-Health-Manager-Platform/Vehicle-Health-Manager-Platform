"""Generate the S0 OpenAPI draft from the tracked core and support contracts."""

import csv
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TRACE = ROOT / "docs/progress/S0_TRACEABILITY.csv"
CONTRACT = ROOT / "docs/api/S0_BUSINESS_CONTRACT.md"
OUTPUT = ROOT / "docs/api/openapi.json"


def operation(method, path, summary, features, protected=True):
    parameters = [
        {"name": name, "in": "path", "required": True,
         "schema": {"type": "string"}}
        for name in re.findall(r"\{([^}]+)\}", path)
    ]
    data = {
        "summary": summary,
        "operationId": re.sub(r"[^A-Za-z0-9]+", "_", f"{method}_{path}").strip("_"),
        "tags": [path.split("/")[2]],
        "x-feature-ids": features.split(",") if features else [],
        "parameters": parameters,
        "responses": {
            "200": {"description": "统一成功响应", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiResponse"}}}},
            "400": {"description": "参数或业务状态错误", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiResponse"}}}},
            "401": {"description": "认证失败，code=40100"},
            "403": {"description": "无权访问，code=40300"},
        },
        "security": [{"bearerAuth": []}] if protected else [],
    }
    if method == "post":
        # Auth endpoints currently do not consume the business idempotency header.
        if not path.startswith("/api/auth/"):
            data["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": protected,
                                       "schema": {"type": "string", "format": "uuid"}})
        data["requestBody"] = {"required": True, "content": {"application/json": {
            "schema": {"type": "object", "additionalProperties": True}}}}
    return data


def implemented_auth_operation(path, summary, role, protected, fields, features=""):
    data = operation("post", path, summary, features, protected)
    data["x-roles"] = role
    data["x-implementation-status"] = "core-implemented"
    data["responses"]["429"] = {"description": "认证请求受限，code=42900"}
    data["responses"]["503"] = {"description": "所需数据库或外部服务尚未配置，code=50300"}
    if fields:
        data["requestBody"]["content"]["application/json"]["schema"] = {
            "type": "object", "required": list(fields), "properties": {
                field: {"type": "string"} for field in fields}, "additionalProperties": False}
    else:
        data.pop("requestBody", None)
    return data


def main():
    document = {
        "openapi": "3.1.0",
        "info": {"title": "汽车健康管家平台 S0 API 草案", "version": "0.1.0",
                 "description": "43 个来源核心接口加 S0 支撑接口。未明确的业务字段以 S0_BUSINESS_CONTRACT.md 为准；未实现的路径不代表已可调用。"},
        "servers": [{"url": "http://127.0.0.1:8080"}],
        "paths": {},
        "components": {
            "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}},
            "schemas": {"ApiResponse": {"type": "object", "required": ["code", "message", "request_id"],
                "properties": {"code": {"type": "integer"}, "message": {"type": "string"},
                               "data": {}, "request_id": {"type": "string", "format": "uuid"}}}},
        },
    }
    with TRACE.open(encoding="utf-8-sig", newline="") as handle:
        core = [row for row in csv.DictReader(handle) if row["类型"] == "核心API"]
    if len(core) != 43:
        raise ValueError(f"Expected 43 core APIs, got {len(core)}")

    for row in core:
        method, path = row["接口"].split(" ", 1)
        path = re.sub(r":([A-Za-z_]+)", r"{\1}", path)
        protected = not path.startswith("/api/auth/")
        document["paths"].setdefault(path, {})[method.lower()] = operation(
            method.lower(), path, row["名称"], row["关联功能"], protected)

    contract = CONTRACT.read_text(encoding="utf-8")
    for method, path, role, summary in re.findall(
            r"^\| `(GET|POST|PUT|PATCH|DELETE) (/api/[^`]+)` \| ([^|]+) \| ([^|]+) \|",
            contract, re.M):
        protected = not (path.startswith("/api/auth/") or "/callback/" in path)
        document["paths"].setdefault(path, {}).setdefault(method.lower(),
            operation(method.lower(), path, summary.strip(), "", protected))
        document["paths"][path][method.lower()]["x-roles"] = role.strip()

    # These routes exist in the backend even though they are not all among the
    # original 43 core operations. Keep their auth boundary explicit here.
    for path, summary, role, protected, fields, features in (
        ("/api/auth/wx-login", "微信车主或技师登录", "车主/技师", False, ("code", "role"), "F01"),
        ("/api/auth/refresh", "轮换刷新凭证", "车主/商家/技师", False, ("refresh_token",), "F01"),
        ("/api/auth/technician/bind", "绑定技师员工码", "持有绑定凭证的技师", True, ("employee_code",), ""),
        ("/api/auth/phone/bind", "绑定车主微信手机号", "车主", True, ("code",), "F01"),
        ("/api/auth/logout", "撤销当前会话", "车主/商家/技师", True, (), ""),
        ("/api/auth/merchant/code", "请求商家登录短信码", "商家", False, ("account", "password"), ""),
        ("/api/auth/merchant/login", "商家密码与短信码登录", "商家", False, ("account", "password", "sms_code"), ""),
    ):
        document["paths"].setdefault(path, {})["post"] = implemented_auth_operation(
            path, summary, role, protected, fields, features)
    document["paths"]["/api/auth/merchant/code"]["post"]["x-external-dependency"] = (
        "生产短信发送器尚未配置，当前返回 503")

    for method, path, summary in (
        ("post", "/api/dev/token", "仅 local profile 可用的演示令牌"),
        ("get", "/api/demo/vehicles/{id}", "仅 local profile 可用的车辆归属校验样例"),
    ):
        document["paths"].setdefault(path, {})[method] = operation(
            method, path, summary, "F01,F02", path != "/api/dev/token")
    document["paths"]["/api/dev/token"]["post"]["x-local-only"] = True
    document["paths"]["/api/demo/vehicles/{id}"]["get"]["x-local-only"] = True
    mileage_path = "/api/demo/vehicles/{id}/mileage"
    mileage = operation("post", mileage_path, "仅 local profile：幂等车辆里程写入及成功变更审计", "")
    mileage["x-local-only"] = True
    mileage["x-implementation-status"] = "local-example"
    mileage["x-roles"] = "车主"
    mileage["description"] = "24 小时内同键同请求重放原成功结果，同键不同请求返回 40001。每次重放仍校验车辆归属。需配置测试数据库，不属于正式车辆业务。"
    mileage["requestBody"]["content"]["application/json"]["schema"] = {
        "type": "object", "required": ["current_mileage"], "additionalProperties": False,
        "properties": {"current_mileage": {"type": "integer", "minimum": 0, "maximum": 2147483647}}}
    mileage["responses"]["404"] = {"description": "车辆不存在，code=40400"}
    mileage["responses"]["503"] = {"description": "数据库未配置或事务失败，code=50300；使用原幂等键重试"}
    document["paths"][mileage_path] = {"post": mileage}

    upload = operation("post", "/api/file/upload", "正式车主：幂等私有图片上传", "F03")
    upload["x-roles"] = "车主正式会话"
    upload["x-implementation-status"] = "implemented"
    upload["parameters"].append({"in": "header", "name": "Idempotency-Key", "required": True,
        "schema": {"type": "string", "format": "uuid"}})
    upload["requestBody"] = {"required": True, "content": {"multipart/form-data": {"schema": {
        "type": "object", "required": ["file"], "additionalProperties": False,
        "properties": {"file": {"type": "string", "format": "binary"}}}}}}
    upload["description"] = "10 MiB 单图片、11 MiB 总请求；同键同内容重放，异内容或正在处理返回 409。外部对象副作用不属于数据库原子事务。详见 UPLOAD_HTTP.md。"
    for status, description in {"409": "同键异内容或处理中", "413": "文件或请求过大", "422": "病毒检查拒绝", "429": "入口限流或繁忙", "503": "依赖不可用或等待核对"}.items():
        upload["responses"][status] = {"description": description}
    document["paths"]["/api/file/upload"] = {"post": upload}
    access = operation("get", "/api/file/{id}/access", "正式车主：本人图片短时 GET 签名", "F03")
    access.pop("requestBody", None)
    access["x-roles"] = "车主正式会话"
    access["x-implementation-status"] = "implemented"
    access["description"] = "每次复核本人/未删除/CLEAN 状态和对象大小/MIME；响应 Cache-Control: no-store，默认签名 120 秒。已经签发的 URL 在 TTL 内仍可能有效。"
    for status, description in {"404": "文件不存在或非本人", "409": "文件尚不可读", "429": "入口限流", "503": "依赖或对象状态异常"}.items():
        access["responses"][status] = {"description": description}
    document["paths"]["/api/file/{id}/access"] = {"get": access}

    count = sum(len(item) for item in document["paths"].values())
    if count < 55:
        raise ValueError(f"Expected at least 55 operations; got {count}")
    # S1 owner vehicles: implemented subset of F02. OCR/VIN matching stay planned.
    for path in ("/api/vehicle/list", "/api/brand/list", "/api/series/list", "/api/model/list"):
        item = document["paths"][path]["get"]
        item["x-roles"] = "正式车主"
        item["x-implementation-status"] = "core-implemented"
        item["description"] = "有效 user/OWNER 会话；分页响应 list/page/page_size/total；车型仅查询现有有效数据库记录。详见 VEHICLE_MANUAL.md。"
        item["parameters"] += [
            {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
            {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
        ]
        parent = "brand_id" if path == "/api/series/list" else "series_id" if path == "/api/model/list" else None
        if parent:
            item["parameters"].append({"name": parent, "in": "query", "required": True,
                "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}})
        item["responses"]["503"] = {"description": "数据库尚未配置，code=50300"}
    item = document["paths"]["/api/vehicle/add"]["post"]
    item["x-roles"] = "正式车主"
    item["x-implementation-status"] = "manual-core-implemented"
    item["description"] = "本步仅 add_type=4 手动录入；同车主非空车牌/VIN重复返回409；24小时幂等与成功审计。详见 VEHICLE_MANUAL.md。"
    item["requestBody"]["content"]["application/json"]["schema"] = {
        "type": "object", "additionalProperties": False, "required": ["add_type", "model_id"], "properties": {
            "add_type": {"type": "integer", "const": 4},
            "model_id": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
            "current_mileage": {"type": "integer", "minimum": 0, "maximum": 2147483647, "default": 0},
            "plate_no": {"type": "string", "maxLength": 32, "description": "可选普通/新能源大陆号牌；去首尾空格并转大写"},
            "vin": {"type": "string", "maxLength": 32, "description": "可选17位VIN，不含I/O/Q；空串为缺省"},
        }}
    for status, message in (("404", "车型不存在或停用"), ("409", "本人已登记该车牌或VIN"), ("503", "数据库或事务暂不可用，同键重试")):
        item["responses"][status] = {"description": message}
    archive = document["paths"]["/api/archive/add"]["post"]
    archive["x-roles"] = "正式车主"
    archive["x-implementation-status"] = "manual-and-photo-core-implemented"
    archive["description"] = "本人车辆手动或拍照记录；拍照至少关联一张图片，提交时复核本人且CLEAN。24小时幂等与成功审计。详见 ARCHIVE_MANUAL.md。"
    archive["requestBody"]["content"]["application/json"]["schema"] = {
        "type": "object", "additionalProperties": False,
        "required": ["vehicle_id", "archive_type", "recorded_date", "title"],
        "allOf": [{"if": {"properties": {"input_type": {"const": 1}}, "required": ["input_type"]},
                   "then": {"required": ["file_ids"], "properties": {"file_ids": {"minItems": 1}}}}],
        "properties": {
            "vehicle_id": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
            "archive_type": {"type": "integer", "minimum": 1, "maximum": 7},
            "input_type": {"type": "integer", "enum": [1, 3], "default": 3,
                           "description": "1拍照，须有1–5张图片；3手动，可无图片；省略取3"},
            "recorded_date": {"type": "string", "format": "date"},
            "mileage": {"type": ["integer", "null"], "minimum": 0, "maximum": 2147483647},
            "title": {"type": "string", "minLength": 1, "maxLength": 80},
            "notes": {"type": "string", "maxLength": 1000},
            "file_ids": {"type": "array", "maxItems": 5, "uniqueItems": True,
                         "items": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}},
        }}
    for status, message in (("404", "本人车辆或图片不可用"), ("503", "数据库或事务暂不可用，同键重试")):
        archive["responses"][status] = {"description": message}
    archive_list = document["paths"]["/api/archive/list"]["get"]
    archive_list["x-roles"] = "正式车主"
    archive_list["x-implementation-status"] = "manual-and-photo-core-implemented"
    archive_list["description"] = "只列所选本人车辆的手动及拍照记录，每条带input_type，按发生日期与ID倒序；附件返回稳定file_ids，不返回签名URL。详见 ARCHIVE_MANUAL.md。"
    archive_list["parameters"] += [
        {"name": "vehicle_id", "in": "query", "required": True,
         "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}},
        {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
        {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
    ]
    archive_list["responses"]["404"] = {"description": "本人车辆不可用"}
    archive_list["responses"]["503"] = {"description": "数据库暂不可用"}
    project_fields = {
        "id": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
        "project_name": {"type": "string"}, "category": {"type": "integer", "minimum": 1, "maximum": 6},
        "base_price_low": {"type": "string", "pattern": r"^\d{1,8}\.\d{2}$"},
        "base_price_high": {"type": "string", "pattern": r"^\d{1,8}\.\d{2}$"},
    }
    schemas = document["components"]["schemas"]
    schemas["ServiceProject"] = {"type": "object", "required": list(project_fields), "properties": project_fields}
    detail_fields = {**project_fields, "service_content": {"type": "string"}, "quality_standard": {"type": ["string", "null"]}}
    schemas["ServiceProjectDetail"] = {"type": "object", "required": list(detail_fields), "properties": detail_fields}
    schemas["ServiceProjectPage"] = {"type": "object", "required": ["items", "total", "page", "page_size"], "properties": {
        "items": {"type": "array", "items": {"$ref": "#/components/schemas/ServiceProject"}},
        "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer", "minimum": 1},
        "page_size": {"type": "integer", "minimum": 1, "maximum": 100}}}
    for path, summary, result_schema in (
        ("/api/service/projects", "车主标准服务项目分类分页列表", "ServiceProjectPage"),
        ("/api/service/project/{id}", "车主标准服务项目详情", "ServiceProjectDetail"),
    ):
        item = operation("get", path, summary, "F06")
        item["x-roles"] = "正式车主"
        item["x-implementation-status"] = "catalog-core-implemented"
        item["description"] = "有效 user/OWNER 会话；只返回启用且未删除项目；价格为两位小数字符串。详见 SERVICE_CATALOG.md。"
        if path.endswith("projects"):
            item["parameters"] = [
                {"name": "category", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 6}},
                {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
                {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
            ]
        else:
            item["parameters"][0]["schema"] = project_fields["id"]
            item["responses"]["404"] = {"description": "项目不存在、停用或软删除，code=40400"}
        item["responses"]["503"] = {"description": "数据库未配置或暂不可用，code=50300"}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
            {"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result_schema}"}}},
        ]}
        document["paths"][path] = {"get": item}
    quote_fields = {
        "merchant_project_id": project_fields["id"], "version_id": project_fields["id"],
        "version": {"type": "integer", "minimum": 1},
        "price": {"type": "string", "pattern": r"^(?:0|[1-9][0-9]{0,7})\.[0-9]{2}$"},
    }
    schemas["MerchantQuote"] = {"type": "object", "required": list(quote_fields) + ["merchant_id", "merchant_name", "address"], "properties": {
        **quote_fields, "merchant_id": project_fields["id"], "merchant_name": {"type": "string"}, "address": {"type": "string"}}}
    schemas["OwnMerchantQuote"] = {"type": "object", "required": list(quote_fields) + ["standard_project_id", "project_name", "status", "available"], "properties": {
        **quote_fields, "standard_project_id": project_fields["id"], "project_name": {"type": "string"},
        "status": {"type": "integer", "enum": [0, 1]}, "available": {"type": "boolean"}}}
    schemas["SavedMerchantQuote"] = {"type": "object", "required": list(quote_fields) + ["status"], "properties": {
        **quote_fields, "status": {"type": "integer", "enum": [0, 1]}}}
    for name, row in (("MerchantQuotePage", "MerchantQuote"), ("OwnMerchantQuotePage", "OwnMerchantQuote")):
        schemas[name] = {**schemas["ServiceProjectPage"], "properties": {**schemas["ServiceProjectPage"]["properties"],
            "items": {"type": "array", "items": {"$ref": f"#/components/schemas/{row}"}}}}
    for method, path, summary, result, role in (
        ("get", "/api/service/project/{id}/merchants", "车主按价格查看商家报价", "MerchantQuotePage", "正式车主"),
        ("get", "/api/merchant/standard-projects", "商家可选标准项目", "ServiceProjectPage", "MERCHANT"),
        ("get", "/api/merchant/projects", "本店选品与当前报价", "OwnMerchantQuotePage", "MERCHANT"),
        ("post", "/api/merchant/projects", "保存本店报价与不可变版本", "SavedMerchantQuote", "MERCHANT"),
    ):
        item = operation(method, path, summary, "F06,F17")
        item["x-roles"] = role
        item["x-implementation-status"] = "merchant-quotes-core-implemented"
        item["description"] = "服务端校验有效会话与商家归属。金额为两位小数字符串，参考价仅供对照。详见 MERCHANT_QUOTES.md。"
        item["responses"]["404"] = {"description": "标准项目或报价不可用，code=40400"}
        item["responses"]["503"] = {"description": "数据库或事务暂不可用，code=50300"}
        if method == "get":
            item["parameters"] += document["paths"]["/api/service/projects"]["get"]["parameters"][-2:]
            if "{id}" in path:
                item["parameters"][0]["schema"] = project_fields["id"]
                item["parameters"].append({"name": "sort", "in": "query", "schema": {"type": "string", "enum": ["price_asc", "price_desc"], "default": "price_asc"}})
            if path.endswith("standard-projects"):
                item["parameters"].append(document["paths"]["/api/service/projects"]["get"]["parameters"][0])
        else:
            item["description"] += " 24小时幂等；报价、版本、成功审计同一事务。停用项目仅允许已有报价按原价下架。"
            item["requestBody"]["content"]["application/json"]["schema"] = {"type": "object", "additionalProperties": False,
                "required": ["standard_project_id", "price", "status"], "properties": {
                    "standard_project_id": project_fields["id"],
                    "price": {**quote_fields["price"], "description": "0.01–99999999.99元，禁止0.00、数字类型、指数和多余小数"},
                    "status": {"type": "integer", "enum": [0, 1]}}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
            {"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item
    identifier = project_fields["id"]
    timestamp = {"type": "string", "format": "date-time"}
    fulfillment_states = ["PENDING_PAYMENT", "PAID", "RECEIVED", "IN_SERVICE", "PENDING_VERIFY", "COMPLETED", "CLOSED", "DISPUTED"]
    fulfillment_actions = ["RECEIVE", "START_SERVICE", "FINISH_SERVICE", "COMPLETE"]
    slot_fields = {"slot_id": identifier, "standard_project_id": identifier, "starts_at": timestamp, "ends_at": timestamp,
        "capacity": {"type": "integer", "minimum": 1, "maximum": 100}, "capacity_left": {"type": "integer", "minimum": 0},
        "open": {"type": "boolean"}, "project_name": {"type": "string"}}
    schemas["ReservationSlot"] = {"type": "object", "required": list(slot_fields), "properties": slot_fields}
    for name, row in (("ReservationSlotPage", "ReservationSlot"), ("OwnerOrderPage", "OwnerOrder")):
        schemas[name] = {**schemas["ServiceProjectPage"], "properties": {**schemas["ServiceProjectPage"]["properties"], "items": {"type": "array", "items": {"$ref": f"#/components/schemas/{row}"}}}}
    order_fields = {"order_id": identifier, "order_no": {"type": "string"}, "amount_due": quote_fields["price"], "status": {"type": "string"},
        "expires_at": {"type": ["string", "null"], "format": "date-time"}, "created_at": timestamp,
        "closed_at": {"type": ["string", "null"], "format": "date-time"}, "close_reason": {"type": ["string", "null"]},
        "project_snapshot": {"type": ["object", "null"]}, "merchant_snapshot": {"type": ["object", "null"]}, "appointment_snapshot": {"type": ["object", "null"]}}
    schemas["OwnerOrder"] = {"type": "object", "required": list(order_fields), "properties": order_fields}
    schemas["OwnerOrderDetail"] = {"type": "object", "required": list(order_fields) + ["vehicle_id", "price_snapshot"], "properties": {**order_fields, "vehicle_id": identifier, "price_snapshot": {"type": ["object", "null"]}}}
    schemas["CreatedReservation"] = {"type": "object", "required": ["order_id", "order_no", "amount_due", "status", "expires_at"], "properties": {k: order_fields[k] for k in ["order_id", "order_no", "amount_due", "status", "expires_at"]}}
    context = {"merchant_project_id": identifier, "quote_version_id": identifier, "version": identifier, "merchant_id": identifier, "standard_project_id": identifier,
        "project_name": {"type": "string"}, "merchant_name": {"type": "string"}, "address": {"type": "string"}, "price": quote_fields["price"]}
    schemas["ReservationQuote"] = {"type": "object", "required": list(context), "properties": context}
    pagination = document["paths"]["/api/service/projects"]["get"]["parameters"][-2:]
    for method, path, title, role, result, fields in (
        ("get", "/api/merchant/slots", "本店预约时段分页", "MERCHANT", "ReservationSlotPage", None),
        ("post", "/api/merchant/slots", "发布本店项目时段", "MERCHANT", "ReservationSlot", {"standard_project_id": identifier, "starts_at": timestamp, "ends_at": timestamp, "capacity": slot_fields["capacity"]}),
        ("post", "/api/merchant/slots/{id}/close", "关闭本店预约时段", "MERCHANT", "ReservationSlot", {}),
        ("get", "/api/order/quote/{id}", "当前预约报价确认上下文", "OWNER", "ReservationQuote", None),
        ("get", "/api/order/slots", "可预约项目时段分页", "OWNER", "ReservationSlotPage", None),
        ("post", "/api/order/create", "创建本人待支付预约", "OWNER", "CreatedReservation", {"merchant_project_id": identifier, "quote_version_id": identifier, "vehicle_id": identifier, "slot_id": identifier}),
        ("get", "/api/order/list", "本人订单分页", "OWNER", "OwnerOrderPage", None),
        ("get", "/api/order/{id}", "本人订单及下单快照", "OWNER", "OwnerOrderDetail", None),
        ("post", "/api/order/cancel", "取消本人待支付订单", "OWNER", "OwnerOrderDetail", {"order_id": identifier}),
    ):
        item = operation(method, path, title, "F07,F08")
        item["x-roles"] = role
        item["x-implementation-status"] = "local-reservations-core-implemented"
        item["description"] = "本机预约订单子集，支付基础接入、正式扣款/券/退款未接入；时间带时区、数据库UTC，日历按北京时间。详见 RESERVATION_ORDERS.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        item["responses"]["404"] = {"description": "本人/本店资源不可用，code=40400"}
        item["responses"]["409"] = {"description": "40901报价版本改变、40902时段关闭/开始、40903名额不足、40904时段重叠"}
        item["responses"]["503"] = {"description": "数据库或事务暂不可用，code=50300"}
        if fields is not None:
            item["requestBody"]["content"]["application/json"]["schema"] = {"type": "object", "additionalProperties": False, "required": list(fields), "properties": fields}
        if result.endswith("Page"): item["parameters"] += pagination
        if path == "/api/merchant/slots" and method == "get": item["parameters"].append({"name": "date", "in": "query", "schema": {"type": "string", "format": "date"}})
        if path == "/api/order/slots":
            item["parameters"] += [{"name": k, "in": "query", "required": True, "schema": v} for k,v in {"merchant_id": identifier, "project_id": identifier, "date": {"type": "string", "format": "date"}}.items()]
        if path == "/api/order/list": item["parameters"].append({"name": "status", "in": "query", "schema": {"type": "string", "enum": fulfillment_states}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item
    payment_fields = {"payment_id": identifier, "order_id": identifier, "payment_no": {"type": ["string", "null"]},
        "channel": {"type": "string", "enum": ["LOCAL_TEST", "WECHAT"]}, "test_mode": {"type": "boolean"},
        "status": {"type": "string", "enum": ["CREATED", "PENDING", "SUCCEEDED", "FAILED", "CLOSED"]},
        "amount": quote_fields["price"], "currency": {"type": "string", "const": "CNY"}, "requires_review": {"type": "boolean"},
        "expires_at": {"type": ["string", "null"], "format": "date-time"}, "paid_at": {"type": ["string", "null"], "format": "date-time"}}
    schemas["PaymentDetail"] = {"type": "object", "required": list(payment_fields), "properties": payment_fields}
    schemas["CreatedPayment"] = {**schemas["PaymentDetail"], "properties": {**payment_fields, "channel_payload": {"type": "object"}}, "required": list(payment_fields)+["channel_payload"]}
    summary_fields = {k:v for k,v in payment_fields.items() if k not in ("order_id", "payment_no")}
    schemas["PaymentSummary"] = {"type": "object", "required": list(summary_fields), "properties": summary_fields}
    for name in ("OwnerOrder", "OwnerOrderDetail"):
        schemas[name]["properties"]["payment_summary"] = {"anyOf": [{"$ref": "#/components/schemas/PaymentSummary"}, {"type": "null"}]}
        schemas[name]["required"].append("payment_summary")
    for method,path,result in (("post","/api/payments/create","CreatedPayment"),("get","/api/payments/{id}","PaymentDetail")):
        item = operation(method,path,"本人支付发起" if method=="post" else "本人支付状态查询","F07,F08")
        item["x-roles"] = "OWNER"
        item["x-implementation-status"] = "payment-foundation-local-test-implemented"
        item["description"] = "正式微信未配置返回503；LOCAL_TEST默认关闭且仅用于隔离环境，不代表真实扣款。详见 PAYMENT_FOUNDATION.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if method=="post": item["requestBody"]["content"]["application/json"]["schema"] = {"type":"object","additionalProperties":False,"required":["order_id","channel"],"properties":{"order_id":identifier,"channel":payment_fields["channel"]}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf":[{"$ref":"#/components/schemas/ApiResponse"},{"type":"object","properties":{"data":{"$ref":f"#/components/schemas/{result}"}}}]}
        item["responses"]["409"] = {"description":"订单/支付状态或事件冲突"}
        item["responses"]["503"] = {"description":"渠道未配置或数据库/事务暂不可用"}
        document["paths"][path] = {method:item}
    notice_fields = {"event_id":{"type":"string","format":"uuid"},"payment_id":identifier,"channel_payment_no":{"type":"string","pattern":"^[a-zA-Z0-9_-]{1,128}$"},"status":{"type":"string","enum":["SUCCEEDED","FAILED"]},"amount":quote_fields["price"],"currency":{"type":"string","const":"CNY"},"order_no":{"type":"string"},"occurred_at":timestamp}
    callback = operation("post","/api/payments/callback/LOCAL_TEST","隔离测试渠道签名通知","F07,F08")
    callback["security"] = []
    callback["x-roles"] = "LOCAL_TEST signed channel"
    callback["x-implementation-status"] = "payment-foundation-local-test-implemented"
    callback["description"] = "默认关闭时404；启用须隔离profile/开关/密钥。正文最多16KiB且拒绝重复键；签名及规范化事件摘要规则见 PAYMENT_FOUNDATION.md，不能作为微信验签协议。"
    callback["parameters"] = [{"name":k,"in":"header","required":True,"schema":{"type":"string"}} for k in ("X-Test-Timestamp","X-Test-Nonce","X-Test-Signature")]
    callback["requestBody"]["content"]["application/json"]["schema"] = {"type":"object","additionalProperties":False,"required":list(notice_fields),"properties":notice_fields}
    callback["responses"] = {"200":{"description":"已提交或同事件已提交","content":{"application/json":{"schema":{"type":"object","required":["code"],"properties":{"code":{"const":"SUCCESS"}}}}}},"400":{"description":"通知参数/金额等不匹配"},"401":{"description":"无效/过期签名"},"404":{"description":"测试渠道关闭或支付不存在"},"409":{"description":"事件异文或流水号冲突"},"503":{"description":"事务失败，重试"}}
    document["paths"]["/api/payments/callback/LOCAL_TEST"] = {"post":callback}
    document["paths"]["/api/payments/callback/{channel}"]["post"]["description"] = "未实现的正式渠道草案，本步仅实现固定LOCAL_TEST路径，不开放其他通知入口。"
    merchant_order_fields = {k: v for k, v in schemas["OwnerOrder"]["properties"].items() if k != "payment_summary"}
    for key, allowed in {
        "project_snapshot": {"standard_project_id": identifier, "project_name": {"type": "string"}, "service_content": {"type": "string"}},
        "merchant_snapshot": {"merchant_id": identifier, "merchant_name": {"type": "string"}, "address": {"type": "string"}},
        "appointment_snapshot": {"slot_id": identifier, "starts_at": timestamp, "ends_at": timestamp},
    }.items():
        merchant_order_fields[key] = {"anyOf": [{"type": "object", "additionalProperties": False, "properties": allowed}, {"type": "null"}]}
    merchant_order_fields["payment_summary"] = schemas["OwnerOrder"]["properties"]["payment_summary"]
    merchant_order_fields["has_payment_exception"] = {"type": "boolean", "description": "任意支付尝试存在待核对异常；独立于选中的支付摘要"}
    merchant_order_fields["allowed_actions"] = {"type": "array", "description": "当前状态下商家可请求的动作；仅由状态矩阵决定，不含前置条件判定",
        "items": {"type": "object", "additionalProperties": False, "required": ["action", "to_status"],
                  "properties": {"action": {"type": "string", "enum": fulfillment_actions}, "to_status": {"type": "string", "enum": fulfillment_states}}}}
    schemas["MerchantOrder"] = {"type": "object", "additionalProperties": False,
        "required": list(merchant_order_fields), "properties": merchant_order_fields}
    schemas["MerchantOrderDetail"] = {"type": "object", "additionalProperties": False,
        "required": list(merchant_order_fields) + ["price_snapshot"],
        "properties": {**merchant_order_fields, "price_snapshot": {"anyOf": [{"type": "object", "additionalProperties": False, "properties": {
            "merchant_project_id": identifier, "quote_version_id": identifier, "version": identifier, "price": quote_fields["price"]}}, {"type": "null"}]}}}
    schemas["MerchantOrderPage"] = {**schemas["ServiceProjectPage"], "properties": {
        **schemas["ServiceProjectPage"]["properties"], "items": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantOrder"}}}}
    for path, result, title in (("/api/merchant/orders", "MerchantOrderPage", "本店订单分页及筛选"),
                                ("/api/merchant/orders/{id}", "MerchantOrderDetail", "本店订单详情与下单快照")):
        item = operation("get", path, title, "F07,F08")
        item["x-roles"] = "MERCHANT"
        item["x-implementation-status"] = "merchant-orders-read-implemented"
        item["description"] = "校验有效商家会话和本店归属；只读投影仅包含白名单下单快照，绝不返回客户身份、车辆ID、完整车牌或VIN。详见 MERCHANT_ORDERS.md。"
        item["responses"]["404"] = {"description": "订单不存在或不属于本店，code=40400"}
        item["responses"]["503"] = {"description": "数据库暂不可用，code=50300"}
        if path.endswith("/{id}"):
            item["parameters"][0]["schema"] = identifier
        else:
            item["parameters"] += pagination + [
                {"name": "status", "in": "query", "schema": {"type": "string", "enum": fulfillment_states}},
                {"name": "date", "in": "query", "description": "按北京时间预约日期，包含全天，严格YYYY-MM-DD", "schema": {"type": "string", "format": "date"}}]
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
            {"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"][path] = {"get": item}
    schemas["MerchantOrderAction"] = {"type": "object", "additionalProperties": False,
        "required": list(merchant_order_fields) + ["price_snapshot", "action", "from_status", "changed"],
        "properties": {**merchant_order_fields, "price_snapshot": schemas["MerchantOrderDetail"]["properties"]["price_snapshot"],
            "action": {"type": "string", "enum": fulfillment_actions}, "from_status": {"type": "string", "enum": fulfillment_states},
            "changed": {"type": "boolean", "description": "false 表示该动作此前已生效，本次为幂等重放，不产生新的状态迁移审计"}}}
    action = operation("post", "/api/merchant/orders/{id}/actions", "本店订单履约状态操作", "F08,F14,F16,F18")
    action["x-roles"] = "MERCHANT"
    action["x-implementation-status"] = "order-fulfillment-state-machine-implemented"
    action["description"] = "前端只能请求动作，不能提交目标状态；矩阵、前置、行锁与幂等审计均由服务端判定。接车证据(A3)、车主确认与派工(A4/A5)、报工(A6)、核销(A4)未接入时按 fail-closed 返回 43001/43003/43004/43005/43006。详见 ORDER_FULFILLMENT.md。"
    action["parameters"][0]["schema"] = identifier
    action["requestBody"]["content"]["application/json"]["schema"] = {"type": "object", "additionalProperties": False,
        "required": ["action"], "properties": {"action": {"type": "string", "enum": fulfillment_actions},
            "note": {"type": "string", "minLength": 1, "maxLength": 200, "description": "可选，去除首尾空白后 1–200 字"}}}
    action["responses"]["404"] = {"description": "订单不存在或不属于本店，code=40400"}
    action["responses"]["409"] = {"description": "40905当前状态不允许该动作；43001接车检查未完成；43003车主未确认接车；43004尚未派工；43005报工未完成；43006该动作的校验尚未接入"}
    action["responses"]["503"] = {"description": "数据库或事务暂不可用，code=50300"}
    action["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
        {"$ref": "#/components/schemas/ApiResponse"},
        {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/MerchantOrderAction"}}}]}
    document["paths"]["/api/merchant/orders/{id}/actions"] = {"post": action}
    count = sum(len(value) for value in document["paths"].values())
    # A3 pickup: photos are private file IDs, never persistent URLs.
    slots = ["FRONT", "REAR", "LEFT", "RIGHT", "ROOF", "DASHBOARD", "INTERIOR"]
    photo_fields = {slot: identifier for slot in slots}
    damage = {"type": "object", "additionalProperties": False, "required": ["photo_slot", "x", "y", "note"], "properties": {
        "photo_slot": {"type": "string", "enum": slots}, "x": {"type": "number", "minimum": 0, "maximum": 1},
        "y": {"type": "number", "minimum": 0, "maximum": 1}, "note": {"type": "string", "minLength": 1, "maxLength": 200}}}
    pickup_fields = {"order_id": identifier, "appointment_code": {"type": "string", "pattern": "^[0-9]{6}$", "writeOnly": True},
        "photos": {"type": "object", "additionalProperties": False, "required": slots, "properties": photo_fields},
        "mileage": {"type": "integer", "minimum": 0, "maximum": 9999999}, "fuel_level": {"type": "string", "enum": ["EMPTY", "QUARTER", "HALF", "THREE_QUARTERS", "FULL"]},
        "damage_status": {"type": "string", "enum": ["NONE", "PRESENT"]}, "damages": {"type": "array", "maxItems": 20, "items": damage},
        "mileage_reason": {"type": "string", "minLength": 1, "maxLength": 200}, "arrival_reason": {"type": "string", "minLength": 1, "maxLength": 200}}
    schemas["PickupSubmit"] = {"type": "object", "additionalProperties": False, "required": [k for k in pickup_fields if k not in ("mileage_reason", "arrival_reason")], "properties": pickup_fields}
    for method, path, roles, title in [
        ("get", "/api/check/pickup/context", "MERCHANT", "本店接车上下文与里程基线"),
        ("post", "/api/check/pickup/submit", "MERCHANT", "验预约码并提交完整接车单"),
        ("get", "/api/check/pickup/{order}", "OWNER,MERCHANT", "本人或本店接车单"),
        ("get", "/api/check/pickup/{order}/files/{file}/access", "OWNER,MERCHANT", "接车单关联图片短时访问"),
        ("post", "/api/merchant/files/upload", "MERCHANT", "商家本人私有图片上传"),
        ("get", "/api/merchant/files/{id}/access", "MERCHANT", "商家上传者本人图片预览")]:
        item = operation(method, path, title, "F14")
        item["x-roles"] = roles
        item["x-implementation-status"] = "pickup-inspection-implemented"
        item["description"] = "手填里程、七图一次提交；本店/本人归属与CLEAN校验。详见 PICKUP_INSPECTION.md，车主确认与派工未开放。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if path.endswith("/context"): item["parameters"].append({"name": "order_id", "in": "query", "required": True, "schema": identifier})
        if path.endswith("/submit"): item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/PickupSubmit"}
        if path.endswith("/upload"): item["requestBody"] = {"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "additionalProperties": False, "required": ["file"], "properties": {"file": {"type": "string", "format": "binary"}}}}}}
        for code in (404, 409, 422, 429, 503): item["responses"][str(code)] = {"description": "资源不可用/状态冲突/验码或图片无效/限流/暂不可用，见契约"}
        document["paths"][path] = {method: item}
    schemas["OwnerOrder"]["properties"]["appointment_code"] = {"type": "string", "pattern": "^[0-9]{6}$", "description": "仅本人PAID详情返回；商家投影永不包含"}
    schemas["MerchantOrder"]["properties"]["allowed_actions"]["description"] = "PAID使用接车检查，不再返回RECEIVE；其他动作仍由状态矩阵决定"
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {count} operations to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
