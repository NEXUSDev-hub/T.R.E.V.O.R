# T.R.E.V.O.R. ESP32-C3 BLE robot firmware

This sketch pairs with `TrevorRobotBleController.kt`. It supports the planned two-motor TB6612FNG drive base; it does not provide obstacle avoidance or battery measurement without extra sensors.

## BLE service

- Device name: `TREVOR-ROBOT`
- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Phone-to-ESP32 write characteristic: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- ESP32-to-phone notify characteristic: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

## Wire protocol

Commands are UTF-8 and newline terminated:

- `D,<left>,<right>,<sequence>\n` — independent signed motor speed, each value -255..255; positive is forward, negative is reverse.
- `S,<sequence>\n` — priority stop.
- `ACK,<sequence>,OK\n` / `ACK,<sequence>,ERR\n` — command acknowledgement.
- `STAT,<left>,<right>,-1,0\n` — periodic motor status. Battery millivolts is -1 because no battery sensor is assumed.
- `FAULT,MOVEMENT_TIMEOUT\n` — movement stopped by firmware watchdog.

The app refreshes held movement commands every 150 ms. Firmware independently stops movement if no command arrives for 500 ms, or as soon as BLE disconnects. A stop command clears queued movement commands in the app.

## Before flashing

1. Install/select the ESP32 Arduino core and choose your exact ESP32-C3 board.
2. Verify GPIO assignments at the top of the sketch against the actual board and wiring. These are examples, not guaranteed for every SuperMini clone.
3. Connect TB6612FNG logic VCC to the correct 3.3 V logic supply and share GND between ESP32, driver, and motor supply.
4. Power motors from a suitable separate motor supply; never power motors from the ESP32 3.3 V pin.
5. Keep wheels off the floor for first tests. Confirm each wheel's direction and stop behavior before putting the robot down.

This source has not yet been compiled or tested on physical hardware. Pin mapping and motor polarity must be verified before driving.
