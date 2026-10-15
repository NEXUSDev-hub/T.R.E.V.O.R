# T.R.E.V.O.R Robot — BLE Protocols and Compatibility

Status: two prototypes exist; they are **not wire-compatible**. Do not treat this document as evidence of hardware validation.

## Active manual-drive prototype

The Android screen `TrevorRobotControlScreen.kt` uses `TrevorRobotBleController.kt` and pairs with the Arduino sketch at `firmware/esp32c3_trevor_ble/esp32c3_trevor_ble.ino`.

- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- RX characteristic (phone → ESP32): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- TX characteristic (ESP32 → phone): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
- Device name: `TREVOR-ROBOT`

Commands are UTF-8, newline-terminated frames:

- `D,<left>,<right>,<sequence>\n` — signed left/right motor PWM values from -255 to 255.
- `S,<sequence>\n` — stop immediately.
- `ACK,<sequence>,OK\n` or `ACK,<sequence>,ERR\n` — command acknowledgement.
- `STAT,<left>,<right>,-1,0\n` — status; battery millivolts are -1 until a voltage sensor is added.
- `FAULT,MOVEMENT_TIMEOUT\n` — the firmware watchdog stopped motion.

The app refreshes held movement every 150 ms. Firmware stops motion after 500 ms without a movement command and on BLE disconnect. STOP clears queued movement frames in the app, but a frame already in flight cannot be withdrawn from the BLE stack.

### Safety and security limitations

This prototype does **not** authenticate or encrypt the control session. A nearby compatible BLE client may be able to issue movement commands. Use only in a controlled test area, keep wheels raised for first tests, and do not operate around people, pets, stairs, roads, or fragile objects. Firmware GPIO values are examples and must be checked against the exact ESP32-C3 board and wiring. Neither this sketch nor the Android app has been verified on physical hardware yet.

## Separate M1 safety-oriented scaffold

The files under `robot/esp32/` are a different, security-oriented M1 scaffold. They use different UUIDs:

- Service: `6f726576-6f72-4d31-9f52-545245564f52`
- RX: `6f726576-6f72-5258-9f52-545245564f52`
- TX: `6f726576-6f72-5458-9f52-545245564f52`

That scaffold uses text commands such as `FORWARD 80\n`, `STOP 0\n`, and a `CLIENT_READY` handshake. Its movement authorization is intentionally locked until a future authenticated control-session implementation enables it. The current Android manual-drive screen does not implement that handshake, so **do not pair this scaffold with the active manual-drive screen and expect movement**.

Keep these implementations separate until a deliberate protocol migration is designed, implemented, and covered by cross-platform tests.
