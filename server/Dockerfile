# ---- 构建阶段 ----
FROM golang:1.23-alpine AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOOS=linux
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags "-s -w" -o /out/podcast-server ./cmd/server

# ---- 运行阶段 ----
FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata curl && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/podcast-server /app/podcast-server
RUN mkdir -p /data && chown -R app:app /data /app
USER app
ENV APP_ADDR=:8080 APP_DATA_DIR=/data APP_ENV=production
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/podcast-server", "-env-file", "/app/.env"]
