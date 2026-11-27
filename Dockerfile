FROM node:22-alpine AS web-builder
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm ci
COPY web ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY protocol-core protocol-core
COPY server server
COPY qsign-server qsign-server
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/server/build/install/server /app
COPY --from=web-builder /web/dist /app/static
RUN mkdir -p /app/data/sessions
EXPOSE 8080
ENV AUTOSININ_PORT=8080 \
    AUTOSININ_HOST=0.0.0.0 \
    AUTOSININ_DB_PATH=/app/data/autosinin.db \
    AUTOSININ_SESSION_DIR=/app/data/sessions
ENTRYPOINT ["/app/bin/server"]
