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
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {count} operations to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
