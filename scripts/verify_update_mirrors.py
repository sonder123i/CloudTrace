#!/usr/bin/env python3
"""发版后校验：确认每个镜像上实际可下载的文件与签名清单完全一致。

背景：1.14.4 发版时上传到 GitHub / Gitee 的 APK 与清单记录的
size / sha256 不一致（差 12 字节），客户端在下载前就正确拦截了
「下载文件长度与清单不一致」，导致用户无法更新。上传完必须回读校验。

用法：
    python -I scripts/verify_update_mirrors.py [--manifest docs/update/stable.json]
                                              [--expect-size 15622401]

只允许 https，且请求前校验 host：拒绝 localhost、环回、私有与保留地址。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import json
import socket
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT = 300
BLOCKED_SUFFIXES = (".localhost", ".local", ".internal", ".lan", ".home", ".home.arpa")


def _resolve_ips(host: str) -> list[str]:
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
    except OSError as exc:
        raise SystemExit(f"无法解析镜像域名 {host}: {exc}")
    return sorted({info[4][0] for info in infos})


def assert_url_allowed(raw: str) -> str:
    """仅 https、无凭据/片段、443 端口、host 不得指向内网或保留地址。"""
    uri = urllib.parse.urlsplit(raw)
    if uri.scheme.lower() != "https":
        raise SystemExit(f"镜像地址必须是 https: {raw}")
    if uri.username or uri.password:
        raise SystemExit(f"镜像地址不得包含账号密码: {raw}")
    if uri.fragment:
        raise SystemExit(f"镜像地址不得包含 fragment: {raw}")
    if uri.port not in (None, 443):
        raise SystemExit(f"镜像地址只允许 443 端口: {raw}")
    host = (uri.hostname or "").strip().lower()
    if not host:
        raise SystemExit(f"镜像地址缺少 host: {raw}")
    if host == "localhost" or host.endswith(BLOCKED_SUFFIXES):
        raise SystemExit(f"镜像地址指向本机/内网域名: {host}")
    for ip_text in _resolve_ips(host):
        ip = ipaddress.ip_address(ip_text)
        if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_reserved or ip.is_multicast:
            raise SystemExit(f"镜像域名 {host} 解析到受限地址 {ip_text}")
    return raw


def download(url: str) -> tuple[int, str]:
    """返回 (字节数, sha256)。跟随跳转时逐跳重新校验 host。"""
    request = urllib.request.Request(url, headers={"Accept-Encoding": "identity"})
    context = ssl.create_default_context()
    for _ in range(6):
        assert_url_allowed(request.full_url)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT, context=context) as response:
                declared = response.headers.get("Content-Length")
                digest = hashlib.sha256()
                size = 0
                while True:
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                    size += len(chunk)
                if declared is not None and int(declared) != size:
                    print(f"    ! Content-Length={declared} 与实际 {size} 不符")
                return size, digest.hexdigest()
        except urllib.error.HTTPError as exc:
            raise SystemExit(f"    HTTP {exc.code} {exc.reason}")
    raise SystemExit("    跳转次数过多")


def main() -> int:
    parser = argparse.ArgumentParser(description="校验更新镜像与签名清单是否一致")
    parser.add_argument("--manifest", default="docs/update/stable.json")
    parser.add_argument("--expect-size", type=int, default=None,
                        help="额外断言版本包大小（防止清单本身写错）")
    args = parser.parse_args()

    with open(args.manifest, encoding="utf-8") as handle:
        envelope = json.load(handle)
    payload = json.loads(base64.b64decode(envelope["payload"]))

    expected_size = payload["size"]
    expected_sha = payload["sha256"]
    if args.expect_size is not None and expected_size != args.expect_size:
        raise SystemExit(f"清单 size={expected_size} 与期望 {args.expect_size} 不符")

    print(f"清单: v{payload['versionName']} ({payload['versionCode']}) "
          f"size={expected_size} sha256={expected_sha[:16]}…")
    print(f"forceUpdate={payload['forceUpdate']} 镜像数={len(payload['mirrors'])}")

    failures = 0
    for mirror in payload["mirrors"]:
        print(f"\n[{mirror['id']}] {mirror['name']}")
        print(f"    {mirror['url']}")
        try:
            size, sha = download(mirror["url"])
        except SystemExit as exc:
            print(f"  [FAIL] {exc}")
            failures += 1
            continue
        ok = size == expected_size and sha == expected_sha
        print(f"    size={size} sha256={sha[:16]}…")
        if ok:
            print("  [OK] 与清单一致")
        else:
            failures += 1
            print(f"  [FAIL] 与清单不一致（期望 size={expected_size} sha256={expected_sha}）")

    if failures:
        print(f"\n{failures} 个镜像未通过校验：请重新上传正确 APK 后再发版。")
        return 1
    print(f"\n全部 {len(payload['mirrors'])} 个镜像校验通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())