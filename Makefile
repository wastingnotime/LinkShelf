.PHONY: mobile-test mobile-build mobile-apk mobile-connected-test

mobile-test:
	cd apps/mobile && ./gradlew :app:testDebugUnitTest

mobile-build:
	cd apps/mobile && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

mobile-apk: mobile-build
	@echo apps/mobile/app/build/outputs/apk/debug/app-debug.apk

mobile-connected-test:
	@test -n "$(ANDROID_SERIAL)" || (echo 'Set ANDROID_SERIAL to the test emulator/device serial'; exit 1)
	cd apps/mobile && ANDROID_SERIAL="$(ANDROID_SERIAL)" ./gradlew :app:connectedDebugAndroidTest
