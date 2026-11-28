#!/usr/bin/env python3
"""生成 CloudTrace 应用更新清单（schemaVersion=2，ECDSA P-256 签名）。

用法：
  python scripts/make_update_manifest.py \
    --apk app/build/outputs/apk/release/CloudTrace_1.13.0_arm64-v8a_release.apk \
    --mirror gh="GitHub Release"="https://github.com/<owner>/CloudTrace/releases/download/v1.13.0/CloudTrace_1.13.0_arm64-v8a_release.apk" \
    --mirror gitee="Gitee 附件"="https://gitee.com/<owner>/cloud-traces/releases/download/v1.13.0/CloudTrace_1.13.0_arm64-v8a_release.apk" \
    --notes-file release-notes/v1.13.0.md \
    --key-file "$GOGOGO_UPDATE_SIGNING_KEY" \
    --out docs/update/stable.json

私钥只从 --key-file 或环境变量 GOGOGO_UPDATE_SIGNING_KEY 读取，
不写入仓库；公钥钉在 app/src/main/assets/app-update-keys.json。
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_NAME = "com.zcshou.gogogo"
MIN_SDK = 27
ABI = "arm64-v8a"
KEY_ID = "gogogo-update-2026-11"


def checked(result):
    if result.returncode != 0:
        raise SystemExit("命令失败\n" + result.stderr.decode(errors="replace"))
    return result.stdout


def read_version():
    text = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", text)
    name = re.search(r"versionName\s+'([^']+)'", text)
    if not code or not name:
        raise SystemExit("无法从 app/build.gradle 读取 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def source_sha():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
            shell=False,
        ).decode().strip()
    except Exception:
        return "0" * 40


def sign_payload(payload_bytes, key_file):
    return checked(subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_file],
        input=payload_bytes,
        capture_output=True,
        shell=False,
    ))


def export_public_key(key_file):
    return checked(subprocess.run(
        ["openssl", "ec", "-in", key_file, "-pubout"],
        capture_output=True,
        shell=False,
    ))


def verify_signature(pub_path, sig_path, payload_bytes):
    checked(subprocess.run(
        ["openssl", "dgst", "-sha256", "-verify", pub_path,
         "-signature", sig_path],
        input=payload_bytes,
        capture_output=True,
        shell=False,
    ))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--channel", default="stable", choices=["stable"])
    parser.add_argument(
        "--mirror", action="append", default=[],
        help='id="显示名"="下载 URL"，可重复；至少一个'
    )
    parser.add_argument("--notes", default="")
    parser.add_argument("--notes-file", default="")
    parser.add_argument(
        "--key-file", default=os.environ.get("GOGOGO_UPDATE_SIGNING_KEY", "")
    )
    parser.add_argument("--key-id", default=KEY_ID)
    parser.add_argument("--force-update", action="store_true")
    parser.add_argument("--source-sha", default="")
    parser.add_argument("--out", default="docs/update/stable.json")
    args = parser.parse_args()

    key_file = args.key_file
    if not key_file:
        raise SystemExit("缺少签名私钥：--key-file 或 GOGOGO_UPDATE_SIGNING_KEY")
    apk = Path(args.apk)
    if not apk.exists():
        raise SystemExit(f"APK 不存在: {apk}")

    notes = args.notes
    if not notes and args.notes_file:
        notes = Path(args.notes_file).read_text(encoding="utf-8")
    if not notes.strip():
        raise SystemExit(
            "缺少更新说明：请用 --notes-file release-notes/vX.Y.Z.md 写清本次改动，"
            "更新弹窗会直接把它展示给用户"
        )
    notes = notes[:20000]
    version_code, version_name = read_version()

    mirrors = []
    for spec in args.mirror:
        # 格式 id="显示名"="URL"；URL 两侧的引号是分隔符，不能带进字段值
        match = re.match(r'([a-z0-9-]{1,32})="?([^"]{1,40})"?="?([^"\s]+)"?\s*$', spec)
        if not match:
            raise SystemExit(f'--mirror 格式应为 id="名称"="URL"，收到: {spec}')
        mirrors.append({
            "id": match.group(1),
            "name": match.group(2),
            "url": match.group(3),
        })
        if '"' in mirrors[-1]["url"] or not mirrors[-1]["url"].startswith("https://"):
            raise SystemExit(f'--mirror 的 URL 必须是纯 https 地址，收到: {spec}')
    if not 1 <= len(mirrors) <= 8:
        raise SystemExit("镜像数量须在 1..8")

    blob = apk.read_bytes()
    payload = {
        "channel": args.channel,
        "revision": int(time.time()),
        "packageName": PACKAGE_NAME,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": MIN_SDK,
        "abi": ABI,
        "releaseNotes": notes,
        "forceUpdate": bool(args.force_update),
        "size": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "sourceSha": args.source_sha or source_sha(),
        "buildId": str(int(time.time() * 1000)),
        "publishedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "mirrors": mirrors,
    }
    payload_bytes = json.dumps(
        payload, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    signature = sign_payload(payload_bytes, key_file)
    envelope = {
        "schemaVersion": 2,
        "keyId": args.key_id,
        "payload": base64.b64encode(payload_bytes).decode(),
        "signature": base64.b64encode(signature).decode(),
    }

    # 自验：确保流水线不会发出坏清单；公钥临时导出，不写入仓库。
    with tempfile.NamedTemporaryFile(delete=False, suffix=".pem") as pub:
        pub.write(export_public_key(key_file))
        pub_path = pub.name
    with tempfile.NamedTemporaryFile(delete=False, suffix=".sig") as sig:
        sig.write(signature)
        sig_path = sig.name
    try:
        verify_signature(pub_path, sig_path, payload_bytes)
    finally:
        os.unlink(pub_path)
        os.unlink(sig_path)

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(envelope, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"清单已写入 {out}: v{version_name} ({version_code}), "
        f"{len(blob)} bytes, {len(mirrors)} mirrors, 签名自验通过"
    )


if __name__ == "__main__":
    main()
