#include "../include/trevor_motor_driver.h"

namespace trevor {

void MotorDriver::begin() {
    // GPIO/PWM setup is intentionally deferred until the exact driver board is selected.
}

void MotorDriver::stop() {
    // M1 hardware-neutral safe state.
}

void MotorDriver::drive(CommandType, uint8_t) {
    // M1 hardware-neutral placeholder.
}

} // namespace trevor
