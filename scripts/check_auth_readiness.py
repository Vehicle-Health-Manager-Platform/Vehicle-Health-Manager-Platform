"""Read-only preflight for a deployed auth API; never sends credentials."""

import argparse
import json
import ssl
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, Request, build_opener


TIMEOUT_SECONDS = 8
MAX_RESPONSE_BYTES = 4096


def validate_base_url(value: str) -> str:
    """Return a normalized HTTPS origin, or reject non-origin and unsafe URLs."""
    if not value or value != value.strip() or any(ord(char) < 33 for char in value):
        raise ValueError("地址格式无效")
    try:
        parts = urlsplit(value)
        port = parts.port
    except ValueError as exc:
        raise ValueError("地址格式无效") from exc
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.path not in ("", "/")
            or parts.query or parts.fragment or value.endswith(("?", "#"))):
        raise ValueError("仅接受 HTTPS 原点地址")
    if port is not None and port == 0:
        raise ValueError("端口无效")
    return f"https://{parts.netloc}"


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def fetch_url(url: str) -> tuple[int, bytes]:
    """GET with certificate validation, finite timeout and no redirects."""
    opener = build_opener(_NoRedirect, HTTPSHandler(context=ssl.create_default_context()))
    request = Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as response:
        with response:
            return response.code, response.read(MAX_RESPONSE_BYTES + 1)


def _check_response(status: int, body: bytes, expected_status: int,
                    field: str, expected_value: object) -> tuple[bool, str]:
    if 300 <= status < 400:
        return False, "发生重定向"
    if status != expected_status:
        return False, f"HTTP 状态不符：期望 {expected_status}，实际 {status}"
    if len(body) > MAX_RESPONSE_BYTES:
        return False, "响应超过预检大小限制"
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, ValueError):
        return False, "响应不是有效 JSON"
    if not isinstance(payload, dict) or payload.get(field) != expected_value:
        return False, f"JSON 字段 {field} 不符合预期"
    return True, "符合预期"


def run_checks(base_url: str, fetch=fetch_url) -> list[tuple[str, bool, str]]:
    """Check public health and anonymous access without credentials."""
    checks = (
        ("健康检查", "/actuator/health", 200, "status", "UP"),
        ("匿名访问保护", "/api/vehicle/list", 401, "code", 40100),
    )
    results = []
    for name, path, expected_status, field, expected_value in checks:
        try:
            status, body = fetch(base_url + path)
            passed, reason = _check_response(status, body, expected_status, field, expected_value)
        except (OSError, URLError, TimeoutError):
            passed, reason = False, "网络、TLS 或超时错误"
        results.append((name, passed, reason))
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="只读检查测试后端登录联调入口")
    parser.add_argument("--base-url", required=True, help="测试后端 HTTPS 原点地址")
    args = parser.parse_args(argv)
    try:
        base_url = validate_base_url(args.base_url)
    except ValueError as exc:
        print(f"地址无效：{exc}", file=sys.stderr)
        return 2
    results = run_checks(base_url)
    for name, passed, reason in results:
        print(f"{'通过' if passed else '失败'}  {name}：{reason}")
    return 0 if all(passed for _, passed, _ in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
