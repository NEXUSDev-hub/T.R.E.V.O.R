# T.R.E.V.O.R Robot — M1 ESP32 Firmware Skeleton

M1 establishes the robot-side software boundary without requiring physical hardware.

## Structure

- `src/main.cpp` — Arduino/ESP32 entry point and safety-controlled command loop.
- `include/trevor_protocol.h` — transport-independent command/response vocabulary.
- `include/trevor_motor_driver.h` — motor-driver abstraction.
- `include/trevor_speaker.h` — speaker/status abstraction.
- `include/trevor_safety.h` — connection/heartbeat safety state.

## M1 guarantees

- Boot state is STOPPED.
- Unknown commands never drive motors.
- STOP immediately requests stopped motor output.
- Commands are bounded by a heartbeat timeout.
- Speed is range-checked before reaching the motor abstraction.
- BLE transport can be added without coupling the protocol parser to motor code.

## Hardware

GPIO assignments are intentionally not finalized in M1. The motor driver abstraction prevents accidental hardware assumptions before the exact ESP32 board and motor driver are selected.

## Next

Phase M1 will add the concrete BLE service/characteristic UUIDs and wire encoding once the exact ESP32 board and transport implementation are selected.
