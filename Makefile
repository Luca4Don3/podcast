.PHONY: help build-cpp test-cpp run-cpp build-go test-go run-go android-build deploy-up deploy-down clean

help:
	@echo "build-cpp     编译 C++ 后端（默认）"
	@echo "test-cpp      运行 C++ 单元测试"
	@echo "run-cpp       本地运行 C++ 后端"
	@echo "build-go      编译 Go 后端（备选实现）"
	@echo "test-go       运行 Go 后端测试"
	@echo "run-go        本地运行 Go 后端"
	@echo "android-build 编译 Debug APK"
	@echo "deploy-up     用 docker compose 启动 api + caddy"
	@echo "deploy-down   停止服务"

build-cpp:
	cmake -S server-cpp -B server-cpp/build -DCMAKE_BUILD_TYPE=Release
	cmake --build server-cpp/build -j"$$(nproc)"

test-cpp: build-cpp
	./server-cpp/build/podcast-tests

run-cpp: build-cpp
	cd server-cpp && ./build/podcast-server -env-file .env

build-go:
	cd server && go build -trimpath -ldflags "-s -w" -o bin/podcast-server ./cmd/server

test-go:
	cd server && go test ./...

run-go:
	cd server && go run ./cmd/server

android-build:
	cd android && ./gradlew :app:assembleDebug

deploy-up:
	cd deploy && docker compose up -d --build

deploy-down:
	cd deploy && docker compose down

clean:
	rm -rf server-cpp/build server/bin dist
