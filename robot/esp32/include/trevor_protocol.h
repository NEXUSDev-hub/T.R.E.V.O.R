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

Command parseCommand(const char* frame);
const char* responseName(ResponseType type);
const char* commandName(CommandType type);

} // namespace trevor
