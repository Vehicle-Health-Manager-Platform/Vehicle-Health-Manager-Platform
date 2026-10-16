"""Generate the S0 OpenAPI draft from the tracked core and support contracts."""

import csv
import json
import re
from pathlib import Path
from r4_openapi import add_r4_completion
from r5a_openapi import add_r5a
from r5b_openapi import add_r5b
from r6a_openapi import add_r6a
from r6b1_openapi import add_r6b1
from r6b2_openapi import add_r6b2
from r6b3_openapi import add_r6b3
from r7a_openapi import add_r7a
from r7b_openapi import add_r7b
from r7c1_openapi import add_r7c1
from r7c2_openapi import add_r7c2
from r7c3_openapi import add_r7c3


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
            "archive_type": {"type": "integer", "minimum": 1, "maximum": 10},
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
    archive_list["x-implementation-status"] = "manual-photo-and-service-archive-implemented"
    archive_list["description"] = "本人车辆档案；input_type=1/3为拍照/手动，4为施工自动归档，附source、test_mode、parts_used、no_parts、work_minutes及施工时间。新核销完成即登记归档、无需评价；source.review_id完成来源为null，旧评价来源为正整数，字段不缺失且不为0；后续评价不改快照。按日期与ID倒序；施工图片经档案专用授权，不返回签名URL。详见 SERVICE_ARCHIVES.md。"
    archive_file_path = "/api/archive/{archiveId}/files/{fileId}/access"
    archive_file = operation("get", archive_file_path, "本人施工档案私有图片短时访问", "F03")
    archive_file["x-roles"] = "正式车主"
    archive_file["x-implementation-status"] = "implemented"
    archive_file["description"] = "复核会话、当前车辆归属、原订单车主、已完成任务、施工照片关系及安全文件；不开放员工签字，不改变文件所有者。返回短时url及expires_at，Cache-Control:no-store。"
    for parameter in archive_file["parameters"]:
        parameter["schema"] = {"type": "integer", "minimum": 1, "maximum": 9007199254740991}
    archive_file["responses"]["404"] = {"description": "档案或图片不可用"}
    archive_file["responses"]["503"] = {"description": "数据库或私有图片暂不可用"}
    document["paths"][archive_file_path] = {"get": archive_file}
    archive_list["parameters"] += [
        {"name": "vehicle_id", "in": "query", "required": True,
         "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}},
        {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
        {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20}},
    ]
    archive_list["responses"]["404"] = {"description": "本人车辆不可用"}
    archive_list["responses"]["503"] = {"description": "数据库暂不可用"}
    archive_report_path = "/api/archive/report"
    archive_report = operation("get", archive_report_path, "导出本人车辆档案 PDF", "F03")
    archive_report["x-roles"] = "正式车主"
    archive_report["x-implementation-status"] = "implemented"
    archive_report["description"] = (
        "单一只读 REPEATABLE READ 快照；仅本人手动/拍照档案和本人已完成施工归档。"
        "默认导出全部可见记录；from/to 必须成对且日期首尾含。未知日期在全量报告中明示，"
        "筛选时排除并报告未分类数量。最多500条、PDF最多3 MiB；不持久化报告。"
        "无 Accept 或 application/pdf 返回 application/pdf；"
        "Accept: application/vnd.autocare.pdf-base64+json;version=1 返回 JSON Base64 信封。"
        "响应 no-store。详见 ARCHIVE_MANUAL.md。")
    archive_report["parameters"] = [
        {"name": "vehicle_id", "in": "query", "required": True,
         "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}},
        {"name": "from", "in": "query", "required": False, "schema": {"type": "string", "format": "date"}},
        {"name": "to", "in": "query", "required": False, "schema": {"type": "string", "format": "date"}},
        {"name": "Accept", "in": "header", "required": False, "schema": {"type": "string",
         "enum": ["application/pdf", "application/vnd.autocare.pdf-base64+json;version=1"]}},
    ]
    archive_report["responses"] = {
        "200": {"description": "本人私有报告，服务端不保存报告副本", "headers": {
            "Cache-Control": {"schema": {"type": "string", "const": "no-store"}}},
            "content": {"application/pdf": {"schema": {"type": "string", "format": "binary"}},
                "application/json": {"schema": {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
                    {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/ArchiveReportEnvelope"}}}]}}}},
        "400": {"description": "缺参、重复参数、未知参数或日期范围无效"},
        "401": {"description": "OWNER 会话无效"}, "403": {"description": "非 OWNER 身份"},
        "404": {"description": "车辆不可用"}, "409": {"description": "超过500条或 PDF 超过3 MiB"},
        "503": {"description": "数据库、中文字体或 PDF 生成暂不可用"},
    }
    document["paths"][archive_report_path] = {"get": archive_report}
    for method, path, summary in (
        ("post", "/api/archive/shares", "创建本人档案摘要限时分享"),
        ("get", "/api/archive/shares", "查看本人车辆最多100条分享，优先有效分享"),
        ("get", "/api/archive/shares/{id}/link", "取得本人有效分享小程序路由"),
        ("post", "/api/archive/shares/{id}/revoke", "撤销本人分享"),
        ("post", "/api/archive/share/view", "已登录车主持能力令牌读取摘要"),
    ):
        share_op = operation(method, path, summary, "F03")
        share_op["x-roles"] = "正式车主"
        share_op["x-implementation-status"] = "implemented"
        share_op["description"] = "固定字段快照，不含车牌/VIN、自由文本、照片、签字或PDF。所有响应no-store。详见 ARCHIVE_SHARES.md。"
        share_op["responses"]["410"] = {"description": "分享过期、撤销或来源车辆/账号失效"}
        share_op["responses"]["409"] = {"description": "记录超过500条或有效分享超过20条"}
        share_op["responses"]["503"] = {"description": "数据库或摘要暂不可用"}
        if path == "/api/archive/shares" and method == "get":
            share_op["parameters"] = [{"name": "vehicle_id", "in": "query", "required": True,
                "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}}]
        if method == "post":
            props = {}
            required = []
            if path == "/api/archive/shares":
                props = {"vehicle_id": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
                    "expires_in_hours": {"type": "integer", "enum": [1, 24, 168], "default": 24},
                    "from": {"type": "string", "format": "date"}, "to": {"type": "string", "format": "date"}}
                required = ["vehicle_id"]
            if path == "/api/archive/share/view":
                share_op["parameters"] = []
                props = {"share_token": {"type": "string", "pattern": "^[a-f0-9]{64}$"}}
                required = ["share_token"]
            share_op["requestBody"]["content"]["application/json"]["schema"] = {
                "type": "object", "additionalProperties": False, "properties": props, "required": required}
        document["paths"].setdefault(path, {})[method] = share_op
    for method, path, summary in (
        ("get", "/api/archive/{id}/reminder", "本人来源档案与当前类目目标设置"),
        ("put", "/api/archive/{id}/reminder", "设置或停用本人档案目标"),
        ("get", "/api/vehicle/reminders", "本人车辆三类可追溯提醒"),
    ):
        op = operation(method, path, summary, "F03,F04")
        op["x-roles"] = "正式车主"
        op["x-implementation-status"] = "implemented"
        op["description"] = "用户填写保养/保险/年检目标，Asia/Shanghai日期或登记里程达到时提醒。不推断养护周期/法定日期。no-store。详见ARCHIVE_REMINDERS.md。"
        op["responses"].update({"404": {"description": "本人车辆或有效来源不可用"},
            "409": {"description": "版本/来源变化，请刷新并复核"}, "503": {"description": "数据库不可用"}})
        if path == "/api/vehicle/reminders":
            op["parameters"] = [{"name": "vehicle_id", "in": "query", "required": True,
                "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}}]
        if method == "put":
            op["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
            op["requestBody"] = {"required": True, "content": {"application/json": {"schema": {
                "type": "object", "additionalProperties": False, "required": ["expected_revision", "active"], "properties": {
                    "expected_revision": {"type": "integer", "minimum": 0, "maximum": 2147483646},
                    "active": {"type": "boolean"}, "due_date": {"type": ["string", "null"], "format": "date"},
                    "due_mileage": {"type": ["integer", "null"], "minimum": 0, "maximum": 2147483647},
                    "replace_source": {"type": "boolean", "default": False}}}}}}
        document["paths"].setdefault(path, {})[method] = op
    for method, summary in (("get", "本人来源结构化资料"), ("put", "替换或清空本人结构化资料")):
        path = "/api/archive/{id}/structured"
        op = operation(method, path, summary, "F03,F04")
        op["x-roles"] = "正式车主"
        op["x-implementation-status"] = "implemented"
        op["description"] = "本人未核验资料；保养/保险/年检类型白名单、有效会话与当前归属。no-store；不改原档案/里程/目标或分享白名单。详见ARCHIVE_STRUCTURED.md。"
        op["responses"].update({"404": {"description": "有效本人来源不可用"}, "409": {"description": "资料版本变化"}, "503": {"description": "数据库或审计失败"}})
        if method == "put":
            op["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
            typed = {
                "provider_name": {"type": "string", "minLength": 1, "maxLength": 100},
                "performed_mileage": {"type": "integer", "minimum": 0, "maximum": 2147483647},
                "service_items": {"type": "array", "minItems": 1, "maxItems": 20, "uniqueItems": True, "items": {"type": "string", "minLength": 1, "maxLength": 80}},
                "insurer_name": {"type": "string", "minLength": 1, "maxLength": 100},
                "policy_number": {"type": "string", "minLength": 1, "maxLength": 64},
                "coverage_type": {"type": "string", "enum": ["COMPULSORY", "COMMERCIAL", "COMBINED", "OTHER"]},
                "coverage_start": {"type": "string", "format": "date"}, "coverage_end": {"type": "string", "format": "date"},
                "inspection_station": {"type": "string", "minLength": 1, "maxLength": 100},
                "inspection_result": {"type": "string", "enum": ["PASSED", "FAILED", "UNKNOWN"]},
                "valid_until": {"type": "string", "format": "date"},
            }
            variants = [["provider_name", "performed_mileage", "service_items"], ["insurer_name", "policy_number", "coverage_type", "coverage_start", "coverage_end"], ["inspection_station", "inspection_result", "valid_until"]]
            op["requestBody"] = {"required": True, "content": {"application/json": {"schema": {
                "type": "object", "additionalProperties": False, "required": ["expected_revision", "fields"], "properties": {
                    "expected_revision": {"type": "integer", "minimum": 0, "maximum": 2147483646},
                    "fields": {"anyOf": [{"type": "object", "additionalProperties": False, "properties": {name: typed[name] for name in names}} for names in variants]}}}}}}
        document["paths"].setdefault(path, {})[method] = op
    metrics = ["OIL", "BRAKE_PAD", "TIRE", "TRACTION_BATTERY", "COOLANT", "AUX_BATTERY"]
    for method, path, summary in (("get", "/api/archive/{id}/health-observation", "本人档案读数来源context"),
            ("put", "/api/archive/{id}/health-observation", "设置或清空本人当前读数"),
            ("get", "/api/vehicle/health-observations", "本人车辆六类当前读数"),
            ("get", "/api/vehicle/health-observation-history", "本人读数分页历史与修正履历")):
        op = operation(method, path, summary, "F03,F04")
        op["x-roles"] = "正式车主"
        op["x-implementation-status"] = "implemented"
        op["description"] = "本人手动/附图保养维修来源，原始读数未核验，无诊断/分数/推断适用性，no-store。详见HEALTH_OBSERVATIONS.md。"
        op["responses"].update({"404": {"description": "有效本人来源不可用"}, "409": {"description": "版本或来源变化"}, "503": {"description": "数据库或审计失败"}})
        if path in ("/api/vehicle/health-observations", "/api/vehicle/health-observation-history"):
            op["parameters"] = [{"name": "vehicle_id", "in": "query", "required": True, "schema": {"type": "integer", "minimum": 1, "maximum": 9007199254740991}}]
        elif method == "get":
            op["parameters"].append({"name": "metric", "in": "query", "required": True, "schema": {"type": "string", "enum": metrics}})
        if path == "/api/vehicle/health-observation-history":
            op["description"] += "按版本倒序分页；逐行验证来源，失效时隐藏读数与来源；原版本不可覆盖。详见HEALTH_OBSERVATION_HISTORY.md。"
            op["parameters"] += [{"name":"metric","in":"query","required":True,"schema":{"type":"string","enum":metrics}},
                {"name":"before_revision","in":"query","schema":{"type":"integer","minimum":1,"maximum":2147483647}},
                {"name":"limit","in":"query","schema":{"type":"integer","minimum":1,"maximum":50,"default":20}}]
        if method == "put":
            op["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
            fields = {"measurement_name": {"type": "string", "minLength": 1, "maxLength": 80},
                "value": {"type": "string", "pattern": r"^-?(0|[1-9][0-9]{0,8})(\.[0-9]{1,3})?$"},
                "unit": {"type": "string", "enum": ["KM", "PERCENT", "MM", "KPA", "V", "CELSIUS"]},
                "measured_on": {"type": "string", "format": "date"}, "method": {"type": "string", "minLength": 1, "maxLength": 120},
                "provenance": {"type": "string", "enum": ["OWNER_MEASURED", "OWNER_TRANSCRIBED"]}}
            op["requestBody"] = {"required": True, "content": {"application/json": {"schema": {
                "type": "object", "additionalProperties": False, "required": ["metric", "expected_revision", "measurement"], "properties": {
                    "metric": {"type": "string", "enum": metrics}, "expected_revision": {"type": "integer", "minimum": 0, "maximum": 2147483646},
                    "operation": {"type":"string","enum":["RECORD","CORRECT","CLEAR"],"description":"省略时空值CLEAR，否则RECORD；CORRECT仅修正同来源当前版本"}, "replace_source": {"type": "boolean", "default": False}, "measurement": {"oneOf": [
                        {"type": "object", "maxProperties": 0}, {"type": "object", "additionalProperties": False, "required": list(fields), "properties": fields}]}}}}}}
        document["paths"].setdefault(path, {})[method] = op
    project_fields = {
        "id": {"type": "integer", "minimum": 1, "maximum": 9007199254740991},
        "project_name": {"type": "string"}, "category": {"type": "integer", "minimum": 1, "maximum": 6},
        "base_price_low": {"type": "string", "pattern": r"^\d{1,8}\.\d{2}$"},
        "base_price_high": {"type": "string", "pattern": r"^\d{1,8}\.\d{2}$"},
    }
    schemas = document["components"]["schemas"]
    report_envelope_fields = {
        "version": {"type": "integer", "const": 1},
        "file_name": {"type": "string", "const": "vehicle-archive-report.pdf"},
        "content_type": {"type": "string", "const": "application/pdf"},
        "content_length": {"type": "integer", "minimum": 1, "maximum": 3145728},
        "content_base64": {"type": "string", "contentEncoding": "base64"},
    }
    schemas["ArchiveReportEnvelope"] = {"type": "object", "additionalProperties": False,
        "required": list(report_envelope_fields), "properties": report_envelope_fields}
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
                item["parameters"].append({"name":"region_code","in":"query","schema":{"type":"string","pattern":"^[0-9]{6}$"},"description":"精确筛选上线门店区划码，省略时全部；无匹配空页，旧无区域门店仅全部显示"})
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
    shop_fields={"merchant_id":project_fields["id"],"name":{"type":"string"},"address":{"type":"string"},
        "merchant_type":{"type":"integer","minimum":1,"maximum":6},"region_code":{"type":["string","null"],"pattern":"^[0-9]{6}$"}}
    offer_fields={**quote_fields,"standard_project_id":project_fields["id"],"project_name":{"type":"string"},"category":{"type":"integer","minimum":1,"maximum":6}}
    schemas["OwnerStorefront"]={"type":"object","additionalProperties":False,"required":list(shop_fields),"properties":shop_fields}
    schemas["StorefrontOffer"]={"type":"object","additionalProperties":False,"required":list(offer_fields),"properties":offer_fields}
    schemas["StorefrontOffers"]={**schemas["ServiceProjectPage"],"additionalProperties":False,"properties":{**schemas["ServiceProjectPage"]["properties"],"items":{"type":"array","items":{"$ref":"#/components/schemas/StorefrontOffer"}}}}
    schemas["OwnerStorefrontDetail"]={"type":"object","additionalProperties":False,"required":["merchant","offers"],"properties":{
        "merchant":{"$ref":"#/components/schemas/OwnerStorefront"},"offers":{"$ref":"#/components/schemas/StorefrontOffers"}}}
    item=operation("get","/api/service/merchant/{id}","车主门店详情与在售服务分页","F06")
    item["x-roles"]="正式车主";item["x-implementation-status"]="implemented"
    item["description"]="有效车主会话；只读最小门店投影，不含电话/坐标/资质/员工/客户信息。无评分与距离。no-store。详见MERCHANT_DISCOVERY.md。"
    item["parameters"][0]["schema"]=project_fields["id"]
    item["parameters"] += [{"name":"project_id","in":"query","schema":project_fields["id"]}]+document["paths"]["/api/service/projects"]["get"]["parameters"][-2:]
    item["responses"].update({"404":{"description":"门店/指定项目停用、删除或不可用"},"503":{"description":"数据库尚未配置/服务不可用"}})
    item["responses"]["200"]["content"]["application/json"]["schema"]={"allOf":[{"$ref":"#/components/schemas/ApiResponse"},{"type":"object","required":["data"],"properties":{"data":{"$ref":"#/components/schemas/OwnerStorefrontDetail"}}}]}
    document["paths"]["/api/service/merchant/{id}"]={"get":item}
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
    recovery = operation("post", "/api/payments/{id}/reconcile", "本人支付结果可信查询核对", "F07,F08")
    recovery["x-roles"] = "OWNER"
    recovery["x-implementation-status"] = "payment-query-recovery-local-test-implemented"
    recovery["description"] = "严格空JSON；核对独立供应商已验签事实，200不表示支付成功。60秒持久租约防重复查询，未知/超时保持状态；迟到账待核对，不恢复关闭订单，不执行退款。正式资金保持R9。详见PAYMENT_RECOVERY.md。"
    recovery["parameters"] = [{"name":"id", "in":"path", "required":True, "schema":identifier}]
    recovery["requestBody"] = {"required":True, "content":{"application/json":{"schema":{"type":"object", "additionalProperties":False, "maxProperties":0}}}}
    recovery["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf":[{"$ref":"#/components/schemas/ApiResponse"},{"type":"object","properties":{"data":{"$ref":"#/components/schemas/PaymentDetail"}}}]}
    recovery["responses"]["409"] = {"description":"历史支付信息不完整"}
    recovery["responses"]["503"] = {"description":"数据库/渠道查询未配置或暂不可用"}
    document["paths"]["/api/payments/{id}/reconcile"] = {"post":recovery}
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
    action["description"] = "前端只能请求动作，不能提交目标状态；矩阵、前置、行锁与幂等审计均由服务端判定。接车证据(A3)与车主决定(A4)已接入；缺证据或缺确认仍拒绝。派工(A5)、报工(A6)、核销(A7)按阶段接入，缺前置按 fail-closed 返回 43001/43003/43004/43005/43006。详见 ORDER_FULFILLMENT.md。"
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
    photo_fields = {slot: identifier for slot in slots + ["FUEL"]}
    damage = {"type": "object", "additionalProperties": False, "required": ["photo_slot", "x", "y", "note"], "properties": {
        "photo_slot": {"type": "string", "enum": slots}, "x": {"type": "number", "minimum": 0, "maximum": 1},
        "y": {"type": "number", "minimum": 0, "maximum": 1}, "note": {"type": "string", "minLength": 1, "maxLength": 200}}}
    pickup_fields = {"order_id": identifier, "appointment_code": {"type": "string", "pattern": "^[0-9]{6}$", "writeOnly": True},
        "photos": {"type": "object", "additionalProperties": False, "required": slots, "minProperties": 7, "maxProperties": 8, "description": "七图历史兼容；新微信表单要求独立FUEL油表照片，所有ID必须互异", "properties": photo_fields},
        "mileage": {"type": "integer", "minimum": 0, "maximum": 9999999}, "fuel_level": {"type": "string", "enum": ["EMPTY", "QUARTER", "HALF", "THREE_QUARTERS", "FULL"]},
        "damage_status": {"type": "string", "enum": ["NONE", "PRESENT"]}, "damages": {"type": "array", "maxItems": 20, "items": damage},
        "mileage_reason": {"type": "string", "minLength": 1, "maxLength": 200}, "arrival_reason": {"type": "string", "minLength": 1, "maxLength": 200}}
    schemas["PickupSubmit"] = {"type": "object", "additionalProperties": False, "required": [k for k in pickup_fields if k not in ("mileage_reason", "arrival_reason")], "properties": pickup_fields}
    for method, path, roles, title in [
        ("get", "/api/check/pickup/context", "MERCHANT,STAFF", "本店接车上下文与里程基线"),
        ("post", "/api/check/pickup/submit", "MERCHANT,STAFF", "验预约码并提交完整接车单"),
        ("post", "/api/check/pickup/confirm", "OWNER", "车主确认接车单或提出异议"),
        ("get", "/api/check/pickup/{order}", "OWNER,MERCHANT,STAFF", "本人或本店接车单"),
        ("get", "/api/check/pickup/{order}/files/{file}/access", "OWNER,MERCHANT,STAFF", "接车单关联图片短时访问"),
        ("post", "/api/merchant/files/upload", "MERCHANT,STAFF", "商家本人私有图片上传"),
        ("get", "/api/merchant/files/{id}/access", "MERCHANT,STAFF", "商家上传者本人图片预览")]:
        item = operation(method, path, title, "F14")
        item["x-roles"] = roles
        item["x-implementation-status"] = "pickup-inspection-implemented"
        item["description"] = "七图历史兼容及独立FUEL油表接车与本人车主决策；身份、状态和图片归属由服务端校验。详见 PICKUP_INSPECTION.md 与 PICKUP_OWNER_DECISION.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if path.endswith("/context"): item["parameters"].append({"name": "order_id", "in": "query", "required": True, "schema": identifier})
        if path.endswith("/submit"): item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/PickupSubmit"}
        if path.endswith("/confirm"): item["requestBody"]["content"]["application/json"]["schema"] = {"type": "object", "additionalProperties": False, "required": ["order_id", "decision"], "properties": {"order_id": identifier, "decision": {"type": "string", "enum": ["CONFIRM", "DISPUTE"]}, "reason": {"type": "string", "minLength": 1, "maxLength": 500, "description": "DISPUTE 必填，CONFIRM 禁止"}}}
        if path.endswith("/upload"): item["requestBody"] = {"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "additionalProperties": False, "required": ["file"], "properties": {"file": {"type": "string", "format": "binary"}}}}}}
        for code in (404, 409, 422, 429, 503): item["responses"][str(code)] = {"description": "资源不可用/状态冲突/验码或图片无效/限流/暂不可用，见契约"}
        document["paths"][path] = {method: item}
    schemas["OwnerOrder"]["properties"]["appointment_code"] = {"type": "string", "pattern": "^[0-9]{6}$", "description": "仅本人PAID详情返回；商家投影永不包含"}
    schemas["MerchantOrder"]["properties"]["allowed_actions"]["description"] = "PAID使用接车检查，不再返回RECEIVE；其他动作仍由状态矩阵决定"
    # A5.2: staff-account assignments and the technician's own acceptance.
    assignment_status = {"type": "string", "enum": ["ASSIGNED", "ACCEPTED"]}
    nullable_time = {"type": ["string", "null"], "format": "date-time"}
    def strict(properties):
        return {"type": "object", "additionalProperties": False, "required": list(properties), "properties": properties}
    def paged(item):
        return strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/" + item}},
            "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer", "minimum": 1}, "page_size": {"type": "integer", "minimum": 1, "maximum": 100}})
    dispatch_fields = {"assignment_id": identifier, "order_id": identifier, "technician_id": identifier,
        "assignment_status": assignment_status, "order_status": {"type": "string", "enum": fulfillment_states},
        "assigned_at": timestamp, "accepted_at": nullable_time, "changed": {"type": "boolean"}}
    schemas["DispatchResult"] = strict(dispatch_fields)
    schemas["TechnicianCandidate"] = strict({"technician_id": identifier, "label": {"type": "string"}})
    schemas["TechnicianCandidates"] = paged("TechnicianCandidate")
    schemas["MerchantAssignment"] = strict({"assignment": {"anyOf": [{"type": "null"}, strict({
        "assignment_id": identifier, "order_id": identifier, "technician_id": identifier, "technician_label": {"type": "string"},
        "status": assignment_status, "assigned_at": timestamp, "accepted_at": nullable_time})]}})
    tech_fields = {k: v for k, v in dispatch_fields.items() if k not in ("technician_id", "changed")}
    tech_fields.update({"order_no": {"type": "string"}, "can_accept": {"type": "boolean"},
        "project_snapshot": schemas["MerchantOrder"]["properties"]["project_snapshot"], "appointment_snapshot": schemas["MerchantOrder"]["properties"]["appointment_snapshot"]})
    schemas["TechnicianOrder"] = strict(tech_fields)
    schemas["TechnicianOrders"] = paged("TechnicianOrder")
    for method, path, roles, title, response in [
        ("get", "/api/merchant/technicians", "MERCHANT", "本店有效且已绑定的技师候选", "TechnicianCandidates"),
        ("get", "/api/merchant/orders/{id}/assignment", "MERCHANT", "本店订单派工详情", "MerchantAssignment"),
        ("post", "/api/merchant/orders/{id}/assign", "MERCHANT", "本店订单首次派工", "DispatchResult"),
        ("get", "/api/tech/orders", "TECHNICIAN", "技师本人派工列表", "TechnicianOrders"),
        ("get", "/api/tech/orders/{id}", "TECHNICIAN", "技师本人工单详情", "TechnicianOrder"),
        ("post", "/api/tech/orders/{id}/accept", "TECHNICIAN", "技师本人接单并开始施工", "DispatchResult")]:
        item = operation(method, path, title, "F14,F16,F18")
        item["x-roles"] = roles
        item["x-implementation-status"] = "technician-dispatch-backend-implemented"
        item["description"] = "严格校验会话/本店或本人归属。technician_id为员工ID。接车证据与车主确认齐全且无争议才可派工/接单；防护在A6报工校验。24小时幂等、行锁与审计同事务；详见 TECHNICIAN_DISPATCH.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if path in ("/api/merchant/technicians", "/api/tech/orders"):
            item["parameters"] += pagination
        if path == "/api/tech/orders":
            item["parameters"].append({"name": "assignment_status", "in": "query", "schema": assignment_status})
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = strict({"technician_id": identifier} if path.endswith("/assign") else {})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
            {"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/" + response}}}]}
        item["responses"]["404"] = {"description": "资源不存在/非本店或本人/不可选技师，code=40400"}
        item["responses"]["409"] = {"description": "40905状态冲突/历史数据不一致；43001缺接车证据；43003缺车主确认"}
        item["responses"]["503"] = {"description": "数据库未配置/事务失败，code=50300；原键重试"}
        document["paths"][path] = {method: item}
    schemas["MerchantOrder"]["properties"]["allowed_actions"]["description"] = "RECEIVE走接车检查，START_SERVICE走技师本人接单，两者不再投影为商家动作"
    action["description"] = "前端只能请求动作，不能提交目标状态；行锁、前置、幂等和审计同事务。A3接车、A4确认、A5.2派工与本人接单已接入；商家通用RECEIVE/START_SERVICE分别返回43001/43004，专用技师接单才开始施工。报工/核销待A6/A7，缺前置返回43005/43006。"
    # A5.6: dispute handling, the owner review and the resume of a disputed order.
    dispute_status = {"type": "string", "enum": ["OPEN", "RESOLVED"]}
    schemas["DisputeResult"] = strict({"dispute_id": identifier, "order_id": identifier, "dispute_status": dispute_status,
        "order_status": {"type": "string", "enum": fulfillment_states}, "owner_confirm": {"type": "integer", "enum": [0, 1, 2, 3]},
        "record_count": {"type": "integer", "minimum": 1},
        "last_action": {"anyOf": [{"type": "null"}, {"type": "string", "enum": ["HANDLE", "ACCEPT", "REJECT"]}]},
        "updated_at": timestamp, "changed": {"type": "boolean"}})
    for method, path, roles, title in [
        ("post", "/api/merchant/orders/{id}/dispute/handle", "MERCHANT", "商家提交争议处理记录"),
        ("post", "/api/check/pickup/dispute/review", "OWNER", "车主复核争议处理并决定是否恢复订单")]:
        item = operation(method, path, title, "F14,F16")
        item["x-roles"] = roles
        item["x-implementation-status"] = "dispute-resolution-implemented"
        item["description"] = "商家只能追加处理记录，不能自行解除争议；只有车主本人复核接受后，订单才回到争议前状态并恢复派工与施工。恢复条件R1–R5、锁顺序、双审计与幂等见 DISPUTE_RESOLUTION.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if path.endswith("/handle"):
            item["requestBody"]["content"]["application/json"]["schema"] = strict({"note": {"type": "string", "minLength": 1, "maxLength": 500, "description": "去除首尾空白后1–500字，可多次提交"}})
        else:
            item["requestBody"]["content"]["application/json"]["schema"] = {"type": "object", "additionalProperties": False,
                "required": ["order_id", "decision"], "properties": {"order_id": identifier,
                    "decision": {"type": "string", "enum": ["ACCEPT", "REJECT"]},
                    "note": {"type": "string", "minLength": 1, "maxLength": 500, "description": "REJECT 必填，ACCEPT 可省略"}}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [
            {"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/DisputeResult"}}}]}
        item["responses"]["404"] = {"description": "订单或接车单不存在/非本人或非本店，code=40400"}
        item["responses"]["409"] = {"description": "40905状态冲突或恢复目标不可用；43008商家尚未提交处理记录"}
        item["responses"]["503"] = {"description": "数据库未配置或事务失败，code=50300；原键重试"}
        document["paths"][path] = {method: item}
    document["paths"]["/api/check/pickup/{order}"]["get"]["description"] = "本人或本店接车单。投影新增 dispute（争议状态、异议原因、时间线与 can_review），不投影任何身份 ID；见 DISPUTE_RESOLUTION.md。"
    # A5.6 把「未解决的争议」从泛化的 40905 收敛为明确的 43007，派工与接单的拒绝说明要同步。
    for path in ("/api/merchant/orders/{id}/assign", "/api/tech/orders/{id}/accept"):
        document["paths"][path]["post"]["responses"]["409"]["description"] = "40905状态冲突/历史数据不一致；43001缺接车证据；43003缺车主确认；43007订单存在未解决的争议"
    # A6.1: immutable private evidence, full report and technician quality signature.
    import copy
    file_ids = {"type": "array", "items": identifier, "minItems": 1, "maxItems": 9, "uniqueItems": True}
    text2000 = {"type": "string", "minLength": 1, "maxLength": 2000}
    part = strict({"name": {"type": "string", "minLength": 1, "maxLength": 100}, "model": {"type": "string", "minLength": 1, "maxLength": 100},
        "brand": {"type": "string", "minLength": 1, "maxLength": 100}, "quantity": {"type": "integer", "minimum": 1, "maximum": 999}})
    report_properties = {"order_id": identifier, "process_photos": file_ids, "fault_part_photos": {**file_ids, "minItems": 0}, "finish_photos": file_ids,
        "no_fault_parts": {"type": "boolean"}, "repair_plan": text2000, "fault_analysis": text2000,
        "parts_used": {"type": "array", "items": part, "maxItems": 20}, "no_parts": {"type": "boolean"}, "work_hours": {"type": "integer", "minimum": 1, "maximum": 1440, "description": "实际填报工时，单位分钟"}}
    schemas["ServiceProtectionRequest"] = strict({"order_id": identifier,
        "items": {"type": "array", "items": {"type": "string", "enum": ["SEAT_COVER", "STEERING_COVER", "FLOOR_MAT", "FENDER_COVER"]}, "minItems": 2, "maxItems": 4, "uniqueItems": True}, "photo_file_id": identifier})
    schemas["ServiceProtectionRequest"]["properties"]["items"]["allOf"] = [{"contains": {"const": "SEAT_COVER"}}, {"contains": {"const": "STEERING_COVER"}}]
    schemas["ServiceReportRequest"] = strict(report_properties)
    schemas["ServiceReportRequest"]["allOf"] = [
        {"if": {"properties": {"no_fault_parts": {"const": True}}}, "then": {"properties": {"fault_part_photos": {"maxItems": 0}}}, "else": {"properties": {"fault_part_photos": {"minItems": 1}}}},
        {"if": {"properties": {"no_parts": {"const": True}}}, "then": {"properties": {"parts_used": {"maxItems": 0}}}, "else": {"properties": {"parts_used": {"minItems": 1}}}}]
    nullable_id = {"anyOf": [identifier, {"type": "null"}]}
    protection_view = strict({"items": schemas["ServiceProtectionRequest"]["properties"]["items"], "uploaded_at": {"anyOf": [timestamp, {"type": "null"}]}, "photo_file_id": nullable_id})
    report_view = strict({**{k: v for k, v in report_properties.items() if k != "order_id"}, "report_id": identifier,
        "submitted_at": timestamp, "signed_at": {"anyOf": [timestamp, {"type": "null"}]}, "signature_file_id": nullable_id, "status": {"type": "string", "enum": ["SUBMITTED", "SIGNED"]}})
    schemas["ServiceWork"] = strict({"order_id": identifier, "order_status": {"type": "string", "enum": fulfillment_states},
        "protection": {"anyOf": [protection_view, {"type": "null"}]}, "report": {"anyOf": [report_view, {"type": "null"}]}})
    for method, path, role, title, request in [
        ("post", "/api/check/protection/upload", "MERCHANT", "提交本店施工防护证据", "ServiceProtectionRequest"),
        ("post", "/api/tech/report/submit", "TECHNICIAN", "本人提交完整施工报工", "ServiceReportRequest"),
        ("post", "/api/tech/sign", "TECHNICIAN", "本人质检签字并送核销", None),
        ("get", "/api/merchant/orders/{id}/work", "MERCHANT", "本店施工记录", None),
        ("get", "/api/tech/orders/{id}/work", "TECHNICIAN", "本人工单施工记录", None),
        ("get", "/api/merchant/orders/{id}/work/files/{file}/access", "MERCHANT", "本店施工证据图片临时访问", None),
        ("get", "/api/tech/orders/{id}/work/files/{file}/access", "TECHNICIAN", "本人施工证据图片临时访问", None)]:
        item = operation(method, path, title, "F15,F18")
        item["x-roles"] = role
        item["x-implementation-status"] = "service-work-backend-implemented"
        item["description"] = "严格身份与订单归属；写入前检查确认1/3、争议、防护和私有安全图片。完整报工不可改，PNG质检签名后原子进入PENDING_VERIFY；不完成核销。每次幂等重放仍复核权限和证据。无查询参数，响应no-store；见 SERVICE_WORK.md。三类图片之间不得重复。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = ({"$ref": "#/components/schemas/"+request} if request else strict({"order_id": identifier, "signature_file_id": identifier}))
        data_schema = strict({"url": {"type": "string", "format": "uri"}, "expires_at": timestamp}) if path.endswith("/access") else {"$ref": "#/components/schemas/ServiceWork"}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": data_schema}}]}
        item["responses"]["404"] = {"description": "不存在/非本店或非本人工单/非关联文件，40400"}
        item["responses"]["409"] = {"description": "40905已提交不可改或状态/历史异常；43001缺接车；43002缺有效防护；43003缺车主确认；43004未本人接单；43005缺完整报工；43007未解决争议"}
        item["responses"]["422"] = {"description": "42200图片非本人CLEAN文件、签名非PNG或已用于其他证据"}
        item["responses"]["503"] = {"description": "数据库/对象存储未配置或事务失败，50300；使用原键重试"}
        document["paths"][path] = {method: item}
    for method, old, new in [("post", "/api/merchant/files/upload", "/api/tech/files/upload"), ("get", "/api/merchant/files/{id}/access", "/api/tech/files/{id}/access")]:
        item = copy.deepcopy(document["paths"][old][method])
        item["operationId"] = re.sub(r"[^A-Za-z0-9]+", "_", f"{method}_{new}").strip("_")
        item["summary"] = "技师本人私有图片" + ("上传" if method == "post" else "临时访问")
        item["x-implementation-status"] = "service-work-backend-implemented"
        item["x-feature-ids"] = ["F18"]
        item["x-roles"] = "TECHNICIAN"
        item["description"] = "仅当前AppID下正式有效技师会话与绑定；扫描、类型、大小、限流、幂等沿用私有图片基础设施。仅本人原始上传可访问；跨上传者的工单关联图片须走work/files接口。"
        document["paths"][new] = {method: item}
    action["description"] = "商家通用RECEIVE/START_SERVICE/FINISH_SERVICE分别拒绝43001/43004/43005。专用接车、本人接单、本人完整报工+质检签字是唯一对应入口；核销仍待A7。"
    action["responses"]["409"]["description"] = "40905非法状态；43001改用接车检查；43004改用本人接单；43005改用本人报工并质检签字；43006核销未接入"
    schemas["MerchantOrder"]["properties"]["allowed_actions"]["description"] = "RECEIVE/START_SERVICE/FINISH_SERVICE均不再投影为商家动作；完工送核销走本人质检签字"
    redemption_receipt = strict({"redeemed_at": timestamp, "test_mode": {"type": "boolean"}})
    for method, path, role, title in [
        ("post", "/api/merchant/orders/{id}/redeem", "MERCHANT", "本店输入六位码核销"),
        ("get", "/api/merchant/orders/{id}/redemption", "MERCHANT", "本店核销记录"),
        ("get", "/api/order/{id}/redemption", "OWNER", "本人核销记录")]:
        item = operation(method, path, title, "F08,F16")
        item["x-roles"] = role
        item["x-implementation-status"] = "redemption-backend-implemented"
        item["description"] = "本店/本人授权、无查询参数、no-store。写入须完整施工证据与可信付款；同店同订单共享五次错误/十分钟限额。LOCAL_TEST仅隔离测试环境可核销且未真实扣款；正式微信付款未接入。核销与双审计同事务，重复不新增成功审计。见 ORDER_REDEMPTION.md。"
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = strict({"code": {"type": "string", "pattern": "^[0-9]{6}$", "writeOnly": True}})
            data_schema = strict({"order_id": identifier, "order_status": {"const": "COMPLETED"}, "redeemed_at": timestamp, "test_mode": {"type": "boolean"}, "changed": {"type": "boolean"}})
        else:
            data_schema = strict({"order_id": identifier, "redemption": {"anyOf": [redemption_receipt, {"type": "null"}]}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": data_schema}}]}
        item["responses"]["409"] = {"description": "40905状态/历史核销异常；43001/2/3/4/5施工证据缺失；43007争议未解决；43009付款缺失或异常"}
        item["responses"]["422"] = {"description": "42200核销码无效"}
        item["responses"]["429"] = {"description": "42900达到共享错误额度", "headers": {"Retry-After": {"schema": {"type": "integer", "minimum": 1}}}}
        item["responses"]["503"] = {"description": "50300数据库未配置/事务失败/正式付款通道未接入；原键重试"}
        document["paths"][path] = {method: item}
    action["description"] = "通用RECEIVE/START_SERVICE/FINISH_SERVICE/COMPLETE分别拒绝43001/43004/43005/43006；核销仅走专用redeem入口，已完成重复也不可绕过。"
    action["responses"]["409"]["description"] = "40905非法状态；43001/43004/43005/43006请使用各自业务专用入口"
    count = sum(len(value) for value in document["paths"].values())
    # A7.2a: immutable feedback, privately visible to the authenticated order owner.
    review_photos = {"type": "array", "items": identifier, "maxItems": 3, "uniqueItems": True}
    schemas["OrderReviewRequest"] = strict({"order_id": identifier,
        "rating": {"type": "integer", "minimum": 1, "maximum": 5},
        "content": {"type": "string", "minLength": 1, "maxLength": 500, "description": "去除首尾空白后计 Unicode 码点；提交后不可修改"},
        "photo_file_ids": review_photos})
    schemas["OwnerOrderReview"] = strict({"review_id": identifier,
        "rating": {"type": "integer", "minimum": 1, "maximum": 5},
        "content": schemas["OrderReviewRequest"]["properties"]["content"], "photo_file_ids": review_photos,
        "submitted_at": timestamp, "test_mode": {"type": "boolean"}})
    schemas["OrderReviewResult"] = strict({"order_id": identifier, "review": {"$ref": "#/components/schemas/OwnerOrderReview"}})
    reasons = ["ORDER_NOT_COMPLETED", "REDEMPTION_UNVERIFIED", "OPEN_DISPUTE", "PAYMENT_UNVERIFIED", "ALREADY_REVIEWED"]
    schemas["OrderReviewDetail"] = strict({"order_id": identifier, "can_submit": {"type": "boolean"},
        "unavailable_reason": {"anyOf": [{"type": "string", "enum": reasons}, {"type": "null"}]},
        "test_mode": {"anyOf": [{"type": "boolean"}, {"type": "null"}]},
        "review": {"anyOf": [{"$ref": "#/components/schemas/OwnerOrderReview"}, {"type": "null"}]}})
    for method, path, title, response in [("post", "/api/order/review", "本人提交唯一订单评价", "OrderReviewResult"),
        ("get", "/api/order/{id}/review", "本人查询评价资格和历史记录", "OrderReviewDetail")]:
        item = operation(method, path, title, "F08")
        item["x-roles"] = "OWNER"
        item["x-implementation-status"] = "owner-review-backend-implemented"
        item["description"] = "仅本人，COMPLETED且可信核销/付款一致，无未解决争议。1–5分、1–500字、0–3本人安全JPEG/PNG；不可修改，不公开，不计商家评分。无查询参数；严格JSON无重复键/尾随；全部响应no-store。幂等重放复核资格及图片，历史本人评价仍可读；见ORDER_REVIEWS.md。"
        for p in item["parameters"]:
            if p["in"] == "path": p["schema"] = identifier
        if method == "post": item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/OrderReviewRequest"}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/"+response}}}]}
        item["responses"]["404"] = {"description": "非本人、订单不存在或已删除，code=40400"}
        item["responses"]["409"] = {"description": "44001缺可信资格；44002已有不同评价"}
        item["responses"]["422"] = {"description": "图片非本人安全文件或不可用，code=42200"}
        item["responses"]["503"] = {"description": "数据库未配置或事务失败，code=50300；原载荷原键重试"}
        document["paths"][path] = {method: item}

    # A7.2c1: private summaries and explicit owner consent; no publication API.
    nullable_time = {"anyOf": [timestamp, {"type": "null"}]}
    schemas["PrivateExperienceSummary"] = strict({"version": {"const": 1},
        "work_minutes": {"type": "integer", "minimum": 1, "maximum": 1440},
        "part_kinds": {"type": "integer", "minimum": 0, "maximum": 20},
        "no_parts": {"type": "boolean"}, "recorded_month": {"type": "string", "pattern": "^[0-9]{4}-(0[1-9]|1[0-2])$"}})
    schemas["PrivateExperienceCard"] = strict({"card_id": identifier, "vehicle_id": identifier,
        "order_id": identifier, "archive_id": identifier, "title": {"const": "施工经验摘要"},
        "status": {"type": "string", "enum": ["DRAFT", "PENDING_REVIEW", "WITHDRAWN"]},
        "revision": {"type": "integer", "minimum": 0}, "test_mode": {"type": "boolean"},
        "consent_version": {"anyOf": [{"const": "experience-v1"}, {"type": "null"}]},
        "consented_at": nullable_time, "withdrawn_at": nullable_time,
        "summary": {"$ref": "#/components/schemas/PrivateExperienceSummary"}})
    schemas["ExperienceConsent"] = strict({"agree": {"const": True}, "consent_version": {"const": "experience-v1"}})
    for method, path, title in [("get", "/api/experience-cards", "本人车辆私有经验卡片"),
        ("post", "/api/experience-cards/{id}/consent", "本人授权卡片送审"),
        ("post", "/api/experience-cards/{id}/withdraw", "本人撤回经验授权")]:
        item = operation(method, path, title, "F12")
        item["x-roles"] = "OWNER"
        item["x-implementation-status"] = "private-experience-backend-implemented"
        item["description"] = "原车主且当前仍拥有车辆，no-store，结构化摘要不含自由文本/照片。测试不能送审；授权后待审核尚未公开；见EXPERIENCE_CARDS.md。"
        for p in item["parameters"]:
            if p["in"] == "path": p["schema"] = identifier
        if method == "get":
            item["parameters"].extend([{"name": "vehicle_id", "in": "query", "required": True, "schema": identifier},
                {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000}},
                {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50}}])
            response = strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/PrivateExperienceCard"}},
                "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer"}, "page_size": {"type": "integer"}})
        else:
            item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/ExperienceConsent"} if path.endswith("/consent") else strict({})
            response = strict({"card": {"$ref": "#/components/schemas/PrivateExperienceCard"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": response}}]}
        item["responses"]["404"] = {"description": "非本人或当前车辆不可用，40400"}
        item["responses"]["409"] = {"description": "45001测试/来源不可信；45002旧幂等键与当前revision不符"}
        item["responses"]["503"] = {"description": "50300数据库未配置或事务失败；原键原载荷重试"}
        document["paths"][path] = {method: item}

    for path, title, fields in [("/api/auth/operator/code", "运营密码核验后发送二次短信", {"account": {"type": "string"}, "password": {"type": "string", "writeOnly": True}}),
        ("/api/auth/operator/login", "运营账号密码与一次短信登录", {"account": {"type": "string"}, "password": {"type": "string", "writeOnly": True}, "sms_code": {"type": "string", "pattern": "^[0-9]{6}$", "writeOnly": True}}),
        ("/api/auth/operator/logout", "撤销当前运营短会话", {})]:
        item = operation("post", path, title, "F20", protected=path.endswith("logout"))
        item["x-roles"] = "OPERATOR"
        item["x-implementation-status"] = "operator-auth-implemented"
        item["description"] = "独立运营身份，密码+短信二次校验，JWT/数据库会话15分钟，无长期refresh；权限实时复核，no-store。正式短信未配置发码503。见OPERATOR_ACCOUNTS.md。"
        item["requestBody"]["content"]["application/json"]["schema"] = strict(fields)
        item["responses"]["429"] = {"description": "账号/IP/短信共享限流，42900"}
        item["responses"]["503"] = {"description": "身份/限流数据库或短信未配置，50300"}
        document["paths"][path] = {"post": item}

    schemas["PrivateExperienceCard"]["properties"]["status"]["enum"].extend(["PUBLISHED", "REJECTED"])
    reason = {"anyOf": [{"type": "string", "enum": ["INSUFFICIENT_DETAIL", "NOT_SUITABLE"]}, {"type": "null"}]}
    schemas["PrivateExperienceCard"]["properties"]["review_reason"] = reason
    schemas["PrivateExperienceCard"]["allOf"] = [{"if": {"properties": {"status": {"const": "REJECTED"}}}, "then": {"required": ["review_reason"]}}]
    schemas["ExperienceModerationRequest"] = strict({"revision": {"type": "integer", "minimum": 1}, "decision": {"type": "string", "enum": ["APPROVE", "REJECT"]}, "reason_code": reason})
    schemas["ExperienceModerationItem"] = strict({"card_id": identifier, "revision": {"type": "integer", "minimum": 1}, "title": {"const": "施工经验摘要"}, "summary": {"$ref": "#/components/schemas/PrivateExperienceSummary"}, "model_id": {"anyOf": [identifier, {"type": "null"}]}})
    schemas["PublicExperience"] = strict({"experience_id": {"type": "string", "format": "uuid"}, "title": {"const": "施工经验摘要"}, "summary": {"$ref": "#/components/schemas/PrivateExperienceSummary"}, "model_id": identifier, "published_at": timestamp})
    for method, path, title in [("get", "/api/admin/experience-cards", "运营经验待审列表"), ("post", "/api/admin/experience-cards/{id}/moderate", "运营批准或驳回指定授权版本"), ("get", "/api/community/experiences", "微信车主同款已发布经验")]:
        item = operation(method, path, title, "F12,F20")
        item["x-roles"] = "OWNER" if path.startswith("/api/community") else "OPERATOR with can_review"
        item["x-implementation-status"] = "experience-moderation-backend-implemented"
        item["description"] = "默认发布开关false；批准重核车主授权/可信来源/车型，固定理由驳回，UUID/revision/事务审计。公共DTO不含私有来源，测试永久排除，撤回/转移/来源异常实时隐藏，no-store。主入口微信小程序；见EXPERIENCE_PUBLICATION.md。"
        for p in item["parameters"]:
            if p["in"] == "path": p["schema"] = identifier
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/ExperienceModerationRequest"}
            response = strict({"card_id": identifier, "status": {"type": "string", "enum": ["PUBLISHED", "REJECTED"]}, "revision": {"type": "integer", "minimum": 2}, "decision": {"type": "string", "enum": ["APPROVE", "REJECT"]}, "reason_code": reason})
        elif path.startswith("/api/community"):
            item["parameters"].extend([{"name": "vehicle_id", "in": "query", "required": True, "schema": identifier}, {"name": "cursor", "in": "query", "schema": identifier}])
            response = strict({"items": {"type": "array", "maxItems": 20, "items": {"$ref": "#/components/schemas/PublicExperience"}}, "next_cursor": {"anyOf": [identifier, {"type": "null"}]}})
        else:
            item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000}}, {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50}}])
            response = strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/ExperienceModerationItem"}}, "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer"}, "page_size": {"type": "integer"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": response}}]}
        item["responses"]["404"] = {"description": "本人车辆/资源不可用，40400"}
        item["responses"]["409"] = {"description": "45001来源不可信；45003版本/授权变化；45004缺可信车型"}
        item["responses"]["503"] = {"description": "开关/依赖/事务不可用，50300；写使用原键重试"}
        document["paths"][path] = {method: item}

    # R1a merchant onboarding (V020). Feature flag off by default; see MERCHANT_ONBOARDING.md.
    categories = {"type": "string", "enum": ["MAINTENANCE", "TIRE", "REPAIR", "BEAUTY", "SERVICE", "SUPPLIES"]}
    region_code = {"type": "string", "pattern": "^[0-9]{6}$"}
    onboard_reason = {"anyOf": [{"type": "string", "enum": ["QUALIFICATION_INCOMPLETE", "CATEGORY_MISMATCH",
        "DUPLICATE_STORE", "REGION_QUOTA_FULL"]}, {"type": "null"}]}
    schemas["MerchantApplicationRequest"] = strict({
        "merchant_name": {"type": "string", "minLength": 2, "maxLength": 64, "description": "去除首尾空白后2–64字符，不含控制字符"},
        "category": categories, "region_code": region_code,
        "address": {"type": "string", "minLength": 1, "maxLength": 256},
        "contact_phone": {"type": "string", "pattern": "^1[3-9][0-9]{9}$"},
        "qualification_file_ids": {"type": "array", "minItems": 1, "maxItems": 9, "items": identifier,
            "description": "申请人本人私有上传且已扫描完成的 file_object id，互不相同"}})
    schemas["MerchantApplication"] = strict({
        "application_id": identifier, "merchant_name": {"type": "string"}, "category": categories,
        "region_code": region_code, "address": {"type": "string"}, "contact_phone": {"type": "string"},
        "status": {"type": "string", "enum": ["PENDING_REVIEW", "APPROVED", "REJECTED"]},
        "revision": {"type": "integer", "minimum": 1}, "merchant_id": {"anyOf": [identifier, {"type": "null"}]},
        "review_reason": onboard_reason, "qualification_file_ids": {"type": "array", "items": identifier},
        "created_at": timestamp, "updated_at": timestamp})
    schemas["MerchantApplicationSummary"] = strict({"application_id": identifier, "merchant_name": {"type": "string"},
        "category": categories, "region_code": region_code, "revision": {"type": "integer", "minimum": 1}, "submitted_at": timestamp})
    schemas["MerchantApplicationReview"] = strict({"revision": {"type": "integer", "minimum": 1},
        "decision": {"type": "string", "enum": ["APPROVED", "REJECTED"]}, "reason_code": onboard_reason,
        "merchant_id": {"anyOf": [identifier, {"type": "null"}]}, "decided_at": timestamp})
    schemas["MerchantOnboardingModeration"] = strict({"revision": {"type": "integer", "minimum": 1},
        "decision": {"type": "string", "enum": ["APPROVE", "REJECT"]}, "reason_code": onboard_reason})
    schemas["MerchantQuotaRequest"] = strict({"region_code": region_code, "category": categories,
        "max_active": {"type": "integer", "minimum": 0, "maximum": 100000}})
    schemas["MerchantQuota"] = strict({"region_code": region_code, "category": categories,
        "max_active": {"type": "integer", "minimum": 0}, "active_stores": {"type": "integer", "minimum": 0}})

    for method, path, title, response in (
        ("post", "/api/merchant-applications", "车主提交或重提入驻申请",
            strict({"application": {"$ref": "#/components/schemas/MerchantApplication"}, "revision": {"type": "integer", "minimum": 1}})),
        ("get", "/api/merchant-applications/mine", "本人当前入驻申请与不可变审核历史",
            strict({"application": {"anyOf": [{"$ref": "#/components/schemas/MerchantApplication"}, {"type": "null"}]},
                "reviews": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantApplicationReview"}}}))):
        item = operation(method, path, title, "F13")
        item["x-roles"] = "OWNER"
        item["x-implementation-status"] = "merchant-onboarding-backend-implemented"
        item["description"] = "默认开关false；一人一行申请且revision递增，资质文件必须为本人已扫描完成的私有上传；写用UUID幂等键。见MERCHANT_ONBOARDING.md。"
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/MerchantApplicationRequest"}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": response}}]}
        item["responses"]["404"] = {"description": "资源不存在或不可见，40400"}
        item["responses"]["409"] = {"description": "49001重复未终结申请；49002资质文件不可用"}
        item["responses"]["503"] = {"description": "开关/依赖/事务不可用，50300；写用原幂等键"}
        document["paths"][path] = {method: item}

    for method, path, title, response in (
        ("get", "/api/admin/merchant-applications", "运营待审入驻申请列表",
            strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantApplicationSummary"}},
                "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer", "minimum": 1},
                "page_size": {"type": "integer", "minimum": 1, "maximum": 50}})),
        ("get", "/api/admin/merchant-applications/{id}", "运营入驻申请详情与审核历史",
            strict({"application": {"$ref": "#/components/schemas/MerchantApplication"},
                "reviews": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantApplicationReview"}}})),
        ("get", "/api/admin/merchant-applications/{id}/files/{file}/access", "运营受控换取资质文件短时地址",
            strict({"url": {"type": "string"}, "expires_at": timestamp})),
        ("post", "/api/admin/merchant-applications/{id}/moderate", "运营按固定码批准或驳回入驻申请",
            strict({"application": {"$ref": "#/components/schemas/MerchantApplication"},
                "decision": {"type": "string", "enum": ["APPROVE", "REJECT"]}, "reason_code": onboard_reason,
                "merchant_id": {"anyOf": [identifier, {"type": "null"}]}}))):
        item = operation(method, path, title, "F13,F20")
        item["x-roles"] = "OPERATOR with can_onboard"
        item["x-implementation-status"] = "merchant-onboarding-backend-implemented"
        item["description"] = ("默认开关false；每次请求实时复核会话与can_onboard（不与can_review交叉）。列表只返回固定摘要，"
            "资质文件仅经受控接口换取短时地址；配额缺失视为0，批准锁序为申请→配额→merchant→staff_account，门店账号为m{id}且PENDING_ACTIVATION，不下发初始密码。见MERCHANT_ONBOARDING.md。")
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if method == "post":
            item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/MerchantOnboardingModeration"}
        elif path == "/api/admin/merchant-applications":
            item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
                {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": response}}]}
        item["responses"]["404"] = {"description": "申请或资质文件不可用，40400"}
        item["responses"]["409"] = {"description": "49003版本/状态变化；49010区域品类满额；49011配额低于有效门店数"}
        item["responses"]["429"] = {"description": "运营共享限流，42900"}
        item["responses"]["503"] = {"description": "开关/依赖/事务/文件服务不可用，50300；写用原幂等键"}
        document["paths"][path] = {method: item}

    for method, path, title, response in (
        ("get", "/api/admin/merchant-quotas", "运营查询区域品类配额", strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantQuota"}}})),
        ("put", "/api/admin/merchant-quotas", "运营设置区域品类配额", strict({"region_code": region_code, "category": categories,
            "max_active": {"type": "integer", "minimum": 0}, "active_stores": {"type": "integer", "minimum": 0}}))):
        item = operation(method, path, title, "F13,F20")
        item["x-roles"] = "OPERATOR with can_onboard"
        item["x-implementation-status"] = "merchant-onboarding-backend-implemented"
        item["description"] = "默认开关false；配额按(region_code,category)唯一，缺失即0（fail-closed）；调小不得低于当前有效门店数，同值重复设置不产生新审计。见MERCHANT_ONBOARDING.md。"
        if method == "put":
            item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/MerchantQuotaRequest"}}}}
            item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": response}}]}
        item["responses"]["409"] = {"description": "49011配额低于当前有效门店数"}
        item["responses"]["429"] = {"description": "运营共享限流，42900"}
        item["responses"]["503"] = {"description": "开关/依赖/事务不可用，50300；写用原幂等键"}
        # 同一路径可有多个方法：必须合并而不是整条覆盖，否则 GET 会被 PUT 顶掉。
        document["paths"].setdefault(path, {})[method] = item

    # R1b 门店员工与门店资料（V021）。店员业务同级、管理面限店长；员工码为轮换语义无幂等键。
    staff_roles = {"type": "string", "enum": ["STAFF", "TECHNICIAN"]}
    coordinate = {"anyOf": [{"type": "number", "minimum": -180, "maximum": 180}, {"type": "null"}]}
    latitude = {"anyOf": [{"type": "number", "minimum": -90, "maximum": 90}, {"type": "null"}]}
    schemas["MerchantStaffCreate"] = {
        "type": "object", "additionalProperties": False, "required": ["role", "display_name", "password"],
        "properties": {
            "role": staff_roles,
            "display_name": {"type": "string", "minLength": 2, "maxLength": 32},
            "phone": {"type": "string", "pattern": "^1[3-9][0-9]{9}$", "description": "店员必填；技师可选"},
            "password": {"type": "string", "minLength": 8, "maxLength": 64,
                "description": "8–64 位可打印 ASCII，须同时含字母与数字；响应不回显"}}}
    schemas["MerchantStaff"] = strict({
        "staff_id": identifier, "account": {"type": "string"}, "role": staff_roles,
        "display_name": {"type": ["string", "null"]}, "phone_masked": {"type": ["string", "null"]},
        "status": {"type": "string", "enum": ["ACTIVE", "DISABLED"]},
        "employee_code_issued": {"type": "boolean"}, "wechat_bound": {"type": "boolean"}, "created_at": timestamp})
    schemas["MerchantProfile"] = strict({
        "merchant_id": identifier, "name": {"type": "string"}, "address": {"type": "string"},
        "contact_phone": {"type": ["string", "null"]}, "lng": {"type": ["string", "null"]},
        "lat": {"type": ["string", "null"]}, "merchant_type": {"type": "integer", "minimum": 1, "maximum": 6},
        "region_code": {"type": "string"}, "status": {"type": "integer", "minimum": 0, "maximum": 2},
        "can_edit": {"type": "boolean"}})
    schemas["MerchantProfileUpdate"] = strict({
        "name": {"type": "string", "minLength": 2, "maxLength": 64},
        "address": {"type": "string", "minLength": 1, "maxLength": 256},
        "contact_phone": {"type": "string", "pattern": "^1[3-9][0-9]{9}$"},
        "lng": coordinate, "lat": latitude})

    for method, path, title, roles, response in (
        ("get", "/api/merchant/staff", "本店店员与技师列表",
            "MERCHANT/STAFF",
            strict({"items": {"type": "array", "items": {"$ref": "#/components/schemas/MerchantStaff"}},
                "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer", "minimum": 1},
                "page_size": {"type": "integer", "minimum": 1, "maximum": 50}})),
        ("post", "/api/merchant/staff", "店长新增本店店员或技师", "MERCHANT",
            {"$ref": "#/components/schemas/MerchantStaff"}),
        ("post", "/api/merchant/staff/{id}/disable", "店长停用本店员工并立即阻断原会话", "MERCHANT",
            strict({"staff_id": identifier, "status": {"type": "string", "enum": ["DISABLED"]},
                "sessions_revoked": {"type": "integer", "minimum": 0}})),
        ("post", "/api/merchant/staff/{id}/enable", "店长启用本店员工", "MERCHANT",
            strict({"staff_id": identifier, "status": {"type": "string", "enum": ["ACTIVE"]}})),
        ("post", "/api/merchant/staff/{id}/employee-code", "店长签发或轮换技师一次性员工码", "MERCHANT",
            strict({"staff_id": identifier, "employee_code": {"type": "string"}, "single_use": {"type": "boolean"}})),
        ("delete", "/api/merchant/staff/{id}/employee-code", "店长撤销技师员工码与微信绑定", "MERCHANT",
            strict({"staff_id": identifier, "employee_code_revoked": {"type": "boolean"}})),
    ):
        item = operation(method, path, title, "M-02,T-02")
        item["x-roles"] = roles
        item["x-implementation-status"] = "merchant-staff-backend-implemented"
        item["description"] = ("角色必须与会话令牌一致；跨店目标按不存在处理。停用为单事务：改状态+撤销该员工全部会话"
            "+（技师）撤销微信绑定，因此原令牌立即失效且刷新不可用。见 MERCHANT_STAFF.md。")
        for parameter in item["parameters"]:
            if parameter["in"] == "path": parameter["schema"] = identifier
        if path == "/api/merchant/staff":
            if method == "get":
                item["parameters"].extend([
                    {"name": "role", "in": "query", "schema": staff_roles},
                    {"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
                    {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
            else:
                item["requestBody"]["content"]["application/json"]["schema"] = {"$ref": "#/components/schemas/MerchantStaffCreate"}
        elif method == "post" and path.endswith("/employee-code"):
            # 轮换语义：不使用幂等键，避免一次性明文码落进幂等记录。
            item["parameters"] = [p for p in item["parameters"] if p["name"] != "Idempotency-Key"]
            item.pop("requestBody", None)
            item["description"] += " 本接口不使用 Idempotency-Key：每次调用都会让旧码失效，明文码仅本次响应可见。"
        elif method == "delete":
            item["description"] += " 本接口不使用 Idempotency-Key：结果为撤销，可安全重复调用。"
        else:
            item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {
                "type": "object", "additionalProperties": False, "maxProperties": 0}}}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": response}}]}
        item["responses"]["403"] = {"description": "店员调用管理面，40300"}
        item["responses"]["404"] = {"description": "员工不存在或不属于本店，40400"}
        item["responses"]["409"] = {"description": "40900员工已停用不可签发员工码；本店账号序号用尽"}
        item["responses"]["503"] = {"description": "依赖/事务不可用，50300；写用原幂等键"}
        document["paths"].setdefault(path, {})[method] = item

    for method, path, title, response in (
        ("get", "/api/merchant/profile", "本店对外资料",
            {"$ref": "#/components/schemas/MerchantProfile"}),
        ("put", "/api/merchant/profile", "店长更新本店对外资料（白名单字段+审计）",
            strict({"name": {"type": "string"}, "address": {"type": "string"},
                "contact_phone": {"type": ["string", "null"]}, "lng": {"type": ["string", "null"]},
                "lat": {"type": ["string", "null"]}})),
    ):
        item = operation(method, path, title, "M-02")
        item["x-roles"] = "MERCHANT/STAFF（PUT 仅 MERCHANT）"
        item["x-implementation-status"] = "merchant-staff-backend-implemented"
        item["description"] = ("只允许改 name/address/contact_phone/lng/lat；merchant_type、status、region_code、"
            "qualification 等一律不可经本接口修改，未知字段 400。改动写 audit_log。见 MERCHANT_STAFF.md。")
        if method == "put":
            item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {
                "$ref": "#/components/schemas/MerchantProfileUpdate"}}}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"},
            {"type": "object", "properties": {"data": response}}]}
        item["responses"]["403"] = {"description": "店员尝试修改资料，40300"}
        item["responses"]["404"] = {"description": "本店不存在或已停用，40400"}
        item["responses"]["503"] = {"description": "依赖/事务不可用，50300；写用原幂等键"}
        document["paths"].setdefault(path, {})[method] = item

    catalog_id = {"type": "integer", "minimum": 1, "maximum": 9007199254740991}
    catalog_text = lambda limit: {"type": "string", "maxLength": limit}
    catalog_fields = {
        "projects": {"project_name": catalog_text(128), "category": {"type": "integer", "minimum": 1, "maximum": 6},
            "service_content": catalog_text(2000), "quality_standard": catalog_text(2000),
            "base_price_low": {"type": "string", "pattern": r"^(0|[1-9][0-9]{0,5})\.[0-9]{2}$"},
            "base_price_high": {"type": "string", "pattern": r"^(0|[1-9][0-9]{0,5})\.[0-9]{2}$"}},
        "brands": {"name": catalog_text(64)},
        "series": {"name": catalog_text(128), "brand_id": catalog_id},
        "models": {"series_id": catalog_id, "year": {"type": "string", "pattern": r"^[0-9]{4}$"},
            "config_name": catalog_text(128), "power_type": {"type": "string", "enum": ["FUEL", "HYBRID", "ELECTRIC"]}},
    }
    for kind, fields in catalog_fields.items():
        common = {**fields, "sort_order": {"type": "integer", "minimum": 0, "maximum": 9999},
            "source_note": {"type": "string", "minLength": 2, "maxLength": 256}}
        schemas["CatalogCreate_" + kind] = strict(common)
        schemas["CatalogUpdate_" + kind] = strict({**common, "status": {"type": "integer", "enum": [0, 1]}, "revision": catalog_id})
    for method, path, title in (("get", "/api/admin/catalog/{kind}", "运营目录分页"),
        ("post", "/api/admin/catalog/{kind}", "新增未发布目录"), ("put", "/api/admin/catalog/{kind}/{id}", "维护目录与发布状态")):
        item = operation(method, path, title, "F17,F20,F02")
        item["x-roles"] = "OPERATOR with can_catalog"
        item["x-implementation-status"] = "catalog-governance-implemented"
        item["description"] = "四种kind：projects/brands/series/models。独立权限、开关默认false、UUID幂等、revision冲突409，上级不可变；详情见 CATALOG_GOVERNANCE.md。"
        for param in item["parameters"]:
            param["schema"] = {"type": "string", "enum": list(catalog_fields)} if param["name"] == "kind" else catalog_id
        if method == "get":
            item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
                {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
        else:
            if method == "put":
                item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True,
                    "schema": {"type": "string", "format": "uuid"}})
            prefix = "CatalogCreate_" if method == "post" else "CatalogUpdate_"
            item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {"oneOf": [
                {"$ref": "#/components/schemas/" + prefix + kind} for kind in catalog_fields]}}}}
        item["responses"]["409"] = {"description": "版本/重复名称/上级状态/历史引用冲突，40900"}
        item["responses"]["503"] = {"description": "开关关闭或事务依赖不可用，50300"}
        document["paths"].setdefault(path, {})[method] = item

    refund_fields = {
        "refund_id": identifier, "refund_no": {"type": "string", "pattern": "^R[a-f0-9]{32}$"},
        "payment_id": identifier, "amount": {"type": "string", "pattern": r"^(0|[1-9][0-9]{0,7})\.[0-9]{2}$"},
        "currency": {"type": "string", "const": "CNY"},
        "status": {"type": "string", "enum": ["REQUESTED", "REJECTED", "WITHDRAWN", "PENDING", "FAILED", "SUCCEEDED"]},
        "revision": identifier, "from_status": {"type": "string", "enum": ["PAID", "RECEIVED", "IN_SERVICE", "PENDING_VERIFY", "DISPUTED"]},
        "reason": {"type": "string", "minLength": 1, "maxLength": 256},
        "decision_note": {"type": ["string", "null"], "maxLength": 256},
        "owner_consented_at": {"type": "string", "format": "date-time"},
        "merchant_consented_at": {"type": ["string", "null"], "format": "date-time"},
        "refunded_at": {"type": ["string", "null"], "format": "date-time"}, "test_mode": {"type": "boolean"}}
    schemas["OrderRefund"] = strict(refund_fields)
    schemas["OrderRefundDetail"] = strict({"order_id": identifier, "order_status": {"type": "string", "enum": ["PENDING_PAYMENT", "PAID", "RECEIVED", "IN_SERVICE", "PENDING_VERIFY", "COMPLETED", "DISPUTED", "CLOSED"]},
        "can_request": {"type": "boolean"}, "refund": {"oneOf": [{"$ref": "#/components/schemas/OrderRefund"}, {"type": "null"}]}})
    for method, path, title, role, body in (
        ("get", "/api/order/{id}/refund", "本人订单取消退款记录", "OWNER", None),
        ("post", "/api/order/{id}/refund", "本人申请双方同意全额退款", "OWNER", strict({"reason": refund_fields["reason"]})),
        ("post", "/api/order/{id}/refund/withdraw", "本人撤回待决定申请", "OWNER", strict({"revision": identifier})),
        ("get", "/api/merchant/orders/{id}/refund", "本店店长退款记录", "MERCHANT", None),
        ("post", "/api/merchant/orders/{id}/refund/decision", "店长按申请版本决定", "MERCHANT", {"type": "object", "additionalProperties": False, "required": ["revision", "decision"], "properties": {"revision": identifier, "decision": {"type": "string", "enum": ["ACCEPT", "REJECT"]}, "note": refund_fields["reason"]}}),
        ("post", "/api/merchant/orders/{id}/refund/reconcile", "本店店长可信退款查询恢复", "MERCHANT", {"type": "object", "additionalProperties": False, "maxProperties": 0}),
    ):
        item = operation(method, path, title, "F07,F08")
        item["x-roles"] = role
        item["x-implementation-status"] = "order-refunds-local-test-implemented"
        item["description"] = "全额按服务器原支付冻结，双方同意后关闭订单、释放容量，资金状态独立。申请时订单继续原流程，状态变化须撤回重提。拒绝必须note；核对不使用幂等键。正式微信渠道503，测试成功无真实资金。见ORDER_REFUNDS.md。"
        for param in item["parameters"]:
            if param["in"] == "path": param["schema"] = identifier
        if path.endswith("/reconcile"):
            item["parameters"] = [p for p in item["parameters"] if p["name"] != "Idempotency-Key"]
        if body is not None: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/OrderRefundDetail"}}}]}
        for code, message in (("403", "身份无退款权限"), ("404", "本人或本店订单不存在"), ("409", "订单/申请版本/可信支付变化"), ("503", "数据库或渠道尚不可用")): item["responses"][code] = {"description": message}
        document["paths"].setdefault(path, {})[method] = item
    callback = operation("post", "/api/refunds/callback/LOCAL_TEST", "隔离测试退款签名通知", "F08")
    callback["security"] = []
    callback["parameters"] = [{"name": name, "in": "header", "required": True, "schema": {"type": "string"}} for name in ("X-Test-Timestamp", "X-Test-Nonce", "X-Test-Signature")]
    callback["x-implementation-status"] = "order-refunds-local-test-implemented"
    callback["description"] = "仅隔离测试开关和local-payment-test配置启用；原文最多16KiB，HMAC五分钟时间窗，事件哈希去重、原支付/退款绑定、全额校验与审计。不是微信官方协议。字段见ORDER_REFUNDS.md。"
    callback["requestBody"] = {"required": True, "content": {"application/json": {"schema": strict({"event_id": {"type": "string", "format": "uuid"}, "refund_id": identifier, "refund_no": refund_fields["refund_no"], "payment_id": identifier, "channel_refund_no": {"type": "string", "pattern": "^[a-zA-Z0-9_-]{1,128}$"}, "status": {"type": "string", "enum": ["SUCCEEDED", "FAILED"]}, "amount": refund_fields["amount"], "currency": refund_fields["currency"], "occurred_at": {"type": "string", "format": "date-time"}})}}}
    callback["responses"]["200"]["content"]["application/json"]["schema"] = strict({"code": {"type": "string", "const": "SUCCESS"}})
    for code, message in (("400", "严格通知字段错误"), ("401", "验签或时间窗失败"), ("404", "测试渠道关闭或退款不存在"), ("409", "原支付/全额/事件哈希/供应商流水冲突"), ("503", "应用事务失败，可重放通知")): callback["responses"][code] = {"description": message}
    document["paths"]["/api/refunds/callback/LOCAL_TEST"] = {"post": callback}

    commission_money = {"type": "string", "pattern": r"^-?(0|[1-9][0-9]*)\.[0-9]{2}$"}
    commission_date = {"type": ["string", "null"], "format": "date-time"}
    schemas["CommissionPolicy"] = strict({"state": {"type": "string", "enum": ["CONFIGURED", "UNCONFIGURED"]}, "rate_percent": {"type": ["string", "null"], "enum": ["3.00", None]}, "effective_from": commission_date, "waived_until": commission_date, "rule_version": {"type": ["string", "null"], "enum": ["R2C_V1", None]}})
    schemas["CommissionTotals"] = strict({"accrued": commission_money, "reversed": commission_money, "net": commission_money, "pending_count": {"type": "integer", "minimum": 0}})
    nullable_id = {"type": ["integer", "null"], "minimum": 1, "maximum": 9007199254740991}
    schemas["CommissionEntry"] = strict({"entry_id": identifier, "order_id": identifier, "payment_id": identifier, "refund_id": nullable_id,
        "kind": {"type": "string", "enum": ["ACCRUAL", "WAIT_REVIEW", "REFUND_REVERSAL", "REFUND_NO_CHARGE"]}, "base_amount": commission_money,
        "rate_percent": {"type": ["string", "null"], "enum": ["3.00", None]}, "exemption": {"type": "string", "enum": ["NEW_MERCHANT", "NONE", "WAIT_POLICY", "WAIT_LEGACY", "WAIT_COUPON", "REFUND_BEFORE_COMPLETION"]},
        "amount": {"type": ["string", "null"], "pattern": commission_money["pattern"]}, "currency": {"type": "string", "const": "CNY"},
        "occurred_at": {"type": "string", "format": "date-time"}, "test_mode": {"type": "boolean"}, "rule_version": {"type": "string", "const": "R2C_V1"},
        "reference_entry_id": nullable_id, "booked_at": {"type": "string", "format": "date-time"}, "waived_until": commission_date})
    schemas["CommissionPage"] = strict({"merchant_id": identifier, "policy": {"$ref": "#/components/schemas/CommissionPolicy"},
        "summary": strict({"real": {"$ref": "#/components/schemas/CommissionTotals"}, "test": {"$ref": "#/components/schemas/CommissionTotals"}}),
        "page": {"type": "integer", "minimum": 1, "maximum": 1000000}, "page_size": {"type": "integer", "minimum": 1, "maximum": 50},
        "total": {"type": "integer", "minimum": 0}, "items": {"type": "array", "items": {"$ref": "#/components/schemas/CommissionEntry"}}})
    item = operation("get", "/api/merchant/commissions", "本店店长只读佣金应计账单", "F16")
    item["x-roles"] = "MERCHANT"
    item["x-implementation-status"] = "commission-ledger-implemented"
    item["description"] = "下单冻结3%/前三自然月免佣，核销应计、可信退款按原台账冲销；无应计退款0记录，未知政策/旧单/券金额null待核对。汇总按全店、真实和测试分开，非已结算资金；见COMMISSIONS.md。"
    item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}}, {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
    item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "properties": {"data": {"$ref": "#/components/schemas/CommissionPage"}}}]}
    for code, message in (("401", "门店会话失效"), ("403", "非店长无账单权限"), ("503", "数据库未配置或不可用")): item["responses"][code] = {"description": message}
    document["paths"]["/api/merchant/commissions"] = {"get": item}

    match_row={"type":"object","additionalProperties":False,"required":["vehicle_id","model_id","model_name","current_mileage","plate_no_masked","vin_masked"],"properties":{
        "vehicle_id":{"type":"integer","minimum":1,"maximum":9007199254740991},"model_id":{"type":"integer","minimum":0},
        "model_name":{"type":"string"},"current_mileage":{"type":"integer","minimum":0},"plate_no_masked":{"type":"string"},"vin_masked":{"type":"string"}}}
    document["paths"]["/api/vehicle/match-plate"]={"post":{
        "summary":"本人车牌精确匹配（只读）","x-roles":"正式车主","x-implementation-status":"core-implemented",
        "description":"只查有效会话的本人未删除车辆，不证明所有权、不解码车型。JSON单字段，不接受query，无需幂等键；no-store。详见VEHICLE_ENTRY.md。",
        "security":[{"bearerAuth":[]}],"requestBody":{"required":True,"content":{"application/json":{"schema":{
            "type":"object","additionalProperties":False,"required":["plate_no"],"properties":{"plate_no":{"type":"string","minLength":1,"maxLength":32}}}}}},
        "responses":{"200":{"description":"脱敏匹配结果，无匹配空数组","content":{"application/json":{"schema":{
            "allOf":[{"$ref":"#/components/schemas/ApiResponse"},{"type":"object","properties":{"data":{"type":"object","additionalProperties":False,"required":["matches"],"properties":{"matches":{"type":"array","maxItems":20,"items":match_row}}}}}]}}}},
            "400":{"description":"body/query无效"},"401":{"description":"会话失效"},"403":{"description":"非车主身份"},"409":{"description":"本人旧记录过多需核对"},"503":{"description":"数据库未配置或不可用"}}}}

    point_fields = {"lng": {"type": "number", "minimum": -180, "maximum": 180},
                    "lat": {"type": "number", "minimum": -90, "maximum": 90},
                    "coordinate_system": {"type": "string", "const": "GCJ02"}}
    source_field = {"type": ["string", "null"], "enum": ["MERCHANT_CONFIRMED", None]}
    distance_fields = {"distance_meters": {"type": ["integer", "null"], "minimum": 0, "maximum": 20015115}, "location_source": source_field}
    schemas["NearbyMerchantQuote"] = strict({**schemas["MerchantQuote"]["properties"], **distance_fields})
    schemas["NearbyMerchantPage"] = strict({"items": {"type": "array", "maxItems": 100, "items": {"$ref": "#/components/schemas/NearbyMerchantQuote"}},
        "total": {"type": "integer", "minimum": 0}, "page": {"type": "integer", "minimum": 1, "maximum": 1000000}, "page_size": {"type": "integer", "minimum": 1, "maximum": 100}})
    schemas["MerchantDistance"] = strict({"merchant_id": identifier, **distance_fields})
    location_fields = {"lng": {"type": "string", "pattern": r"^-?(0|[1-9][0-9]{0,2})(\.[0-9]{1,7})?$", "description": "经度范围 -180..180"},
        "lat": {"type": "string", "pattern": r"^-?(0|[1-9][0-9]{0,2})(\.[0-9]{1,7})?$", "description": "纬度范围 -90..90"},
        "coordinate_system": {"type": "string", "const": "GCJ02"}, "source": {"type": "string", "const": "MERCHANT_CONFIRMED"}}
    schemas["MerchantLocation"] = strict({**location_fields, "version": identifier})
    nullable_location = {"anyOf": [{"$ref": "#/components/schemas/MerchantLocation"}, {"type": "null"}]}
    schemas["MerchantLocationRead"] = strict({"can_edit": {"type": "boolean"}, "location": nullable_location})
    schemas["MerchantLocationSaved"] = strict({"location": nullable_location})
    schemas["MerchantLocationInput"] = {"oneOf": [strict(location_fields), strict({k: {"type": "null"} for k in location_fields})]}
    nearby_input = strict({**point_fields, "sort": {"type": "string", "enum": ["distance_asc", "price_asc", "price_desc"]},
        "region_code": {"type": ["string", "null"], "pattern": "^[0-9]{6}$"}, "page": {"type": "integer", "minimum": 1, "maximum": 1000000}, "page_size": {"type": "integer", "minimum": 1, "maximum": 100}})
    for method, path, summary, role, body, result in (
        ("get", "/api/merchant/location", "本店已确认位置", "MERCHANT/STAFF", None, "MerchantLocationRead"),
        ("put", "/api/merchant/location", "店长确认或清除 GCJ02 门店位置", "MERCHANT", {"$ref": "#/components/schemas/MerchantLocationInput"}, "MerchantLocationSaved"),
        ("post", "/api/service/project/{id}/nearby-merchants", "车主附近报价与直线距离", "OWNER", nearby_input, "NearbyMerchantPage"),
        ("post", "/api/service/merchant/{id}/distance", "车主与门店的直线距离", "OWNER", strict(point_fields), "MerchantDistance"),
    ):
        item = operation(method, path, summary, "F06")
        item["x-roles"] = role; item["x-implementation-status"] = "implemented"
        item["description"] = "no-store；严格正文且不接受查询参数。旧坐标不推断；只使用店长明确确认的GCJ02位置，不代表平台现场核实。详见MERCHANT_LOCATION.md。"
        if method == "post":
            item["parameters"] = [p for p in item["parameters"] if p["in"] != "header"]
            item["x-read-only"] = True
            item["description"] += " 只读POST，不持久化车主位置、不写业务审计；位置仅在正文，不进入URL。"
        if method == "put":
            item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
            item["description"] += " MERCHANT_STAFF_ENABLED=true；同事务幂等和审计，清除保留版本。"
        if body is not None: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
        if "{id}" in path: item["parameters"][0]["schema"] = identifier
        item["responses"].update({"404": {"description": "门店或标准项目已不可用"}, "503": {"description": "开关关闭、服务未配置或数据库失败"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    schemas["ReviewPublicationState"] = strict({"order_id": identifier, "version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
        "consented": {"type": "boolean"}, "visible": {"type": "boolean"}, "can_publish": {"type": "boolean"},
        "unavailable_reason": {"type": ["string", "null"], "enum": ["TEST_ORDER", "PUBLIC_SOURCE_UNVERIFIED", None]}})
    schemas["PublicRating"] = strict({"rating": {"type": "integer", "minimum": 1, "maximum": 5}, "submitted_date": {"type": "string", "format": "date"}})
    schemas["PublicRatingSummary"] = strict({"count": {"type": "integer", "minimum": 0}, "rating_sum": {"type": "integer", "minimum": 0},
        "average_rating": {"type": ["string", "null"], "pattern": r"^[1-5]\.[0-9]{2}$", "description": "全有效样本星级平均，四舍五入两位；零样本null"}})
    schemas["PublicRatingPage"] = strict({"merchant_id": identifier, "page": {"type": "integer", "minimum": 1, "maximum": 1000000},
        "page_size": {"type": "integer", "minimum": 1, "maximum": 50}, "summary": {"$ref": "#/components/schemas/PublicRatingSummary"},
        "items": {"type": "array", "maxItems": 50, "items": {"$ref": "#/components/schemas/PublicRating"}}})
    for method, path, summary, result in (
        ("get", "/api/order/{id}/review-publication", "本人独立公开星级授权状态", "ReviewPublicationState"),
        ("put", "/api/order/{id}/review-publication", "本人授权或撤销公开星级", "ReviewPublicationState"),
        ("get", "/api/service/merchant/{id}/ratings", "车主查看匿名公开星级与汇总", "PublicRatingPage"),
    ):
        item = operation(method, path, summary, "F06")
        item["x-roles"] = "OWNER"; item["x-implementation-status"] = "rating-consent-implemented"
        item["parameters"][0]["schema"] = identifier
        item["description"] = "no-store；有效本人会话；独立可撤销授权，历史私有评价不迁移。只公开评分和UTC日期，无正文/照片/身份/订单标识；实时真实WECHAT履约资格过滤与可复算平均，测试评价不公开。详见PUBLIC_RATINGS.md。"
        if method == "put":
            item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
            item["requestBody"] = {"required": True, "content": {"application/json": {"schema": strict({"publish": {"type": "boolean"}, "expected_version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991}})}}}
            item["description"] += " 不接受query；旧版本409，重放重新授权且不重复变更；客户端写后GET刷新，历史响应不是当前可见状态。"
        elif "ratings" in path:
            item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}},
                {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
        item["responses"].update({"404": {"description": "本人评价/订单或公开门店不可用"}, "409": {"description": "公开资格或版本已变化"}, "503": {"description": "数据库未配置或不可用"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    review_text = {"type": "string", "minLength": 1, "maxLength": 300}
    text_version = {"type": "integer", "minimum": 0, "maximum": 9007199254740991}
    text_reason = {"type": ["string", "null"], "enum": ["PRIVACY", "ABUSE", "OFF_TOPIC", "UNVERIFIABLE", None]}
    schemas["PublicReviewTextState"] = strict({"order_id": identifier, "version": text_version,
        "status": {"type": "string", "enum": ["NONE", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"]},
        "public_text": {"type": ["string", "null"], "maxLength": 300}, "reason_code": text_reason,
        "visible": {"type": "boolean"}, "can_submit": {"type": "boolean"}, "unavailable_reason": {"type": ["string", "null"], "enum": ["DISABLED", "PUBLIC_SOURCE_UNVERIFIED", None]}})
    schemas["PublicReviewTextPreview"] = strict({"public_text": review_text, "redaction_version": {"type": "string", "const": "TEXT_REDACTION_V1"}})
    schemas["PublicReviewTextInput"] = {"oneOf": [strict({"action": {"type": "string", "const": "SUBMIT"}, "expected_version": text_version, "public_text": review_text, "consent_version": {"type": "string", "const": "R4B2_TEXT_V1"}}), strict({"action": {"type": "string", "const": "WITHDRAW"}, "expected_version": text_version})]}
    schemas["PublicReviewTextModerationInput"] = {"oneOf": [strict({"expected_version": text_version, "decision": {"type": "string", "const": "APPROVE"}, "reason_code": {"type": "null"}}), strict({"expected_version": text_version, "decision": {"type": "string", "const": "REJECT"}, "reason_code": {"type": "string", "enum": ["PRIVACY", "ABUSE", "OFF_TOPIC", "UNVERIFIABLE"]}})]}
    schemas["PublicReviewTextModerationResult"] = strict({"review_id": identifier, "version": identifier, "status": {"type": "string", "enum": ["APPROVED", "REJECTED"]}})
    text_page_fields = {"page": {"type": "integer", "minimum": 1, "maximum": 1000000}, "page_size": {"type": "integer", "minimum": 1, "maximum": 50}, "total": {"type": "integer", "minimum": 0}}
    schemas["PublicReviewTextQueue"] = strict({**text_page_fields, "items": {"type": "array", "maxItems": 50, "items": strict({"review_id": identifier, "version": identifier, "public_text": review_text, "can_approve": {"type": "boolean"}})}})
    schemas["PublicReviewTextPage"] = strict({**text_page_fields, "merchant_id": identifier, "items": {"type": "array", "maxItems": 50, "items": strict({"public_id": {"type": "string", "format": "uuid"}, "public_text": review_text, "published_date": {"type": "string", "format": "date"}})}})
    for method, path, summary, role, body, result in (
        ("get", "/api/order/{id}/public-review-text", "本人独立公开文本状态", "OWNER", None, "PublicReviewTextState"),
        ("post", "/api/order/{id}/public-review-text/preview", "本人独立文本只读脱敏预览", "OWNER", strict({"public_text": review_text}), "PublicReviewTextPreview"),
        ("put", "/api/order/{id}/public-review-text", "本人提交或撤销公开文本授权", "OWNER", {"$ref": "#/components/schemas/PublicReviewTextInput"}, "PublicReviewTextState"),
        ("get", "/api/admin/review-texts", "运营待审独立公开文本", "OPERATOR can_review", None, "PublicReviewTextQueue"),
        ("post", "/api/admin/review-texts/{id}/moderate", "运营审核确切授权文本", "OPERATOR can_review", {"$ref": "#/components/schemas/PublicReviewTextModerationInput"}, "PublicReviewTextModerationResult"),
        ("get", "/api/service/merchant/{id}/review-texts", "门店获批公开文本列表", "OWNER", None, "PublicReviewTextPage"),
    ):
        item = operation(method, path, summary, "F06"); item["x-roles"] = role; item["x-implementation-status"] = "public-review-text-implemented"
        item["description"] = "no-store；独立文本授权与星级分开；规则预览不覆盖所有语义隐私，人工can_review审核必需；真实非测试来源实时复核。REVIEW_TEXT_PUBLICATION_ENABLED默认false；关闭可本人读取/撤销但不投稿/审核/公开。详见PUBLIC_REVIEW_TEXTS.md。"
        if "{id}" in path: item["parameters"][0]["schema"] = identifier
        if "preview" in path:
            item["parameters"] = [p for p in item["parameters"] if p["in"] != "header"]; item["x-read-only"] = True
            item["description"] += " 只读POST，无幂等键，不持久化原文或写业务审计。"
        if method == "put": item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
        if path in ("/api/admin/review-texts", "/api/service/merchant/{id}/review-texts"):
            item["parameters"].extend([{"name": "page", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1000000, "default": 1}}, {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 50, "default": 20}}])
        if body is not None: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
        item["responses"].update({"404": {"description": "本人评价或门店不存在/不可用"}, "409": {"description": "资格、版本或授权已变化"}, "422": {"description": "文本尚未规范脱敏，请重做预览和授权"}, "503": {"description": "开关关闭或数据库不可用"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    schemas["ReviewSummaryConsent"] = strict({"order_id": identifier, "version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
        "text_version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991}, "consented": {"type": "boolean"}, "effective": {"type": "boolean"}, "can_consent": {"type": "boolean"}})
    schemas["ReviewSummaryConsentInput"] = strict({"consented": {"type": "boolean"}, "expected_version": text_version, "expected_text_version": text_version,
        "consent_version": {"type": "string", "const": "R4B2_SUMMARY_V1"}})
    schemas["ReviewSummary"] = strict({"merchant_id": identifier,
        "status": {"type": "string", "enum": ["AI_READY", "FALLBACK", "EMPTY", "DISABLED", "SOURCE_CHANGED"]},
        "method": {"type": ["string", "null"], "enum": ["AI_EXTRACTIVE_V1", "EXACT_EXTRACT_V1", None]},
        "sample_count": {"type": "integer", "minimum": 0, "maximum": 20}, "eligible_count": {"type": "integer", "minimum": 0},
        "highlights": {"type": "array", "maxItems": 3, "items": strict({"public_id": {"type": "string", "format": "uuid"}, "quote": {"type": "string", "minLength": 1, "maxLength": 300}})},
        "sources": {"type": "array", "maxItems": 3, "items": strict({"public_id": {"type": "string", "format": "uuid"}, "public_text": review_text, "published_date": {"type": "string", "format": "date"}})}})
    for method, path, summary, body, result in (
        ("get", "/api/order/{id}/review-summary-consent", "本人第三方AI用途授权状态", None, "ReviewSummaryConsent"),
        ("put", "/api/order/{id}/review-summary-consent", "本人授予或撤销当前批准文字AI用途", {"$ref": "#/components/schemas/ReviewSummaryConsentInput"}, "ReviewSummaryConsent"),
        ("post", "/api/service/merchant/{id}/review-summary", "主动生成可核对来源的评价原句要点", strict({}), "ReviewSummary"),
    ):
        item = operation(method, path, summary, "F06"); item["x-roles"] = "OWNER"; item["x-implementation-status"] = "source-checked-review-extracts-implemented"
        item["parameters"][0]["schema"] = identifier
        item["description"] = "no-store，无query；REVIEW_SUMMARY_ENABLED与REVIEW_TEXT_PUBLICATION_ENABLED默认关闭；原文字/星级授权不推断第三方AI用途，绑定当前批准版本，改稿需重新授权。最多最近20条/3条完整原句，明确原文降级，生成后重检来源，不持久化结果。详见REVIEW_SUMMARIES.md。"
        if method == "post": item["parameters"] = [p for p in item["parameters"] if p["in"] != "header"]; item["x-read-only"] = True
        if method == "put": item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
        if body is not None: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
        item["responses"].update({"404": {"description": "本人订单评价或门店不可用"}, "409": {"description": "用途授权/当前批准文本版本已变化"}, "503": {"description": "数据库未配置或不可用"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    schemas["AiPlanProject"] = strict({"project_id": identifier, "project_name": {"type": "string"}, "category": {"type": "integer", "minimum": 1, "maximum": 6}, "service_content": {"type": "string"}})
    schemas["AiPlanCatalog"] = strict({"items": {"type": "array", "maxItems": 40, "items": {"$ref": "#/components/schemas/AiPlanProject"}}, "total": {"type": "integer", "minimum": 0}, "limit": {"type": "integer", "const": 40}})
    schemas["AiServicePlanInput"] = strict({"question": {"type": "string", "minLength": 1, "maxLength": 300}, "requested_project_ids": {"type": "array", "maxItems": 3, "uniqueItems": True, "items": identifier}, "consent_to_provider": {"type": "boolean", "const": True}})
    schemas["AiServicePlan"] = strict({"status": {"type": "string", "enum": ["AI_READY", "MANUAL", "EMPTY", "SOURCE_CHANGED"]}, "question": {"type": "string", "minLength": 1, "maxLength": 300}, "candidate_count": {"type": "integer", "minimum": 0, "maximum": 40},
        "items": {"type": "array", "maxItems": 8, "items": strict({"project_id": identifier, "project_name": {"type": "string"}, "category": {"type": "integer", "minimum": 1, "maximum": 6}, "tier": {"type": "string", "enum": ["REQUESTED", "RECOMMENDED", "OPTIONAL"]}, "reason_code": {"type": "string", "enum": ["OWNER_SELECTED", "MODEL_MATCH"]}, "evidence_quote": {"type": "string", "minLength": 1, "maxLength": 300}})}})
    for method, path, summary, body, result in (
        ("get", "/api/ai/service-plan/catalog", "结构化建议的有效限定目录", None, "AiPlanCatalog"),
        ("post", "/api/ai/service-plan", "只读生成有效目录项目建议", {"$ref": "#/components/schemas/AiServicePlanInput"}, "AiServicePlan"),
    ):
        item = operation(method, path, summary, "F05,F06"); item["x-roles"] = "OWNER"; item["x-implementation-status"] = "catalog-based-service-plan-implemented"
        item["description"] = "no-store、无query；AI_SERVICE_PLAN_ENABLED默认false，明确第三方问题用途确认，规范问题+公开目录最多40候选；车主指定独立REQUESTED，模型只推荐/可选，完整问题证据校验；返回后重核会话/目录，无价格或车辆档案。跳转当前服务详情/报价/预约，详见AI_SERVICE_PLANS.md。"
        if method == "post": item["parameters"] = [p for p in item["parameters"] if p["in"] != "header"]; item["x-read-only"] = True
        if body is not None: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
        item["responses"].update({"404": {"description": "明确指定项目已停用/不可用"}, "503": {"description": "数据库未配置/不可用"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    rule_source = {"type": "string", "minLength": 1, "maxLength": 256, "description": "规范来源引用，无控制/格式/孤立代理字符；运营声明，真实性未自动核验"}
    applicability_rule = strict({"verdict": {"enum": ["APPLICABLE", "NOT_APPLICABLE"]}, "source_reference": rule_source})
    schemas["ProjectModelApplicability"] = strict({"project_id": identifier, "model_id": identifier,
        "version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
        "rule": {"anyOf": [applicability_rule, {"type": "null"}]}})
    schemas["ProjectModelApplicabilityInput"] = strict({"verdict": {"enum": ["APPLICABLE", "NOT_APPLICABLE", "WITHDRAW"]},
        "source_reference": {"anyOf": [rule_source, {"type": "null"}]}, "expected_version": {"type": "integer", "minimum": 0, "maximum": 9007199254740990}})
    schemas["ProjectModelApplicabilityInput"]["allOf"] = [{"if": {"properties": {"verdict": {"const": "WITHDRAW"}}}, "then": {"properties": {"source_reference": {"type": "null"}}}, "else": {"properties": {"source_reference": rule_source}}}]
    schemas["VehiclePlanEvidenceInput"] = strict({"vehicle_id": identifier,
        "project_ids": {"type": "array", "maxItems": 8, "uniqueItems": True, "items": identifier}})
    schemas["VehiclePlanEvidence"] = strict({
        "vehicle": strict({"vehicle_id": identifier, "model_id": {"anyOf": [identifier, {"type": "null"}]},
            "model_name": {"type": ["string", "null"]}, "model_source": {"const": "OWNER_REGISTERED"}, "power_type": {"type": ["string", "null"]}}),
        "observations": strict({"vehicle_id": identifier, "today": {"type": "string", "format": "date"},
            "list": {"type": "array", "minItems": 6, "maxItems": 6, "items": strict({
                "metric": {"enum": ["OIL", "BRAKE_PAD", "TIRE", "TRACTION_BATTERY", "COOLANT", "AUX_BATTERY"]},
                "revision": {"type": "integer", "minimum": 0}, "archive_id": {"anyOf": [identifier, {"type": "null"}]},
                "recorded_date": {"type": ["string", "null"], "format": "date"}, "measurement": {"type": "object", "description": "复用HEALTH_OBSERVATIONS协议；未知/来源失效为空对象"},
                "status": {"enum": ["NOT_SET", "RECORDED_UNVERIFIED", "SOURCE_UNAVAILABLE"]}})}}),
        "experiences": strict({"items": {"type": "array", "maxItems": 20, "items": {"$ref": "#/components/schemas/PublicExperience"}}, "next_cursor": {"anyOf": [identifier, {"type": "null"}]}}),
        "applicability": {"type": "array", "maxItems": 8, "items": strict({"project_id": identifier,
            "verdict": {"enum": ["APPLICABLE", "NOT_APPLICABLE", "UNKNOWN"]}, "rule_version": {"type": "integer", "minimum": 0, "maximum": 9007199254740991},
            "source_reference": {"anyOf": [rule_source, {"type": "null"}]}})}})
    for method, path, summary, body, result, roles in (
        ("post", "/api/ai/service-plan/vehicle-evidence", "主动读取本人车辆本地方案证据", "VehiclePlanEvidenceInput", "VehiclePlanEvidence", "OWNER"),
        ("get", "/api/admin/project-applicability/{project}/{model}", "目录运营读取精确车型适用性规则", None, "ProjectModelApplicability", "OPERATOR(can_catalog)"),
        ("put", "/api/admin/project-applicability/{project}/{model}", "目录运营发布或撤销精确车型适用性规则", "ProjectModelApplicabilityInput", "ProjectModelApplicability", "OPERATOR(can_catalog)")):
        item = operation(method, path, summary, "F05,F06"); item["x-roles"] = roles; item["x-implementation-status"] = "vehicle-plan-evidence-implemented"
        item["description"] = "no-store、无query；V036无规则回填。仅当前有效精确车型/项目；撤销保留版本，未配置显示UNKNOWN。不按动力类型推断，不证明技术必做。车辆/读数/获批同款摘要仅本地，不发第三方；写入复核权限/目录、版本与审计原子，历史成功后客户端GET，详见VEHICLE_PLAN_EVIDENCE.md。"
        if method == "post": item["parameters"] = [p for p in item["parameters"] if p["in"] != "header"]; item["x-read-only"] = True
        if method == "put": item["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "format": "uuid"}})
        if body: item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {"$ref": f"#/components/schemas/{body}"}}}}
        item["responses"].update({"404": {"description": "本人车辆或有效目录不可用"}, "409": {"description": "目录或规则版本已变化"}, "503": {"description": "数据库/写入暂不可用"}})
        item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": f"#/components/schemas/{result}"}}}]}
        document["paths"].setdefault(path, {})[method] = item

    schemas["PlanBookingContextInput"] = strict({"merchant_project_id": identifier, "standard_project_id": identifier, "vehicle_id": identifier,
        "date": {"type": "string", "format": "date", "description": "北京时间当天到30天内"}, "page": {"type": "integer", "minimum": 1, "maximum": 1000000},
        "selected_slot_id": {"anyOf": [identifier, {"type": "null"}]}})
    schemas["PlanBookingContext"] = strict({"quote": {"$ref": "#/components/schemas/ReservationQuote"}, "evidence": {"$ref": "#/components/schemas/VehiclePlanEvidence"},
        "slots": strict({"items": {"type": "array", "maxItems": 20, "items": {"$ref": "#/components/schemas/ReservationSlot"}}, "total": {"type": "integer", "minimum": 0},
            "page": {"type": "integer", "minimum": 1, "maximum": 1000000}, "page_size": {"const": 20}}),
        "selected_slot": {"anyOf": [{"$ref": "#/components/schemas/ReservationSlot"}, {"type": "null"}]}})
    path = "/api/order/plan-booking-context"
    item = operation("post", path, "本人方案当前报价/车辆证据/时段同快照复核", "F05,F06")
    item["parameters"] = []; item["x-roles"] = "OWNER"; item["x-read-only"] = True; item["x-implementation-status"] = "plan-booking-context-implemented"
    item["description"] = "no-store，无query/写幂等键/审计或第三方调用。严格六字段；当前报价必须匹配标准项目；本人有效车辆，日期当天至30天，20条可用时段分页。selected_slot跨页独立复核商家/项目/日期/未来/容量，无效null。规则为来源提示；原创建接口继续原子复核最新报价/时段容量。未知创建结果仅原body/key重试。无迁移，77表，详见PLAN_BOOKING.md。"
    item["requestBody"] = {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/PlanBookingContextInput"}}}}
    item["responses"].update({"404": {"description": "报价/项目映射/本人车辆或有效目录不可用"}, "503": {"description": "数据库未配置/不可用"}})
    item["responses"]["200"]["content"]["application/json"]["schema"] = {"allOf": [{"$ref": "#/components/schemas/ApiResponse"}, {"type": "object", "required": ["data"], "properties": {"data": {"$ref": "#/components/schemas/PlanBookingContext"}}}]}
    document["paths"][path] = {"post": item}

    add_r4_completion(document, operation)
    add_r5a(document, operation)
    add_r5b(document, operation)
    add_r6a(document, operation)
    add_r6b1(document, operation)
    add_r6b2(document, operation)
    add_r6b3(document, operation)
    add_r7a(document, operation)
    add_r7b(document, operation)
    add_r7c1(document, operation)
    add_r7c2(document, operation)
    add_r7c3(document, operation)
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    count = sum(len(item) for item in document["paths"].values())
    print(f"Wrote {count} operations to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
