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
    char extra = 0;

    const int fields = sscanf(frame, "%19s %u %c", verb, &speed, &extra);
    if (fields < 1 || fields > 2) return invalidCommand();

    CommandType type = CommandType::INVALID;

    if (strcmp(verb, "FORWARD") == 0) type = CommandType::FORWARD;
    else if (strcmp(verb, "BACKWARD") == 0) type = CommandType::BACKWARD;
    else if (strcmp(verb, "LEFT") == 0) type = CommandType::LEFT;
    else if (strcmp(verb, "RIGHT") == 0) type = CommandType::RIGHT;
    else if (strcmp(verb, "STOP") == 0) type = CommandType::STOP;

    if (type == CommandType::INVALID) return invalidCommand();
    if (fields == 1) speed = 100;
    if (speed > MAX_SPEED) return invalidCommand();
    if (type == CommandType::STOP && speed != 0) return invalidCommand();

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

bool encodeResponse(const Response& response, char* output, uint8_t outputSize) {
    if (output == nullptr || outputSize == 0) return false;

    int written = 0;

    switch (response.type) {
        case ResponseType::HELLO:
            written = snprintf(
                output, outputSize,
                "HELLO %s %s\n",
                "TREVOR-RBT-M1",
                PROTOCOL_VERSION
            );
            break;

        case ResponseType::READY:
            written = snprintf(output, outputSize, "READY\n");
            break;

        case ResponseType::ACK:
            written = snprintf(
                output, outputSize,
                "ACK %s %u\n",
                commandName(response.command),
                response.speed
            );
            break;

        case ResponseType::STATUS:
            written = snprintf(
                output, outputSize,
                "STATUS %s %u\n",
                commandName(response.command),
                response.speed
            );
            break;

        case ResponseType::ERROR:
            written = snprintf(output, outputSize, "ERROR\n");
            break;
    }

    return written > 0 && written < outputSize;
}

bool encodeHello(const char* robotId, char* output, uint8_t outputSize) {
    if (robotId == nullptr || output == nullptr || outputSize == 0) return false;
    const int written = snprintf(output, outputSize, "HELLO %s %s\\n", robotId, PROTOCOL_VERSION);
    return written > 0 && written < outputSize;
}

} // namespace trevor

