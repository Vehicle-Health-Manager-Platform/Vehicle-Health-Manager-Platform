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
        data["parameters"].append({"name": "Idempotency-Key", "in": "header", "required": protected,
                                   "schema": {"type": "string", "format": "uuid"}})
        data["requestBody"] = {"required": True, "content": {"application/json": {
            "schema": {"type": "object", "additionalProperties": True}}}}
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

    for method, path, summary in (
        ("post", "/api/dev/token", "仅 local profile 可用的演示令牌"),
        ("get", "/api/demo/vehicles/{id}", "仅 local profile 可用的车辆归属校验样例"),
    ):
        document["paths"].setdefault(path, {})[method] = operation(
            method, path, summary, "F01,F02", path != "/api/dev/token")
    document["paths"]["/api/dev/token"]["post"]["x-local-only"] = True
    document["paths"]["/api/demo/vehicles/{id}"]["get"]["x-local-only"] = True

    count = sum(len(item) for item in document["paths"].values())
    if count < 55:
        raise ValueError(f"Expected at least 55 operations; got {count}")
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {count} operations to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
