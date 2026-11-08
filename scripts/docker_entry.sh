#!/usr/bin/env bash
# Точка входа контейнера сборки. Шаги и флаги повторяют .github/workflows/release.yml.
#   models  скачать модели в том /models и сверить с config/models.lock
#   test    модели и модульные тесты
#   apk     модели и release-APK с моделями в /out/skvor-<коммит>.apk
#   all     модели, тесты и APK, как в CI
set -euo pipefail

ROOT=/src
ANDROID="$ROOT/src/mobile/android"
OUT=/out
# Gradle без параллельных проектов и не больше двух рабочих потоков, чтобы не исчерпать память.
GRADLE=(./gradlew --console=plain --no-daemon --no-parallel --max-workers=2)

models() {
  "$ROOT/scripts/fetch_models.sh"
}

signing() {
  local props=/signing/keystore.properties store
  if [ ! -f "$props" ]; then
    echo "keystore.properties не найден, APK будет подписан отладочным ключом"
    return
  fi
  store=$(sed -n 's/^storeFile=//p' "$props")
  case "$store" in
    /*) echo "storeFile задан абсолютным путём хоста и недоступен в контейнере, APK будет подписан отладочным ключом"; return ;;
  esac
  if [ ! -f "/signing/$store" ]; then
    echo "Ключ $store не найден рядом с keystore.properties, APK будет подписан отладочным ключом"
    return
  fi
  mkdir -p "$(dirname "$ANDROID/$store")"
  cp "/signing/$store" "$ANDROID/$store"
  cp "$props" "$ANDROID/keystore.properties"
  echo "APK будет подписан ключом из keystore.properties"
}

tests() {
  cd "$ANDROID"
  "${GRADLE[@]}" :core:test :app:testDevDebugUnitTest
}

apk() {
  signing
  cd "$ANDROID"
  "${GRADLE[@]}" :app:assembleFullRelease
  local apk aapt2 sha name
  apk=$(find app/build/outputs/apk -path '*/full/release/*.apk' | head -1)
  [ -n "$apk" ] || { echo "APK не найден в app/build/outputs/apk" >&2; exit 1; }
  aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
  if "$aapt2" dump permissions "$apk" | grep -E 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)'; then
    echo "В APK есть сетевые разрешения" >&2
    exit 1
  fi
  sha=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
  name="skvor-$sha.apk"
  mkdir -p "$OUT"
  cp "$apk" "$OUT/$name"
  (cd "$OUT" && sha256sum "$name" | tee "$name.sha256")
  if [ -n "${HOST_UID:-}" ]; then
    chown "$HOST_UID:${HOST_GID:-$HOST_UID}" "$OUT/$name" "$OUT/$name.sha256"
  fi
  echo "Готово: out/$name"
}

case "${1:-apk}" in
  models) models ;;
  test) models; tests ;;
  apk) models; apk ;;
  all) models; tests; apk ;;
  *) echo "Использование: docker_entry.sh models|test|apk|all" >&2; exit 2 ;;
esac
