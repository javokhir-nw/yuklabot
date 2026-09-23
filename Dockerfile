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

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        ffmpeg \
        curl \
        ca-certificates \
    && curl -L \
        https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp \
    && pip3 install --break-system-packages gallery-dl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r spring \
    && useradd -r -g spring spring

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN chown spring:spring /app/app.jar

USER spring:spring

ENTRYPOINT ["java", "-jar", "app.jar"]