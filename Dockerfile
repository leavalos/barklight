FROM eclipse-temurin:17-jdk-jammy AS builder
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_VERSION=8.2 \
    ANDROID_BUILD_TOOLS=34.0.0 \
    ANDROID_PLATFORM=android-34 \
    DEBIAN_FRONTEND=noninteractive
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}"
RUN apt-get update -q && apt-get install -y --no-install-recommends curl unzip wget ca-certificates && rm -rf /var/lib/apt/lists/*
RUN wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/ct.zip /tmp/ct
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}"
WORKDIR /project
COPY . .
RUN gradle assembleDebug --project-dir /project --no-daemon --quiet && echo "APK compilada"

FROM alpine:3.19 AS output
COPY --from=builder /project/app/build/outputs/apk/debug/app-debug.apk /apk/huesync-tv.apk
RUN printf '#!/bin/sh\ncp /apk/huesync-tv.apk /output/huesync-tv.apk\necho "APK lista en ./output/huesync-tv.apk"\n' > /entrypoint.sh && chmod +x /entrypoint.sh
VOLUME ["/output"]
ENTRYPOINT ["/entrypoint.sh"]
