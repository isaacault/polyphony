# Reproducible build image for Polyphony. CI and scripts/ci.sh use it.
#
# Builds natively on x86_64 and on arm64 Linux (e.g. a Jetson). Google publishes aapt2 for x86_64
# only, so on arm64 the image runs the official binary under qemu user-mode emulation: it adds
# qemu-x86_64-static, an x86_64 glibc sysroot and /opt/aapt2/aapt2 (a wrapper), and Gradle is
# pointed at it with -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 (scripts/ci.sh does this).
# Only resource compilation is emulated; the JVM and everything else run natively.
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

# Must match AGP's own aapt2 (gradle/libs.versions.toml `agp` plus its build number).
ARG AAPT2_VERSION=9.4.1-15978811
RUN if [ "$(dpkg --print-architecture)" = "arm64" ]; then \
        apt-get update && apt-get install -y --no-install-recommends qemu-user-static libc6-amd64-cross libgcc-s1-amd64-cross \
        && rm -rf /var/lib/apt/lists/* \
        && mkdir -p /opt/aapt2 \
        && curl -sSL -o /tmp/aapt2.jar "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/${AAPT2_VERSION}/aapt2-${AAPT2_VERSION}-linux.jar" \
        && unzip -q -o /tmp/aapt2.jar aapt2 -d /opt/aapt2 && mv /opt/aapt2/aapt2 /opt/aapt2/aapt2-x86_64 && rm /tmp/aapt2.jar \
        && printf '#!/bin/sh\nexec qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/aapt2/aapt2-x86_64 "$@"\n' > /opt/aapt2/aapt2 \
        && chmod +x /opt/aapt2/aapt2 /opt/aapt2/aapt2-x86_64 \
        && /opt/aapt2/aapt2 version; \
    fi

WORKDIR /src
# Default: build a debug APK and run unit tests. Override the command to do anything else.
CMD ["sh", "-c", "./gradlew --no-daemon assembleDebug test && ls -la android/app/build/outputs/apk/debug/"]
