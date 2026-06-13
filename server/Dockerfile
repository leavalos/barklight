FROM eclipse-temurin:17-jdk-jammy AS builder

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_VERSION=8.2 \
    ANDROID_PLATFORM=android-30 \
    DEBIAN_FRONTEND=noninteractive

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

RUN apt-get update -q && apt-get install -y --no-install-recommends \
        curl unzip wget ca-certificates && rm -rf /var/lib/apt/lists/*

# Gradle
RUN wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -O /tmp/gradle.zip && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle

# Android SDK command-line tools — necesitamos android.jar para compilar
# contra android.view.SurfaceControl (API oculta pero presente en el jar)
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
             -O /tmp/ct.zip && unzip -q /tmp/ct.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/ct.zip /tmp/ct

# Android 11 = API 30, para que coincida con el TV
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager "platforms;${ANDROID_PLATFORM}"

WORKDIR /project
COPY . .

# Copiar android.jar a android-stubs/
RUN mkdir -p android-stubs \
    && cp ${ANDROID_SDK_ROOT}/platforms/${ANDROID_PLATFORM}/android.jar android-stubs/

RUN gradle jar --no-daemon --quiet && echo "JAR compilado"

FROM alpine:3.19 AS output
COPY --from=builder /project/build/libs/barklight-server.jar /jar/barklight-server.jar

RUN printf '#!/bin/sh\ncp /jar/barklight-server.jar /output/barklight-server.jar\necho "JAR listo en ./output/barklight-server.jar"\n' \
    > /entrypoint.sh && chmod +x /entrypoint.sh

VOLUME ["/output"]
ENTRYPOINT ["/entrypoint.sh"]
