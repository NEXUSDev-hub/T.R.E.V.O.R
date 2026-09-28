# T.R.E.V.O.R Robot — BLE Protocol v1

Status: M1 protocol contract locked  
Date: 2026-09-28

## 1. Transport

Bluetooth Low Energy is the V1 transport.

The application protocol uses **newline-delimited ASCII**. This is intentionally human-readable so the ESP32 can be tested from a serial/BLE debugging tool before the Android controller exists.

- One frame = one line.
- Frames end with \n.
- Maximum application frame length: 64 bytes, excluding the newline.
- UTF-8/ASCII command text is uppercase and space-separated.
- Unknown, malformed, oversized, or out-of-range frames are rejected and must not move the robot.

## 2. UUIDs

These UUIDs are fixed for T.R.E.V.O.R Robot Protocol v1:

- Service: `6f726576-6f72-4d31-9f52-545245564f52`
- RX characteristic (phone → robot): `6f726576-6f72-5258-9f52-545245564f52`
- TX characteristic (robot → phone): `6f726576-6f72-5458-9f52-545245564f52`

RX is writable. TX supports notifications.

The UUIDs identify the T.R.E.V.O.R application protocol; they are not an authentication mechanism.

## 3. Robot identity

A robot advertises a unique logical ID such as:

`TREVOR-RBT-A7F3`

The current M1 firmware skeleton uses `TREVOR-RBT-M1` as its test identity. A hardware-specific unique ID is a later configuration step.

The Android app must not treat a Bluetooth MAC address as the robot's identity.

## 4. Command frames

### Movement

`FORWARD 80\n`  
`BACKWARD 50\n`  
`LEFT 40\n`  
`RIGHT 40\n`

### Stop

`STOP 0\n`

A missing speed defaults to 100 for movement commands in the current parser. Android should send an explicit speed once the controller is implemented.

Speed is an integer from 0 through 100.

## 5. Heartbeat

The safety layer uses a heartbeat watchdog. The transport-level heartbeat frame is:

`HB\n`

An authenticated control session will be required before heartbeat/control frames can authorize movement. Authentication is an M2 concern; M1 must not pretend UUIDs alone provide authorization.

## 6. Responses

Examples:

`HELLO TREVOR-RBT-M1 1\n`  
`READY\n`  
`ACK FORWARD 80\n`  
`STATUS FORWARD 80\n`  
`ERROR\n`

Response meanings:

- HELLO — robot identity + protocol version.
- READY — firmware initialized.
- ACK — command accepted by protocol/safety layers.
- STATUS — current commanded state.
- ERROR — frame rejected or an internal error occurred.

## 7. Safety contract

1. Boot starts stopped.
2. STOP requests immediate stop.
3. Invalid commands request stop.
4. Speed is validated on the ESP32.
5. Missing heartbeat causes stop.
6. Reconnection never automatically resumes the previous movement command.
7. Authentication is separate from BLE discovery.
8. Motor code is downstream of protocol validation and safety state.

## 8. M1 test vectors

| Input | Expected result |
|---|---|
| `FORWARD 80\n` | FORWARD, speed 80 |
| `STOP 0\n` | STOP, speed 0 |
| `LEFT\n` | LEFT, speed 100 |
| `BACKWARD 101\n` | INVALID |
| `FLY 80\n` | INVALID |
| empty frame | INVALID |

## 9. Next milestone

M1 transport implementation will bind these UUIDs to the ESP32 BLE stack. M2 will add the Android BLE client and authenticated control session.
