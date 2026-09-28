#include <Arduino.h>
#include "../include/trevor_protocol.h"
#include "../include/trevor_motor_driver.h"
#include "../include/trevor_speaker.h"
#include "../include/trevor_safety.h"

using namespace trevor;

MotorDriver motors;
Speaker speaker;
SafetyController safety;

static void handleCommand(const Command& command, uint32_t nowMs) {
    if (command.type == CommandType::INVALID) {
        safety.forceStop();
        motors.stop();
        speaker.errorTone();
        return;
    }

    if (command.speed > MAX_SPEED) {
        safety.forceStop();
        motors.stop();
        speaker.errorTone();
        return;
    }

    safety.heartbeat(nowMs);

    if (command.type == CommandType::STOP) {
        safety.forceStop();
        motors.stop();
        return;
    }

    if (!safety.motionAllowed(nowMs)) {
        motors.stop();
        return;
    }

    motors.drive(command.type, command.speed);
}

void setup() {
    Serial.begin(115200);

    motors.begin();
    speaker.begin();
    safety.begin();

    // M1 boots into a safe stopped state.
    motors.stop();

    Serial.println("TREVOR-RBT-M1");
    Serial.println("HELLO");
    Serial.println("READY");

    speaker.readyTone();
}

void loop() {
    const uint32_t nowMs = millis();

    if (!safety.motionAllowed(nowMs)) {
        motors.stop();
    }

    if (Serial.available() > 0) {
        static char frame[64];
        const size_t count = Serial.readBytesUntil('\n', frame, sizeof(frame) - 1);
        frame[count] = '\0';

        const Command command = parseCommand(frame);
        handleCommand(command, nowMs);
    }
}
