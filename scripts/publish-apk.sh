#!/usr/bin/env bash
# 发布参与者包到上传站，并逐位核对公网下载的结果。
#
# `latest` 是数据库里的指针，不是"最新的那一行"——发现新包有问题时，
# 把指针切回旧 id 即可秒级回退，历史包都留着。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="$(cd "$ROOT/../EgoAudio-Data_Platform" && pwd)"
APK="$ROOT/app/build/outputs/apk/device/debug/app-device-debug.apk"
PUBLIC="${EGOAUDIO_UPLOAD_URL:-https://nuna-audio-lab-data.cuhkaiot.com}"

[ -f "$APK" ] || { echo "先构建参与者包：./gradlew assembleDeviceDebug"; exit 1; }

# 参与者包绝不能带 x86：那是模拟器用的，51 MB 白占参与者流量
if unzip -l "$APK" | grep -q "lib/x86"; then
  echo "拒绝发布：这个包含有 x86 库，说明构建的是模拟器变体"; exit 1
fi

LOCAL_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
echo "待发布 sha256=${LOCAL_SHA:0:16}…"

cp "$APK" "$PLATFORM/runtime/apk/_incoming.apk"
docker exec nuna_receiver python -c "
from backend.apk_release import publish
from backend.database import SessionLocal
db = SessionLocal()
try:
    rel, err = publish(db, '/runtime/apk/_incoming.apk', uploaded_by='mobile-agent',
                       notes='${1:-}')
    db.commit()
    print('已发布 id=%s versionCode=%s versionName=%s' % (rel.id, rel.version_code, rel.version_name))
    if err: print('解析警告:', err)
finally:
    db.close()
"

# 不信任"发布成功"这句话本身：真的下一遍，逐位比对
TMP=$(mktemp)
curl -sfL --max-time 300 -o "$TMP" "$PUBLIC/apk/latest"
REMOTE_SHA=$(sha256sum "$TMP" | cut -d' ' -f1)
rm -f "$TMP"
if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
  echo "❌ 公网下载与本地构建不一致：$REMOTE_SHA"; exit 1
fi
echo "✅ $PUBLIC/apk/latest 与本地构建逐位一致"
curl -s --max-time 15 "$PUBLIC/apk/version"; echo
