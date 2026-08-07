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

# 签名指纹：这是固定发布密钥（T-006）唯一的意义所在。
# 在没配 keystore 的机器上构建会**静默退回 Android 默认 debug 密钥**——
# x86 检查过、哈希也对得上，一切看起来正常，直到中途要推新版时才发现
# 30 台手机装的是另一个签名的包，只能全部卸载重装、带走未上传的录音。
EXPECTED_SIGNER=e20f6e55554959176fb42d572b08c961e80c6ff692f6765ec77de4040c91dceb
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | tail -1)apksigner
GOT_SIGNER=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null |
  awk '/SHA-256 digest/{print $NF; exit}')
if [ "$GOT_SIGNER" != "$EXPECTED_SIGNER" ]; then
  echo "拒绝发布：签名指纹不符"
  echo "  期望 $EXPECTED_SIGNER"
  echo "  实际 ${GOT_SIGNER:-（读不到）}"
  echo "  多半是这台机器没配 EGOAUDIO_KEYSTORE_FILE，构建退回了默认 debug 密钥"
  exit 1
fi
echo "签名指纹已核对：${GOT_SIGNER:0:16}…"

# versionCode 必须严格大于线上：同号或降号 Android 不认为是升级，
# 表现为"参与者装不上新版"，又是一次静默失败。
AAPT=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | tail -1)aapt2
LOCAL_VC=$("$AAPT" dump badging "$APK" 2>/dev/null |
  sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
ONLINE_VC=$(curl -s --max-time 15 "$PUBLIC/apk/version" |
  sed -n 's/.*"versionCode":\([0-9]*\).*/\1/p')
if [ -n "$ONLINE_VC" ] && [ -n "$LOCAL_VC" ] && [ "$LOCAL_VC" -le "$ONLINE_VC" ]; then
  echo "拒绝发布：versionCode $LOCAL_VC 不大于线上的 $ONLINE_VC，装不上去"
  exit 1
fi
echo "versionCode $LOCAL_VC（线上 ${ONLINE_VC:-无}）"

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
