# syntax=docker/dockerfile:1
# Окружение сборки APK «Сквор», повторяет .github/workflows/release.yml:
# Ubuntu 24.04, Temurin JDK 21, Android SDK с теми же компонентами.
# Инструменты Android SDK выпускаются только для x86_64, поэтому образ amd64.
# На Apple Silicon он работает через эмуляцию и собирает заметно медленнее.
# Модели в образ не входят: их скачивает scripts/fetch_models.sh при запуске в том /models.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    STENOKOT_MODELS_DIR=/models \
    GRADLE_USER_HOME=/gradle
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update \
 && apt-get install -y --no-install-recommends bzip2 ca-certificates curl git perl unzip \
 && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
 && mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platforms;android-36" "build-tools;36.0.0" \
      "ndk;28.2.13676358" "ndk;29.0.13113456" "cmake;3.31.6" > /dev/null \
 && sdkmanager --list_installed

WORKDIR /src
COPY . /src

# Каталог .git в образ не копируется. Gradle берёт хеш коммита через git rev-parse,
# поэтому хеш передаётся аргументом и записывается в HEAD пустого репозитория.
ARG GIT_COMMIT=
RUN if [ -n "$GIT_COMMIT" ]; then \
      git init -q /src && echo "$GIT_COMMIT" > /src/.git/HEAD && git -C /src rev-parse --short HEAD; \
    fi

WORKDIR /src/src/mobile/android
ENTRYPOINT ["/src/scripts/docker_entry.sh"]
CMD ["apk"]
