# Reproducible build image for Polyphony (Linux x86_64).
# On Apple Silicon this runs under emulation and is slow; build natively there instead
# (see README "Building"). CI uses the same image.
#
#   docker build -t polyphony-build .
#   docker run --rm -v "$PWD":/src -v polyphony-gradle:/root/.gradle polyphony-build
#
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    CMDLINE_TOOLS_VERSION=13114758 \
    GRADLE_OPTS="-Dorg.gradle.daemon=false"

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -sSL -o /tmp/tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/tools.zip

ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

WORKDIR /src
# Default: build a debug APK and run unit tests. Override the command to do anything else.
CMD ["sh", "-c", "./gradlew --no-daemon assembleDebug test && ls -la app/build/outputs/apk/debug/"]
