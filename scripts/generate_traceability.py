"""Generate the S0 traceability baseline from the versioned delivery document.

Run: python scripts/generate_traceability.py
The file is a planning baseline: planned tests are not reported as passing.
"""

import csv
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "reference" / "DELIVERY_V1.md"
OUTPUT = ROOT / "docs" / "progress" / "S0_TRACEABILITY.csv"

# Each feature maps to its planned stage, UI, API, and primary data entities.
# The mapping is intentionally explicit so reviewers can correct a contract
# without silently changing it when the source document is reformatted.
FEATURES = {
    "F01": ("S1", "C01", "/api/auth/wx-login,/api/auth/refresh", "user"),
    "F02": ("S1", "C02-C06", "/api/vehicle/list,/api/vehicle/add,/api/vehicle/vin-decode,/api/vehicle/ocr-license,/api/brand/list,/api/series/list,/api/model/list", "vehicle,brand,series,model"),
    "F03": ("S1", "C13-C14", "/api/archive/add,/api/archive/list", "vehicle_archive,vehicle"),
    "F04": ("S1", "C07,C15", "/api/home/dashboard,/api/home/reminders", "vehicle,vehicle_archive,maintenance_rule"),
    "F05": ("S3", "C11-C12", "/api/ai/chat,/api/ai/plan", "vehicle_archive,ai_plan_rule"),
    "F06": ("S2", "C08-C10", "/api/service/projects,/api/service/project/:id/merchants,/api/merchant/:id,/api/merchant/:id/reviews/summary", "standard_project,merchant_project,merchant"),
    "F07": ("S2+S3券", "C18", "/api/order/create", "order,user_coupon"),
    "F08": ("S2+S4核销", "C16-C17,C24-C25", "/api/order/list,/api/order/:id,/api/order/cancel,/api/order/review", "order"),
    "F09": ("S3", "C19", "/api/coupon/receive,/api/coupon/mine", "coupon,user_coupon"),
    "F10": ("S3", "C20", "/api/invite/qrcode,/api/invite/records,/api/invite/progress", "invite_record"),
    "F11": ("S3", "C21", "/api/point/exchange,/api/point/flow", "point_flow,point_exchange"),
    "F12": ("S3读取+S4卡片", "C22", "/api/community/hot", "community_content,community_interaction,circle_follow"),
    "F13": ("S4+S5审核", "M30,O62", "/api/admin/merchant/audit", "merchant"),
    "F14": ("S4", "M34,M35,C23", "/api/check/pickup/submit,/api/check/pickup/confirm,/api/check/delivery/compare", "pickup_check,delivery_compare"),
    "F15": ("S4", "M36,C24", "/api/check/protection/upload", "repair_protection"),
    "F16": ("S4", "M32-M33,M37-M39", "/api/order/:id,/api/order/cancel", "order"),
    "F17": ("S4", "M40", "/api/service/projects", "standard_project,merchant_project"),
    "F18": ("S4", "T50-T59", "/api/tech/orders,/api/tech/report/submit,/api/tech/sign", "technician_report"),
    "F19": ("S5", "M42,O66", "/api/admin/assessment", "assessment"),
    "F20": ("S5", "O60-O72", "/api/admin/standard-project,/api/admin/dashboard", "merchant,standard_project,model,coupon"),
}

# The delivery document gives ranges for the three staff-side page groups,
# but the ranges disagree with the number of named pages. These are stable
# tracking IDs for the *named* pages only, not claimed source identifiers.
STAFF_PAGES = {
    "商家": ("M", 30, "F13,F16,F17,F09,F19", "S4"),
    "技师": ("T", 50, "F18", "S4"),
    "运营": ("O", 60, "F20,F13,F19", "S5"),
}

ACCEPTANCE_FEATURES = (
    "F02", "F04", "F05,F12", "F06", "F07,F08,F09",
    "F14", "F15,F18", "F18", "F09,F10,F11", "F19",
    "F01,F02,F03,F14,F16,F18,F20", "F03,F12,F18",
    "F04,F06,F07", "F01,F02,F14,F18,F20", "F01,F02,F03,F07,F14,F16,F18,F20",
)


def section(source: str, start: str, end: str) -> str:
    return source.split(start, 1)[1].split(end, 1)[0]


def table_rows(body: str, pattern: str):
    for line in body.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if cells and re.fullmatch(pattern, cells[0]):
            yield cells


def staff_names(body: str, label: str):
    line = next(line for line in body.splitlines() if line.startswith(f"**{label}"))
    names = line.split("：**", 1)[1].rstrip("。")
    return [name.strip() for name in names.split("、")]


def main():
    source = SOURCE.read_text(encoding="utf-8")
    rows = []

    def add(kind, item_id, name, stage, priority, feature, page, api, data, test, source_ref):
        rows.append((kind, item_id, name, stage, priority, feature, page, api, data,
                     test, "待编写", "未执行", "待分配", source_ref))

    feature_section = section(source, "### 2.1 功能清单与优先级", "### 2.2")
    for cells in table_rows(feature_section, r"F\d{2}"):
        item_id, name, _, priority = cells[:4]
        stage, page, api, data = FEATURES[item_id]
        add("功能", item_id, name, stage, priority, item_id, page, api, data,
            f"TC-{item_id}", "交付文档 §2.1")

    page_section = section(source, "### 3.1 页面清单", "### 3.2")
    for cells in table_rows(page_section, r"C\d{2}"):
        item_id, name, route = cells[:3]
        related = [f for f, (_, pages, _, _) in FEATURES.items()
                   if item_id in expand_ids(pages)]
        stage = "+".join(dict.fromkeys(FEATURES[f][0] for f in related)) or "待核定"
        add("页面", item_id, name, stage, "见关联功能", ",".join(related),
            route, related_apis(related), related_data(related), f"TC-{item_id}", "交付文档 §3.1")

    for label, (prefix, first, related, stage) in STAFF_PAGES.items():
        for offset, name in enumerate(staff_names(page_section, label)):
            item_id = f"{prefix}{first + offset}"
            add("页面", item_id, name, stage, "见关联功能", related, item_id,
                related_apis(related.split(",")), related_data(related.split(",")),
                f"TC-{item_id}", "交付文档 §3.1；ID 为仓库追踪编号")

    api_section = section(source, "### 8.3 接口列表（核心）", "### 8.4")
    for index, cells in enumerate(table_rows(api_section, r"GET|POST|PUT|DELETE|PATCH"), 1):
        method, path, name = cells[:3]
        related = [f for f, (_, _, apis, _) in FEATURES.items() if path in apis.split(",")]
        stage = "+".join(dict.fromkeys(FEATURES[f][0] for f in related)) or "待核定"
        add("核心API", f"API{index:02d}", name, stage, "见关联功能",
            ",".join(related), "见关联功能", f"{method} {path}",
            "见关联功能", f"TC-API{index:02d}", "交付文档 §8.3")

    acceptance_section = section(source, "### 12.1 验收项与标准", "### 12.2")
    for index, cells in enumerate(table_rows(acceptance_section, r"功能|权限|数据|性能|兼容|安全"), 1):
        category, name, criterion = cells[:3]
        related = ACCEPTANCE_FEATURES[index - 1].split(",")
        add("验收", f"AC{index:02d}", f"{category}：{name} — {criterion}",
            "S6（首次验收见阶段计划）", "验收门禁", ",".join(related),
            ",".join(FEATURES[f][1] for f in related), related_apis(related),
            related_data(related), f"TC-AC{index:02d}", "交付文档 §12.1")

    expected = {"功能": 20, "页面": 63, "核心API": 43, "验收": 15}
    actual = {kind: sum(row[0] == kind for row in rows) for kind in expected}
    if actual != expected or any(not row[5] for row in rows):
        raise ValueError(f"Traceability drift: {actual}; expected {expected}")

    headers = ("类型", "编号", "名称", "目标阶段", "优先级", "关联功能", "页面或路由",
               "接口", "数据实体", "计划用例", "用例状态", "验收结果", "负责人", "来源")
    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(headers)
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {OUTPUT.relative_to(ROOT)}")


def expand_ids(value: str):
    result = set()
    for part in value.split(","):
        match = re.fullmatch(r"([A-Z])(\d+)-([A-Z])?(\d+)", part)
        if match:
            prefix, start, _, end = match.groups()
            result.update(f"{prefix}{n:02d}" for n in range(int(start), int(end) + 1))
        else:
            result.add(part)
    return result


def related_apis(features):
    return ",".join(dict.fromkeys(
        api for feature in features for api in FEATURES[feature][2].split(",")
    ))


def related_data(features):
    return ",".join(dict.fromkeys(
        entity for feature in features for entity in FEATURES[feature][3].split(",")
    ))


if __name__ == "__main__":
    main()
