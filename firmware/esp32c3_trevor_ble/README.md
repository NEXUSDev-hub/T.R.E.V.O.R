# T.R.E.V.O.R. ESP32-C3 BLE robot firmware

This sketch is the firmware counterpart to `TrevorRobotBleController.kt` and `TrevorRobotControlScreen.kt`.

## BLE protocol

- Device name: `TREVOR-ROBOT`
- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write characteristic (phone → ESP32): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Notify characteristic (ESP32 → phone): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
- Commands: `F` forward, `B` reverse, `L` left, `R` right, `S` stop.

## Before flashing

1. Install/select the ESP32 Arduino core and choose your exact ESP32-C3 board.
2. Check the GPIO pin assignments at the top of the sketch against your actual wiring. The included pins are examples, not a guarantee for every SuperMini clone.
3. Connect the TB6612FNG logic supply (VCC) to the correct 3.3 V logic supply and share GND between ESP32, driver, and motor supply.
4. Power the motors from an appropriate separate motor supply. Do not power motors from the ESP32 3.3 V pin.
5. Keep the robot's wheels off the floor for the first test. Confirm motor direction and stop behavior before putting it down.

## Safety behavior

The firmware stops the motors on BLE disconnect and if no movement command arrives for 500 ms. The Android UI sends single commands, so this timeout intentionally stops a movement shortly after a tap. Continuous drive/press-and-hold behavior should only be added after testing and implementing a deliberate command-refresh strategy.

The speed slider currently changes the UI preview only; it does not transmit speed commands. The firmware uses the fixed `MOTOR_SPEED` constant.

This firmware has been added as source but has not yet been compiled or tested on physical hardware. Review pin assignments and test with wheels lifted.
