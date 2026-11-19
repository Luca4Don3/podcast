.PHONY: help android-test android-build apple-test apple-simulator clean

help:
	@echo "android-test     运行 Android JVM 单元测试"
	@echo "android-build    导出 Android 通用包与 ABI 包"
	@echo "apple-test       运行共享 Apple API 单元测试"
	@echo "apple-simulator  构建 iPhone/iPad 通用模拟器包"

android-test:
	cd android && ./gradlew :app:testCompactUnitTest

android-build:
	cd android && ./gradlew :app:testCompactUnitTest :app:exportCompactApks

apple-test:
	swift test --package-path apple

apple-simulator:
	bash apple/build-simulator.sh output/apple

clean:
	rm -rf android/app/build dist output/apple apple/.build
