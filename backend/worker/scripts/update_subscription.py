#!/usr/bin/env python3
"""Update one Firefly subscription source through the protected admin API."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import sys
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit, urlunsplit
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "https://ly.202132.xyz"
MAX_MANAGED_BYTES = 2 * 1024 * 1024
MAX_ADMIN_REQUEST_BYTES = MAX_MANAGED_BYTES + 16 * 1024
SOURCE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


class ScriptError(RuntimeError):
    """An expected validation, network, or API error."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "修改一个订阅源的外源链接和/或可选托管正文。"
            "未指定 --yes 时，写入前会要求人工确认。"
        ),
    )
    parser.add_argument(
        "subscription_id",
        help="订阅源 ID，例如 origin、node2",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("FIREFLY_BASE_URL", DEFAULT_BASE_URL),
        help=f"后端地址（默认：FIREFLY_BASE_URL 或 {DEFAULT_BASE_URL}）",
    )
    parser.add_argument(
        "--admin-route",
        default=os.getenv("FIREFLY_ADMIN_ROUTE"),
        help="后台访问路径（也可使用 FIREFLY_ADMIN_ROUTE）",
    )
    parser.add_argument(
        "--admin-token",
        default=os.getenv("FIREFLY_ADMIN_TOKEN"),
        help="Admin Token（建议使用 FIREFLY_ADMIN_TOKEN 或安全提示输入，避免命令历史泄露）",
    )
    managed_mode = parser.add_mutually_exclusive_group()
    managed_mode.add_argument(
        "--managed-file",
        metavar="PATH",
        help="从 UTF-8 文件更新可选托管正文；PATH 为 - 时从 stdin 读取",
    )
    managed_mode.add_argument(
        "--clear-managed",
        action="store_true",
        help="清空托管正文，只保留外源内容",
    )
    source_mode = parser.add_mutually_exclusive_group()
    source_mode.add_argument(
        "--external-url",
        metavar="HTTPS_URL",
        help="切换为外源 + 托管并设置必填的 HTTPS 外源链接",
    )
    source_mode.add_argument(
        "--managed-only",
        action="store_true",
        help="切换为纯托管模式；必须已有或同时提供托管正文",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="单个 API 请求超时秒数（默认：30）",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="跳过写入前确认",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="登录并检查目标，但不提交修改",
    )
    return parser.parse_args()


def normalize_base_url(value: str) -> str:
    parsed = urlsplit(value.strip())
    hostname = (parsed.hostname or "").lower()
    local = hostname in {"localhost", "127.0.0.1", "::1"}
    if not parsed.netloc or (parsed.scheme != "https" and not (local and parsed.scheme == "http")):
        raise ScriptError("--base-url 必须是 HTTPS 地址（仅 localhost 可使用 HTTP）")
    if parsed.query or parsed.fragment:
        raise ScriptError("--base-url 不能包含查询参数或片段")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def admin_api_url(base_url: str, admin_route: str, path: str) -> str:
    route = quote(admin_route.strip().strip("/"), safe="")
    return f"{base_url}/{route}/api/{path.lstrip('/')}"


def decode_api_error(status: int, raw: bytes) -> str:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return f"HTTP {status}"
    if not isinstance(payload, dict):
        return f"HTTP {status}"
    code = payload.get("error")
    request_id = payload.get("requestId")
    detail = str(code) if code else f"HTTP {status}"
    return f"{detail}（requestId: {request_id}）" if request_id else detail


def request_json(
    url: str,
    *,
    method: str = "GET",
    bearer: str | None = None,
    payload: dict[str, Any] | None = None,
    timeout: float,
) -> Any:
    body = None
    headers = {
        "Accept": "application/json",
        "User-Agent": "Firefly-Subscription-Updater/1.0",
    }
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(body) > MAX_ADMIN_REQUEST_BYTES:
            raise ScriptError(
                f"JSON 请求体为 {len(body)} 字节，超过服务端上限 {MAX_ADMIN_REQUEST_BYTES} 字节",
            )
        headers["Content-Type"] = "application/json; charset=utf-8"

    request = Request(url, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except HTTPError as error:
        raise ScriptError(
            f"API 请求失败：{decode_api_error(error.code, error.read())}",
        ) from error
    except URLError as error:
        raise ScriptError(f"无法连接后端：{error.reason}") from error
    except TimeoutError as error:
        raise ScriptError(f"请求超过 {timeout:g} 秒未完成") from error

    try:
        envelope = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScriptError("后端返回了无效的 JSON") from error
    if not isinstance(envelope, dict) or envelope.get("ok") is not True:
        raise ScriptError("后端返回了未知响应")
    return envelope.get("data")


def read_managed_content(path_value: str) -> str:
    if path_value == "-":
        content = sys.stdin.read()
    else:
        path = Path(path_value).expanduser()
        try:
            content = path.read_text(encoding="utf-8-sig")
        except OSError as error:
            raise ScriptError(f"无法读取托管正文文件 {path}: {error}") from error
        except UnicodeDecodeError as error:
            raise ScriptError(f"托管正文文件不是有效 UTF-8：{path}") from error

    encoded_size = len(content.encode("utf-8"))
    if not content.strip():
        raise ScriptError("托管正文不能为空")
    if encoded_size > MAX_MANAGED_BYTES:
        raise ScriptError(f"托管正文为 {encoded_size} 字节，超过 2 MiB 上限")
    return content


def validate_external_url(value: str) -> str:
    url = value.strip()
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ScriptError("外源链接必须是有效的 HTTPS URL")
    if parsed.username is not None or parsed.password is not None:
        raise ScriptError("外源链接不能包含 URL 用户名或密码")
    if len(url) > 2048:
        raise ScriptError("外源链接不能超过 2048 个字符")
    return url


def confirmation_text(current: dict[str, Any], payload: dict[str, Any]) -> str:
    source_id = current.get("id", "?")
    details = []
    if "sourceType" in payload:
        details.append("模式为外源 + 托管" if payload["sourceType"] == "external" else "模式为托管")
    if "sourceUrl" in payload:
        hostname = urlsplit(str(payload["sourceUrl"])).hostname or "?"
        details.append(f"新外源主机 {hostname}")
    if "managedContent" in payload:
        content = str(payload["managedContent"])
        details.append(
            f"新托管正文 {len(content.encode('utf-8'))} 字节" if content else "清空托管正文"
        )
    return f"将修改订阅源 {source_id}：{'，'.join(details)}"


def main() -> int:
    args = parse_args()
    if args.managed_file is None and not args.clear_managed and args.external_url is None and not args.managed_only:
        raise ScriptError("请至少指定 --external-url、--managed-only、--managed-file 或 --clear-managed")
    if not SOURCE_ID_PATTERN.fullmatch(args.subscription_id):
        raise ScriptError("订阅源 ID 格式无效")
    if not args.admin_route or not args.admin_route.strip().strip("/"):
        raise ScriptError("请传入 --admin-route 或设置 FIREFLY_ADMIN_ROUTE")
    if args.timeout <= 0:
        raise ScriptError("--timeout 必须大于 0")
    if args.managed_file == "-" and not args.yes and not args.dry_run:
        raise ScriptError("从 stdin 读取正文时无法交互确认，请同时传入 --yes")

    base_url = normalize_base_url(args.base_url)
    admin_token = args.admin_token or getpass.getpass("Admin Token: ")
    if not admin_token:
        raise ScriptError("Admin Token 不能为空")

    login = request_json(
        admin_api_url(base_url, args.admin_route, "session/login"),
        method="POST",
        bearer=admin_token,
        timeout=args.timeout,
    )
    if not isinstance(login, dict) or not isinstance(login.get("token"), str):
        raise ScriptError("登录成功，但响应中没有管理员 JWT")
    jwt = login["token"]

    subscription_path = f"subscriptions/{quote(args.subscription_id, safe='')}"
    current = request_json(
        admin_api_url(base_url, args.admin_route, subscription_path),
        bearer=jwt,
        timeout=args.timeout,
    )
    if not isinstance(current, dict):
        raise ScriptError("订阅源详情响应格式无效")

    payload: dict[str, Any] = {}
    if args.external_url is not None:
        payload["sourceType"] = "external"
        payload["sourceUrl"] = validate_external_url(args.external_url)
    elif args.managed_only:
        payload["sourceType"] = "managed"
    if args.managed_file is not None:
        payload["managedContent"] = read_managed_content(args.managed_file)
    elif args.clear_managed:
        payload["managedContent"] = ""
    target_mode = payload.get("sourceType", current.get("sourceType"))
    if target_mode == "managed" and not (payload.get("managedContent", current.get("managedContent")) or "").strip():
        raise ScriptError("托管模式必须提供托管正文，不能清空")

    summary = confirmation_text(current, payload)
    print(summary)
    if args.dry_run:
        print("dry-run：未提交修改")
        return 0
    if not args.yes and input("确认写入？输入 yes 继续：").strip().lower() != "yes":
        print("已取消，未修改任何内容")
        return 0

    updated = request_json(
        admin_api_url(base_url, args.admin_route, subscription_path),
        method="PATCH",
        bearer=jwt,
        payload=payload,
        timeout=args.timeout,
    )
    if not isinstance(updated, dict):
        raise ScriptError("修改成功，但订阅源响应格式无效")
    print(
        "修改成功："
        f"id={updated.get('id', args.subscription_id)}，"
        f"sourceType={updated.get('sourceType', '?')}，"
        f"sourceUrl={'已配置' if updated.get('sourceUrl') else '未配置'}，"
        f"updatedAt={updated.get('updatedAt', '?')}",
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ScriptError as error:
        print(f"错误：{error}", file=sys.stderr)
        raise SystemExit(1) from None
