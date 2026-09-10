FROM gradle:8-jdk17 AS builder
WORKDIR /app
# Copy project configuration files first to cache dependencies
COPY gradle/ gradle/
COPY build.gradle* settings.gradle* gradlew ./
# Fetch dependencies
RUN ./gradlew dependencies --no-daemon || true
# Copy full source and build the ShadowJar executable
COPY . .
RUN ./gradlew shadowJar --no-daemon

# --- Stage 2: Production Runtime ---
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ffmpeg tzdata
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar ./xhrec.jar
COPY ./docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

VOLUME ["/app/config", "/app/recordings"]
ENV TZ=America/Chicago
EXPOSE 8090
ENTRYPOINT ["/app/entrypoint.sh"]