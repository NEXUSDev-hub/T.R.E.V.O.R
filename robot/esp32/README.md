# T.R.E.V.O.R Robot — M1 ESP32 Firmware

M1 now contains the concrete BLE transport boundary while keeping authentication as a separate M2 responsibility.

## Structure

- `src/main.cpp` — ESP32 entry point and safety-controlled command loop.
- `src/trevor_ble_transport.cpp` — Arduino-ESP32 BLE server, advertising, RX/TX characteristics, connection handling.
- `include/trevor_ble_transport.h` — BLE transport interface.
- `include/trevor_protocol.h` — transport-independent command/response vocabulary and BLE UUIDs.
- `src/trevor_protocol.cpp` — command parser and response encoder.
- `include/trevor_motor_driver.h` — motor-driver abstraction.
- `include/trevor_speaker.h` — speaker/status abstraction.
- `include/trevor_safety.h` — heartbeat safety state.

## BLE contract

Service:
`6f726576-6f72-4d31-9f52-545245564f52`

RX (phone → robot):
`6f726576-6f72-5258-9f52-545245564f52`

TX (robot → phone):
`6f726576-6f72-5458-9f52-545245564f52`

RX accepts one complete ASCII application frame per BLE write. TX sends newline-delimited responses as notifications.

## Security boundary

M1 advertises and accepts BLE connections, but **movement is locked** until M2 authentication explicitly sets the control session as authenticated.

Authentication is cleared on every disconnect.

BLE UUIDs are identifiers, not credentials.

## Safety boundary

- Boot/reset starts stopped.
- Disconnect causes authorization loss and therefore motor stop.
- Authentication loss cannot grant movement.
- Invalid commands stop the robot.
- Heartbeat timeout stops the robot.
- Reconnection does not resume previous motion.
- Speed is validated before motor control.
- Serial diagnostics cannot bypass the M1 authorization gate.

## Build note

The transport uses the Arduino-ESP32 BLE API (`BLEDevice.h`, `BLEServer.h`, `BLECharacteristic.h`). The exact ESP32 board/core version should be pinned before claiming a hardware build is verified.

## Next

M2: implement Android BLE discovery/connection and a real authenticated control-session handshake.
