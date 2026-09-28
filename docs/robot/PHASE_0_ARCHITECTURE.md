# T.R.E.V.O.R Robot — Phase 0 Architecture

Status: LOCKED  
Version: Robot V1 / Phase 0  
Date: 2026-09-28

## 1. Goal

Build a simple tray-carrying mobile robot controlled by the T.R.E.V.O.R Android app.

V1 is intentionally small:
- Drive around.
- Carry a tray/platform.
- Accept control from a T.R.E.V.O.R phone.
- Provide basic status/audio feedback.
- Keep the robot inexpensive and easy to repair.

No arm, gripper, camera, ultrasonic sensor, LiDAR, GPS, IMU, Raspberry Pi, or separate Wi-Fi module in V1.

## 2. System architecture

```
User
  |
  v
T.R.E.V.O.R Android app
  |
  | Bluetooth Low Energy
  v
ESP32 robot controller
  |
  v
Motor driver
  |
  +--> Left geared DC motor
  +--> Right geared DC motor

ESP32
  |
  +--> Speaker / buzzer
  +--> Robot status
  +--> Safety stop handling
```

The Android app is the universal controller. The ESP32 is the local real-time hardware controller.

## 3. Responsibilities

### Android / T.R.E.V.O.R
- Discover nearby T.R.E.V.O.R robots.
- Show robot identity and connection state.
- Pair/connect to a selected robot.
- Send movement commands.
- Send optional speed values.
- Display ACK/STATUS/ERROR responses.
- Handle connection loss.
- Never assume a command succeeded without an acknowledgement where applicable.
- Provide an obvious STOP control.

### ESP32
- Advertise a T.R.E.V.O.R robot identity.
- Accept an authenticated control connection.
- Parse and validate commands.
- Drive the motors.
- Stop motors on STOP, invalid command, or safety timeout.
- Return HELLO/READY/ACK/STATUS/ERROR responses.
- Control basic speaker/status feedback.
- Remain functional without the Android UI after a connection loss by entering a safe stopped state.

### Motor driver
- Electrically drive the two DC motors.
- Receive direction/PWM signals from the ESP32.
- Keep motor current away from the ESP32 GPIO pins.

## 4. V1 hardware boundary

Required:
- ESP32 development board
- Dual-channel motor driver
- 2 geared DC motors
- 2 drive wheels
- 1 caster/support wheel
- Simple chassis
- Tray/platform
- Battery suitable for the selected motors/driver
- Power switch
- Wires/connectors/mounting hardware
- Small speaker or buzzer

Excluded from V1:
- Robotic arm
- Gripper
- Camera
- Distance sensors
- GPS
- IMU
- Raspberry Pi
- Dedicated Wi-Fi module

## 5. Control protocol

The protocol is deliberately simple and transport-independent so it can be implemented over BLE first and changed later without rewriting the robot logic.

### Robot identity

Example:

`TREVOR-RBT-A7F3`

Each robot gets a unique ID.

### Commands

- `FORWARD`
- `BACKWARD`
- `LEFT`
- `RIGHT`
- `STOP`

Optional command field:
- `SPEED` — bounded speed value selected by the app.

### Robot responses

- `HELLO` — robot announces identity/protocol.
- `READY` — controller is initialized and ready.
- `ACK` — command accepted.
- `STATUS` — current robot state.
- `ERROR` — command or hardware error.

Phase 1 will define the exact wire encoding, framing, limits, and UUIDs.

## 6. Safety rules

Safety is a core requirement, not a later feature.

1. Physical power switch must be available.
2. `STOP` must be implemented independently of any UI animation.
3. ESP32 must stop the motors when the control connection is lost or the command heartbeat times out.
4. Unknown/malformed commands must never result in motor movement.
5. Speed values must be range-checked by the ESP32.
6. Android must surface connection-loss state clearly.
7. The app must not silently resume motion after reconnecting.
8. Motor control must default to stopped during boot/reset.

## 7. Pairing and authorization

V1 goal:
- Anyone may discover a robot.
- A T.R.E.V.O.R app may initiate a connection.
- Movement commands require an authenticated control session.

The protocol reserves room for challenge-response authentication.

Phase 1 will implement the first concrete authentication mechanism. The robot must not rely only on a Bluetooth MAC address as its identity.

## 8. Software layers

### Android
`Robot UI -> Robot Controller -> BLE Transport -> Protocol -> Authentication`

### ESP32
`BLE Transport -> Authentication -> Protocol Parser -> Safety Controller -> Motor/Speaker Drivers`

The protocol layer must not directly contain Android UI or motor-driver code.

## 9. Connection state machine

```
DISCONNECTED
    |
    v
SCANNING
    |
    v
FOUND
    |
    v
CONNECTING
    |
    v
AUTHENTICATING
    |
    v
READY
    |
    +--> CONTROL ACTIVE
    |
    +--> DISCONNECTED -> STOPPED
```

The ESP32's physical motor state is always governed by the safety controller, not by the Android connection-state display.

## 10. Planned milestones

### Phase 0 — Architecture
This document. No hardware control code.

### M1 — ESP32 protocol + firmware skeleton
- Define BLE service/characteristic UUIDs.
- Define wire format.
- Implement robot identity.
- Implement parser/validator.
- Implement safety timeout.
- Add motor-driver abstraction.
- Add speaker/status abstraction.
- Test without the Android app.

### M2 — Android BLE + authentication
- Scan.
- Discover T.R.E.V.O.R robots.
- Connect.
- Authenticate.
- Send commands.
- Process responses.
- Handle disconnects.

### M3 — Robot control UI
- Direction controls.
- Stop.
- Speed control.
- Robot selector.
- Connection/status display.

### M4 — Text + voice control
- Natural-language commands.
- Optional voice input.
- Convert intent to safe robot commands.
- Require confirmation for ambiguous/high-impact commands.

### M5 — Safety + integration
- End-to-end testing.
- Connection-loss tests.
- Emergency-stop tests.
- Battery/power tests.
- Final Android build and robot integration.

## 11. Design constraints

- Keep V1 cheap: target roughly ₹800–₹900, with ₹1000 as the practical ceiling.
- Android phone does the high-level control work.
- ESP32 handles deterministic local control.
- Avoid unnecessary hardware.
- Keep protocol modular and documented.
- No autonomous navigation in V1.
- No cloud dependency for basic manual driving.
- T.R.E.V.O.R should be able to control multiple uniquely identified robots in future without changing the core protocol.

## 12. Phase 0 exit criteria

Phase 0 is complete when:
- Architecture is documented.
- Android/ESP32 responsibilities are separated.
- V1 hardware boundary is fixed.
- Command/response vocabulary is fixed.
- Safety requirements are fixed.
- Milestones are defined.

Next implementation target: M1 — ESP32 protocol + firmware skeleton.
