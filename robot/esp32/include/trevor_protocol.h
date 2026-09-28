#pragma once

#include <stdint.h>

namespace trevor {

enum class CommandType : uint8_t {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    STOP,
    INVALID
};

struct Command {
    CommandType type;
    uint8_t speed;
};

enum class ResponseType : uint8_t {
    HELLO,
    READY,
    ACK,
    STATUS,
    ERROR
};

struct Response {
    ResponseType type;
    CommandType command;
    uint8_t speed;
};

constexpr uint8_t MIN_SPEED = 0;
constexpr uint8_t MAX_SPEED = 100;

// BLE application protocol v1.
// Service and characteristic UUIDs are fixed here so Android and ESP32 can share
// the same transport contract without depending on Bluetooth MAC addresses.
constexpr const char* PROTOCOL_VERSION = "1";
constexpr const char* ROBOT_SERVICE_UUID = "6f726576-6f72-4d31-9f52-545245564f52";
constexpr const char* ROBOT_RX_UUID      = "6f726576-6f72-5258-9f52-545245564f52";
constexpr const char* ROBOT_TX_UUID      = "6f726576-6f72-5458-9f52-545245564f52";

// Maximum application frame, excluding the terminating newline.
constexpr uint8_t MAX_FRAME_LENGTH = 64;

Command parseCommand(const char* frame);
const char* responseName(ResponseType type);
const char* commandName(CommandType type);

// Encodes a response as one newline-delimited ASCII frame.
// Returns false if the output buffer is too small.
bool encodeResponse(const Response& response, char* output, uint8_t outputSize);

} // namespace trevor
