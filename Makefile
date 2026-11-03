.PHONY: mobile-test mobile-build mobile-apk mobile-connected-test mobile-run

ANDROID_AVD ?= my-feed-wnt-222
ANDROID_SDK_DIR ?= $(shell sed -n 's/^sdk.dir=//p' apps/mobile/local.properties 2>/dev/null)

mobile-test:
	cd apps/mobile && ./gradlew :app:testDebugUnitTest

mobile-build:
	cd apps/mobile && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

mobile-apk: mobile-build
	@echo apps/mobile/app/build/outputs/apk/debug/app-debug.apk

mobile-connected-test:
	@test -n "$(ANDROID_SERIAL)" || (echo 'Set ANDROID_SERIAL to the test emulator/device serial'; exit 1)
	cd apps/mobile && ANDROID_SERIAL="$(ANDROID_SERIAL)" ./gradlew :app:connectedDebugAndroidTest

# Start the configured AVD if the requested emulator is not already running.
# Defaults to emulator-5554; set ANDROID_SERIAL / ANDROID_AVD to select another.
mobile-run:
	cd apps/mobile && ./gradlew :app:assembleDebug
	@set -eu; \
	serial="$(ANDROID_SERIAL)"; \
	if [ -z "$$serial" ]; then \
		serial="$$(adb devices | awk 'NR > 1 && $$2 == "device" { print $$1; exit }')"; \
	fi; \
	if [ -z "$$serial" ]; then serial=emulator-5554; fi; \
	if ! adb -s "$$serial" get-state >/dev/null 2>&1; then \
		case "$$serial" in emulator-*) port="$${serial#emulator-}" ;; *) echo "Device $$serial is not connected, and it is not an emulator serial."; exit 1 ;; esac; \
		command -v "$(ANDROID_SDK_DIR)/emulator/emulator" >/dev/null || { echo "Android emulator not found under ANDROID_SDK_DIR=$(ANDROID_SDK_DIR)"; exit 1; }; \
		echo "Starting $(ANDROID_AVD) as $$serial"; \
		"$(ANDROID_SDK_DIR)/emulator/emulator" -avd "$(ANDROID_AVD)" -port "$$port" -no-snapshot -no-boot-anim >/tmp/my-feed-mobile-run-emulator.log 2>&1 & \
	fi; \
	adb -s "$$serial" wait-for-device; \
	attempt=0; until [ "$$(adb -s "$$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do \
		attempt=$$((attempt + 1)); \
		if [ "$$attempt" -ge 180 ]; then echo "Android did not finish booting on $$serial"; exit 1; fi; \
		sleep 2; \
	done; \
	adb -s "$$serial" install -r apps/mobile/app/build/outputs/apk/debug/app-debug.apk; \
	adb -s "$$serial" shell am start -W -n org.wastingnotime.linkshelf/.MainActivity
