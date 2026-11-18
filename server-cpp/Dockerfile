# ---- Alpine 容器构建阶段，默认不附带 WebDAV 依赖 ----
FROM alpine:3.20 AS build
ARG PODCAST_ENABLE_WEBDAV=OFF
RUN apk add --no-cache build-base cmake openssl-dev sqlite-dev linux-headers \
 && if [ "$PODCAST_ENABLE_WEBDAV" = ON ]; then apk add --no-cache curl-dev libxml2-dev; fi
WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
COPY tests ./tests
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DPODCAST_ENABLE_SSL=ON \
    -DPODCAST_ENABLE_WEBDAV="$PODCAST_ENABLE_WEBDAV" \
 && cmake --build build -j"$(nproc)" \
 && ctest --test-dir build --output-on-failure

# ---- 运行阶段 ----
FROM alpine:3.20
ARG PODCAST_ENABLE_WEBDAV=OFF
RUN apk add --no-cache libstdc++ libgcc openssl sqlite-libs ca-certificates curl \
 && if [ "$PODCAST_ENABLE_WEBDAV" = ON ]; then apk add --no-cache libxml2; fi \
 && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /src/build/podcast-server /app/podcast-server
RUN mkdir -p /data && chown -R app:app /data /app
USER app
ENV APP_ADDR=:8080 APP_ENV=production APP_DATA_DIR=/data
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/podcast-server", "-env-file", "/app/.env"]
