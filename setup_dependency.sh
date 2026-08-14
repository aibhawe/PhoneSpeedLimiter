#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/app/src/main/jni/hev-socks5-tunnel"
PIN="64cc609f945253b0e9ebc56317d544268f3c68c1"

if ! command -v git >/dev/null 2>&1; then
  echo "git غير مثبت" >&2
  exit 1
fi

if [ ! -d "$DEST/.git" ]; then
  rm -rf "$DEST"
  git clone --recursive https://github.com/heiher/hev-socks5-tunnel.git "$DEST"
fi

git -C "$DEST" fetch origin "$PIN" --depth 1
git -C "$DEST" checkout --detach "$PIN"
git -C "$DEST" submodule update --init --recursive

echo "تم تجهيز hev-socks5-tunnel عند $PIN"
