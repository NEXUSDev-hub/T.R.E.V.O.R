#pragma once

#include <stdint.h>
#include "trevor_protocol.h"

namespace trevor {

class MotorDriver {
public:
    void begin();
    void stop();
    void drive(CommandType command, uint8_t speed);
};

} // namespace trevor
