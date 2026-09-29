#pragma once

#include <stddef.h>
#include <stdint.h>

#include "trevor_protocol.h"

namespace trevor {

class BleTransport {
public:
    using FrameHandler = void (*)(const char* frame, uint32_t nowMs);

    void begin(const char* robotId, FrameHandler handler);
    void loop(uint32_t nowMs);

    bool connected() const;
    bool authenticated() const;
    void setAuthenticated(bool authenticated);

    bool sendResponse(const Response& response);

private:
    class ServerCallbacks;
    class RxCallbacks;

    FrameHandler handler_ = nullptr;
    volatile bool connected_ = false;
    volatile bool authenticated_ = false;

    char pendingFrame_[MAX_FRAME_LENGTH + 1] = {};
    volatile bool framePending_ = false;

    void handleConnectionState(bool connected);
    void handleRx(const uint8_t* data, size_t length);

    friend class ServerCallbacks;
    friend class RxCallbacks;
};

} // namespace trevor
