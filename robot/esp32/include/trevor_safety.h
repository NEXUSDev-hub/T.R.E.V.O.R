#pragma once

#include <stdint.h>

namespace trevor {

class SafetyController {
public:
    static constexpr uint32_t DEFAULT_TIMEOUT_MS = 750;

    void begin();
    void heartbeat(uint32_t nowMs);
    bool motionAllowed(uint32_t nowMs) const;
    void forceStop();

private:
    uint32_t lastHeartbeatMs_ = 0;
    bool forcedStop_ = true;
};

} // namespace trevor
