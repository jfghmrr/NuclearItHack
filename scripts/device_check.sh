#!/usr/bin/env bash
# Проверка чёрным ящиком: чистая установка, авиарежим, запуск через adb, контрактный файл.
# Запуск: scripts/device_check.sh <apk> <аудио> [serial]
# Итоговые файлы складываются в каталог STENOKOT_OUT, по умолчанию device_check.
set -euo pipefail

APK="$1"
AUDIO="$2"
SERIAL="${3:-emulator-5554}"
PKG=ru.stenokot.app
OUT="$(mkdir -p "${STENOKOT_OUT:-device_check}" && cd "${STENOKOT_OUT:-device_check}" && pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT2="$(command -v aapt2 || ls -d "$SDK"/build-tools/*/aapt2 | tail -1)"
ADB=("$(command -v adb || echo "$SDK/platform-tools/adb")" -s "$SERIAL")
NAME="$(basename "$AUDIO")"
STEM="${NAME%.*}"

case "$NAME" in
  *.mp3) TYPE=audio/mpeg ;;
  *.wav) TYPE=audio/wav ;;
  *.m4a) TYPE=audio/mp4 ;;
  *.ogg) TYPE=audio/ogg ;;
  *.flac) TYPE=audio/flac ;;
  *) echo "неизвестный тип: $NAME" >&2; exit 2 ;;
esac

if "$AAPT2" dump permissions "$APK" | grep -E 'INTERNET|ACCESS_NETWORK_STATE'; then
  echo "в APK объявлен доступ к сети" >&2
  exit 1
fi
echo "сетевых разрешений нет"

"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell rm -rf /sdcard/Download/Stenokot
"${ADB[@]}" install -r -g "$APK"
"${ADB[@]}" push "$AUDIO" "/sdcard/Download/$NAME" >/dev/null
"${ADB[@]}" shell cmd connectivity airplane-mode enable
trap '"${ADB[@]}" shell cmd connectivity airplane-mode disable' EXIT
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start -a android.intent.action.VIEW -t "$TYPE" \
  -d "file:///sdcard/Download/$NAME" -n "$PKG/.MainActivity"

final=""
for _ in $(seq 1 900); do
  final="$("${ADB[@]}" logcat -d -s PIPELINE:I -v raw | grep -E '^(DONE|DONE_LLM_FAILED|DONE_META_FAILED|FAILED:.*|CANCELLED)$' | tail -1 || true)"
  [ -n "$final" ] && break
  sleep 2
done
echo "итог: ${final:-нет итога за 30 минут}"
[ -n "$final" ] || exit 1
case "$final" in FAILED:*|CANCELLED) exit 1 ;; esac

"${ADB[@]}" pull "/sdcard/Download/Stenokot/$STEM.json" "$OUT/" >/dev/null
"${ADB[@]}" pull "/sdcard/Download/Stenokot/meta/$STEM.metadata.json" "$OUT/" >/dev/null
"${ADB[@]}" pull "/sdcard/Download/Stenokot/meta/$STEM.protocol.json" "$OUT/" >/dev/null 2>&1 || echo "протокола нет"
DURATION="$(grep -o '"audio_duration_ms": *[0-9]*' "$OUT/$STEM.metadata.json" | grep -o '[0-9]*$')"
cd "$ROOT/src/mobile/android"
"${GRADLE:-./gradlew}" -q :core:validateTranscript --args="$OUT/$STEM.json $DURATION"
