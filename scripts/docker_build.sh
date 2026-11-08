#!/usr/bin/env bash
# Сборка «Сквор» в Docker одной командой.
#   ./scripts/docker_build.sh         release-APK в out/skvor-<коммит>.apk и out/skvor-<коммит>.apk.sha256
#   ./scripts/docker_build.sh test    модульные тесты
#   ./scripts/docker_build.sh all     тесты и APK, как в CI
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
TARGET="${1:-apk}"
case "$TARGET" in
  apk|test|all) ;;
  *) echo "Использование: $0 [apk|test|all]" >&2; exit 2 ;;
esac

command -v docker >/dev/null || { echo "Нужен Docker с плагином compose" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker не запущен" >&2; exit 1; }

if [ ! -f third_party/llama.cpp/CMakeLists.txt ]; then
  git submodule update --init --depth 1 third_party/llama.cpp
fi

GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || true)
HOST_UID=$(id -u)
HOST_GID=$(id -g)
export GIT_COMMIT HOST_UID HOST_GID
mkdir -p out

docker compose build apk
SERVICE=apk
[ "$TARGET" = test ] && SERVICE=test
if [ "$TARGET" = all ]; then
  docker compose run --rm apk all
else
  docker compose run --rm "$SERVICE"
fi
