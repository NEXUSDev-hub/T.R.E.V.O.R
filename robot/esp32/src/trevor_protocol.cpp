#include "../include/trevor_protocol.h"

#include <string.h>
#include <stdio.h>

namespace trevor {

static Command invalidCommand() {
    return {CommandType::INVALID, 0};
}

Command parseCommand(const char* frame) {
    if (frame == nullptr) return invalidCommand();

    char verb[20] = {};
    unsigned int speed = 0;

    const int fields = sscanf(frame, "%19s %u", verb, &speed);
    if (fields < 1) return invalidCommand();

    CommandType type = CommandType::INVALID;

    if (strcmp(verb, "FORWARD") == 0) type = CommandType::FORWARD;
    else if (strcmp(verb, "BACKWARD") == 0) type = CommandType::BACKWARD;
    else if (strcmp(verb, "LEFT") == 0) type = CommandType::LEFT;
    else if (strcmp(verb, "RIGHT") == 0) type = CommandType::RIGHT;
    else if (strcmp(verb, "STOP") == 0) type = CommandType::STOP;

    if (type == CommandType::INVALID) return invalidCommand();
    if (fields == 1) speed = 100;
    if (speed > MAX_SPEED) return invalidCommand();

    return {type, static_cast<uint8_t>(speed)};
}

const char* responseName(ResponseType type) {
    switch (type) {
        case ResponseType::HELLO: return "HELLO";
        case ResponseType::READY: return "READY";
        case ResponseType::ACK: return "ACK";
        case ResponseType::STATUS: return "STATUS";
        case ResponseType::ERROR: return "ERROR";
    }
    return "ERROR";
}

const char* commandName(CommandType type) {
    switch (type) {
        case CommandType::FORWARD: return "FORWARD";
        case CommandType::BACKWARD: return "BACKWARD";
        case CommandType::LEFT: return "LEFT";
        case CommandType::RIGHT: return "RIGHT";
        case CommandType::STOP: return "STOP";
        default: return "INVALID";
    }
}

} // namespace trevor
