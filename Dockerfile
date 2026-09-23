# ============================================
# 1-BOSQICH: BUILD
# ============================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw

RUN ./mvnw dependency:go-offline -B

COPY src src

RUN ./mvnw package -DskipTests

RUN mkdir -p target/dependency \
    && cd target/dependency \
    && jar -xf ../*.jar


# ============================================
# 2-BOSQICH: RUNTIME
# ============================================
FROM eclipse-temurin:21-jre

# yt-dlp + ffmpeg uchun kerakli paketlar
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        curl \
        ca-certificates \
    && curl -L \
        https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN groupadd -r spring \
    && useradd -r -g spring spring

WORKDIR /app

ARG DEPENDENCY=/app/target/dependency

COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

USER spring:spring

ENTRYPOINT ["java","-cp","app:app/lib/*","com.javier.telegrambot.TelegramBotApplication"]