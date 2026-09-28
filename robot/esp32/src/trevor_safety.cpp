#include "../include/trevor_safety.h"

namespace trevor {

void SafetyController::begin() {
    lastHeartbeatMs_ = millis();
    forcedStop_ = true;
}

void SafetyController::heartbeat(uint32_t nowMs) {
    lastHeartbeatMs_ = nowMs;
    forcedStop_ = false;
}

bool SafetyController::motionAllowed(uint32_t nowMs) const {
    return !forcedStop_ && (nowMs - lastHeartbeatMs_) <= DEFAULT_TIMEOUT_MS;
}

void SafetyController::forceStop() {
    forcedStop_ = true;
}

} // namespace trevor
