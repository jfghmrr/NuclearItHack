#!/usr/bin/env bash
# Скачивает модели и нативные библиотеки sherpa-onnx, проверяет SHA-256 по config/models.lock.
# Каталог моделей задаётся переменной STENOKOT_MODELS_DIR, по умолчанию <корень>/models.
# Ключ --update-lock пересчитывает config/models.lock по скачанным файлам.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${STENOKOT_MODELS_DIR:-$ROOT/models}"
JNI="$ROOT/src/mobile/android/app/src/main/jniLibs/arm64-v8a"
LOCK="$ROOT/config/models.lock"
CACHE="$DIR/.cache"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
GIGA="sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
PARA="sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
GGUF_URL="https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf"

mkdir -p "$DIR"/{asr-ru,asr-en,vad,diar,llm} "$CACHE" "$JNI"

fetch() {
  local url="$1" out="$2"
  [ -s "$out" ] && return 0
  curl -fL --retry 5 --retry-delay 3 -C - -o "$out.part" "$url"
  mv "$out.part" "$out"
}

fetch "$GH/asr-models/$GIGA.tar.bz2" "$CACHE/$GIGA.tar.bz2"
if [ ! -s "$DIR/asr-ru/tokens.txt" ]; then
  tar -xjf "$CACHE/$GIGA.tar.bz2" -C "$CACHE"
  cp "$CACHE/$GIGA"/{encoder*.onnx,decoder*.onnx,joiner*.onnx,tokens.txt} "$DIR/asr-ru/"
  mkdir -p "$DIR/samples" && cp -R "$CACHE/$GIGA/test_wavs/." "$DIR/samples/" 2>/dev/null || true
fi

fetch "$GH/asr-models/$PARA.tar.bz2" "$CACHE/$PARA.tar.bz2"
# Каталог пересобирается и тогда, когда в нём лежит другая модель: сверка идёт по замку.
if ! (cd "$DIR" && grep ' asr-en/' "$LOCK" | shasum -a 256 -c --quiet >/dev/null 2>&1); then
  tar -xjf "$CACHE/$PARA.tar.bz2" -C "$CACHE"
  rm -f "$DIR"/asr-en/*
  cp "$CACHE/$PARA"/{encoder*.onnx,decoder*.onnx,joiner*.onnx,tokens.txt} "$DIR/asr-en/"
fi

fetch "$GH/asr-models/silero_vad.onnx" "$DIR/vad/silero_vad.onnx"

fetch "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$CACHE/seg.tar.bz2"
if [ ! -s "$DIR/diar/segmentation.int8.onnx" ]; then
  tar -xjf "$CACHE/seg.tar.bz2" -C "$CACHE"
  cp "$CACHE/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$DIR/diar/segmentation.int8.onnx"
fi

fetch "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx" "$DIR/diar/titanet_small.onnx"
fetch "$GGUF_URL" "$DIR/llm/qwen3-0.6b-q4_k_m.gguf"

fetch "$GH/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2" "$CACHE/sherpa-android.tar.bz2"
if [ ! -s "$JNI/libsherpa-onnx-jni.so" ]; then
  mkdir -p "$CACHE/sherpa-android"
  tar -xjf "$CACHE/sherpa-android.tar.bz2" -C "$CACHE/sherpa-android"
  find "$CACHE/sherpa-android" -path '*arm64-v8a*' \( -name 'libonnxruntime.so' -o -name 'libsherpa-onnx-jni.so' \) -exec cp {} "$JNI/" \;
fi

cd "$DIR"
if [ "${1:-}" = "--update-lock" ]; then
  find asr-ru asr-en vad diar llm -type f | LC_ALL=C sort | xargs shasum -a 256 > "$LOCK"
  echo "обновлён $LOCK"
else
  shasum -a 256 -c "$LOCK" --quiet
  echo "модели проверены: $DIR"
fi
