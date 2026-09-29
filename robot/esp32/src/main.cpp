#include <Arduino.h>
#include "../include/trevor_protocol.h"
#include "../include/trevor_motor_driver.h"
#include "../include/trevor_speaker.h"
#include "../include/trevor_safety.h"
#include "../include/trevor_ble_transport.h"

using namespace trevor;

MotorDriver motors;
Speaker speaker;
SafetyController safety;
BleTransport ble;

static void sendError() {
    ble.sendResponse({ResponseType::ERROR, CommandType::INVALID, 0});
}

static void handleFrame(const char* frame, uint32_t nowMs) {
    // Heartbeat never grants movement authority.
    if (strcmp(frame, "HB") == 0) {
        if (!ble.authenticated()) {
            sendError();
            return;
        }

        safety.heartbeat(nowMs);
        ble.sendResponse({ResponseType::ACK, CommandType::STOP, 0});
        return;
    }

    const Command command = parseCommand(frame);

    if (command.type == CommandType::INVALID) {
        safety.forceStop();
        motors.stop();
        sendError();
        return;
    }

    // M1 has no authentication handshake yet. Do not let BLE or USB
    // accidentally become an unauthenticated motor-control interface.
    if (!ble.authenticated()) {
        safety.forceStop();
        motors.stop();
        sendError();
        return;
    }

    safety.heartbeat(nowMs);

    if (command.type == CommandType::STOP) {
        safety.forceStop();
        motors.stop();
        ble.sendResponse({ResponseType::ACK, CommandType::STOP, 0});
        return;
    }

    if (!safety.motionAllowed(nowMs)) {
        motors.stop();
        sendError();
        return;
    }

    motors.drive(command.type, command.speed);
    ble.sendResponse({ResponseType::ACK, command.type, command.speed});
}

static void handleBleFrame(const char* frame, uint32_t nowMs) {
    handleFrame(frame, nowMs);
}

void setup() {
    Serial.begin(115200);

    motors.begin();
    speaker.begin();
    safety.begin();

    // Boot/reset is always stopped and unauthenticated.
    motors.stop();

    ble.begin("TREVOR-RBT-M1", handleBleFrame);

    Serial.println("TREVOR-RBT-M1");
    Serial.println("HELLO TREVOR-RBT-M1 1");
    Serial.println("READY");

    speaker.readyTone();
    safety.forceStop();
}

void loop() {
    const uint32_t nowMs = millis();

    ble.loop(nowMs);

    if (!ble.connected() ||
        !ble.authenticated() ||
        !safety.motionAllowed(nowMs)) {
        motors.stop();
    }

    // USB serial remains a diagnostic sink in M1. It cannot bypass the
    // authentication gate or authorize movement.
    if (Serial.available() > 0) {
        static char frame[MAX_FRAME_LENGTH + 1];
        const size_t count =
            Serial.readBytesUntil('\n', frame, MAX_FRAME_LENGTH);
        frame[count] = '\0';

        (void)frame;
        safety.forceStop();
        motors.stop();
    }
}
