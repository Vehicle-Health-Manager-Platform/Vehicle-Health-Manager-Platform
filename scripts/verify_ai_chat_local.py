#!/usr/bin/env python
"""本机 /api/ai/chat 端到端验证。

用途：在本机 Docker 后端已启动、且 .env.auth-backend.local 已配置真实
DEEPSEEK_API_KEY 的前提下，验证车主端 AI 管家的真实链路（会真实调用上游模型）。

⚠️ 本脚本只用在本机验证。它用 JWT_SECRET 直接自签令牌来模拟会话，绕过真实微信登录，
   **不是**任何可用的鉴权途径，也不能用于测试或生产环境。
   它只读取本地已被 .gitignore 忽略的 .env.auth-backend.local，不自带任何密钥。

为什么要传 --jti：JwtDecoder 除了验签，还要求令牌的 jti 在 auth_session 表中处于
未撤销、未过期状态（见 SecurityConfig#jwtDecoder）。所以自签令牌必须复用一条真实存在的
活跃会话。查一条可用会话：

    docker exec -i vehicle-auth-local-mysql-1 mysql -uroot -p<ROOT_PW> -e \
      "SELECT id FROM vehicle_health.auth_session WHERE revoked_at IS NULL AND expires_at > NOW() AND subject_type='user' AND role='OWNER' LIMIT 1;"

用法：
    python scripts/verify_ai_chat_local.py --jti <活跃车主会话ID> \
        --owner-vehicle-id 9100699 --foreign-vehicle-id 9100690 --plate 京B90366
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid

ISSUER = "vehicle-health-manager"
REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / ".env.auth-backend.local"
PLACEHOLDERS = {"", "unconfigured", "your-deepseek-api-key"}


def load_env(path: pathlib.Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def mint(secret: str, subject: str, role: str, subject_type: str, app_id: str,
         jti: str | None = None, ttl: int = 900) -> str:
    now = int(time.time())
    header = {"alg": "HS256"}
    payload = {
        "iss": ISSUER,
        "sub": subject,
        "iat": now,
        "exp": now + ttl,
        "role": role,
        "subject_type": subject_type,
        "app_id": app_id,
    }
    if jti:
        payload["jti"] = jti
    signing_input = (f"{b64url(json.dumps(header, separators=(',', ':')).encode())}."
                     f"{b64url(json.dumps(payload, separators=(',', ':')).encode())}")
    signature = hmac.new(secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def post(base_url: str, token: str | None, body: object, timeout: int = 90):
    """返回 (http_status, 响应头, 解析后的 JSON 或原始文本)。"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{base_url}/api/ai/chat", data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw, status, head = response.read().decode("utf-8", "replace"), response.status, response.headers
    except urllib.error.HTTPError as error:
        raw, status, head = error.read().decode("utf-8", "replace"), error.code, error.headers
    try:
        return status, head, json.loads(raw)
    except json.JSONDecodeError:
        return status, head, raw


class Report:
    def __init__(self) -> None:
        self.passed = 0
        self.failed: list[str] = []

    def check(self, case: str, ok: bool, detail: str) -> None:
        if ok:
            self.passed += 1
            print(f"  [PASS] {case} — {detail}")
        else:
            self.failed.append(case)
            print(f"  [FAIL] {case} — {detail}")

    def info(self, case: str, detail: str) -> None:
        print(f"  [INFO] {case} — {detail}")

    def skip(self, case: str, why: str) -> None:
        print(f"  [SKIP] {case} — {why}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--jti", required=True, help="一条活跃的 OWNER 会话 ID（见文件头说明）")
    parser.add_argument("--owner-user-id", type=int, default=1)
    parser.add_argument("--owner-vehicle-id", type=int, default=None, help="属于该车主的车辆 ID")
    parser.add_argument("--foreign-vehicle-id", type=int, default=None, help="属于其他车主的车辆 ID")
    parser.add_argument("--plate", default=None, help="目标车辆车牌，用于隐私断言")
    parser.add_argument("--merchant-jti", default=None, help="活跃 MERCHANT 会话 ID（可选）")
    parser.add_argument("--technician-jti", default=None, help="活跃 TECHNICIAN 会话 ID（可选）")
    args = parser.parse_args()

    if not ENV_FILE.exists():
        print(f"缺少 {ENV_FILE}", file=sys.stderr)
        return 2
    env = load_env(ENV_FILE)
    secret, app_id = env.get("JWT_SECRET", ""), env.get("WECHAT_APP_ID", "")
    if len(secret) < 32 or not app_id:
        print("JWT_SECRET 或 WECHAT_APP_ID 不可用", file=sys.stderr)
        return 2
    if env.get("DEEPSEEK_API_KEY", "") in PLACEHOLDERS:
        print("DEEPSEEK_API_KEY 未配置，真实链路无法验证（此时期望 50301）", file=sys.stderr)
        return 2

    owner_token = mint(secret, str(args.owner_user_id), "OWNER", "user", app_id, args.jti)
    binding_token = mint(secret, "oTestOpenid", "BIND", "wechat_binding", app_id)
    model = env.get("DEEPSEEK_MODEL", "deepseek-flash") or "deepseek-flash"

    report = Report()
    print(f"目标：{args.base_url}/api/ai/chat\n")

    print("A1 会话与角色")
    status, _, body = post(args.base_url, None, {"message": "你好"})
    report.check("A1-匿名", status == 401 and body.get("code") == 40100, f"HTTP {status} code={body.get('code')}")
    status, _, body = post(args.base_url, binding_token, {"message": "你好"})
    report.check("A1-微信绑定态", status == 403 and body.get("code") == 40300, f"HTTP {status} code={body.get('code')}")
    for label, jti, role, subject_type, mer_id in (
        ("A1-商家", args.merchant_jti, "MERCHANT", "staff_account", True),
        ("A1-技师", args.technician_jti, "TECHNICIAN", "staff_account", False),
    ):
        if not jti:
            report.skip(label, "未提供对应活跃会话 ID，改由离线测试 AiChatHttpTest 覆盖")
            continue
        extra = {"merchant_id": 1}
        if not mer_id:
            extra["binding_id"] = 1
        token = mint(secret, "1", role, subject_type, "merchant-account" if mer_id else app_id, jti)
        status, _, body = post(args.base_url, token, {"message": "你好"})
        report.check(label, status == 403 and body.get("code") == 40300, f"HTTP {status} code={body.get('code')}")

    print("\nA5 参数校验（车主会话）")
    for label, payload in (
        ("A5-缺 message", {}),
        ("A5-空 message", {"message": "   "}),
        ("A5-超长", {"message": "x" * 2001}),
        ("A5-未知字段", {"message": "你好", "unknown_field": 1}),
        ("A5-历史超 8 轮", {"message": "你好", "history": [{"role": "user", "content": "x"}] * 9}),
        ("A5-vehicle_id 非法", {"message": "你好", "vehicle_id": -1}),
    ):
        status, _, body = post(args.base_url, owner_token, payload)
        report.check(label, status == 400 and body.get("code") == 40001, f"HTTP {status} code={body.get('code')}")

    print("\nA2 通用回答（不传 vehicle_id）")
    status, head, body = post(args.base_url, owner_token, {"message": "冬天开暖风油耗变高正常吗？请用一句话回答。"})
    data = body.get("data") or {}
    report.check("A2-状态", status == 200 and body.get("code") == 0, f"HTTP {status} code={body.get('code')}")
    report.check("A2-grounded", data.get("grounded") is False and data.get("vehicle_id") is None,
                 f"grounded={data.get('grounded')} vehicle_id={data.get('vehicle_id')}")
    report.check("A2-无缓存", (head.get("Cache-Control") or "") == "no-store", f"Cache-Control={head.get('Cache-Control')}")
    report.check("A2-模型", data.get("model") == model, f"model={data.get('model')}（期望 {model}）")
    if data.get("reply"):
        report.info("A2-回答", data["reply"][:70].replace("\n", " ") + "…")

    if args.foreign_vehicle_id:
        print("\nA4 越权车辆")
        status, _, body = post(args.base_url, owner_token, {"message": "你好", "vehicle_id": args.foreign_vehicle_id})
        report.check("A4-他人车辆", status == 404 and body.get("code") == 40400, f"HTTP {status} code={body.get('code')}")

    if args.owner_vehicle_id:
        print("\nA3 档案上下文注入（本人车辆）")
        status, head, body = post(args.base_url, owner_token, {
            "message": "请结合我的车辆档案，用两三句话说明这辆车目前的状况。",
            "vehicle_id": args.owner_vehicle_id,
        })
        data = body.get("data") or {}
        report.check("A3-状态", status == 200 and body.get("code") == 0, f"HTTP {status} code={body.get('code')}")
        report.check("A3-grounded", data.get("grounded") is True and data.get("vehicle_id") == args.owner_vehicle_id,
                     f"grounded={data.get('grounded')} vehicle_id={data.get('vehicle_id')}")
        report.check("A3-无缓存", (head.get("Cache-Control") or "") == "no-store", f"Cache-Control={head.get('Cache-Control')}")
        reply = data.get("reply") or ""
        report.check("A3-有回答", bool(reply.strip()), f"长度 {len(reply)}")
        if reply:
            report.info("A3-回答", reply[:100].replace("\n", " ") + "…")

        print("\n隐私：车牌不得进入上下文")
        status, _, body = post(args.base_url, owner_token, {
            "message": "我的车牌号是什么？如果你不知道就直接回答不知道。",
            "vehicle_id": args.owner_vehicle_id,
        })
        reply = ((body.get("data") or {}).get("reply") or "")
        report.check("隐私-状态", status == 200, f"HTTP {status}")
        if args.plate:
            report.check("隐私-车牌未泄露", args.plate not in reply,
                         f"回复中{'出现' if args.plate in reply else '未出现'}车牌 {args.plate}")
        else:
            report.skip("隐私-车牌", "未提供 --plate")
        report.info("隐私-回复", reply[:80].replace("\n", " ") + "…")

    print(f"\n通过 {report.passed} 项，失败 {len(report.failed)} 项")
    if report.failed:
        print("失败用例：" + "、".join(report.failed))
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
