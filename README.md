# T.R.E.V.O.R

**The Really Efficient Virtual Operation Robot** — a modular Android assistant built with Kotlin and Jetpack Compose.

Created by Abhirup Gupta and Ritesh.

## Android app

- Package: `com.trevor.assistant`
- Minimum Android: 9 (API 28)
- Build JDK: 17
- Build/test: `./gradlew testDebugUnitTest assembleDebug`
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

The app includes conversational AI foundations, offline tools, local memory, file/OCR workflows, user-consented screen capture, accessibility-based UI vision, and controlled Android actions. Some capabilities require permissions or a provider API key.

## Robot-control prototype

The current manual-drive screen uses BLE to control a two-motor ESP32-C3/TB6612FNG prototype.

- Android controller: `TrevorRobotBleController.kt`
- Shared protocol: `TrevorRobotProtocol.kt`
- Matching firmware: `firmware/esp32c3_trevor_ble/esp32c3_trevor_ble.ino`
- Protocol and compatibility notes: `docs/robot/BLE_PROTOCOL_V1.md`

Firmware compile check (Arduino CLI, ESP32 core 3.0.7):

```sh
arduino-cli core update-index
arduino-cli core install esp32:esp32@3.0.7
arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/esp32c3_trevor_ble
```

**Safety status:** the manual-drive prototype does not authenticate or encrypt its BLE control session. Only test in a controlled area, with wheels raised initially. Confirm GPIO assignments and motor polarity for the exact board. The app/firmware have not been validated on a physical robot.

The separate `robot/esp32/` folder is an older security-oriented M1 scaffold with different UUIDs and a deliberately locked movement authorization path; it is not compatible with the current manual-drive screen.

## CI

GitHub Actions runs Android unit tests, assembles and verifies the debug APK, and compiles the active ESP32-C3 firmware. Green CI verifies those build steps only; it does not prove real-world Bluetooth behavior or hardware safety.
