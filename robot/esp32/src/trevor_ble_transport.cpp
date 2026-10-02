#include "../include/trevor_ble_transport.h"

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/portmacro.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include <cstring>
#include <string>

namespace trevor {

namespace {
BLECharacteristic* txCharacteristic = nullptr;
portMUX_TYPE frameMux = portMUX_INITIALIZER_UNLOCKED;
constexpr const char* DEFAULT_ROBOT_NAME = "TREVOR Robot";
}

class BleTransport::ServerCallbacks final : public BLEServerCallbacks {
public:
    explicit ServerCallbacks(BleTransport* owner) : owner_(owner) {}

    void onConnect(BLEServer*) override {
        owner_->handleConnectionState(true);
        // Defer the handshake to loop(). The phone must first have an
        // opportunity to subscribe to TX notifications.
    }

    void onDisconnect(BLEServer* server) override {
        owner_->handleConnectionState(false);
        server->startAdvertising();
    }

private:
    BleTransport* owner_;
};

class BleTransport::RxCallbacks final : public BLECharacteristicCallbacks {
public:
    explicit RxCallbacks(BleTransport* owner) : owner_(owner) {}

    void onWrite(BLECharacteristic* characteristic) override {
        const std::string value = characteristic->getValue();

        owner_->handleRx(
            reinterpret_cast<const uint8_t*>(value.data()),
            value.length()
        );
    }

private:
    BleTransport* owner_;
};

void BleTransport::begin(const char* robotId, FrameHandler handler) {
    handler_ = handler;
    strncpy(robotId_, robotId != nullptr ? robotId : DEFAULT_ROBOT_NAME, sizeof(robotId_) - 1);
    robotId_[sizeof(robotId_) - 1] = '\0';
    connected_ = false;
    authenticated_ = false;
    frameHead_ = 0;
    frameTail_ = 0;
    frameCount_ = 0;
    queueOverflow_ = false;
    handshakePending_ = false;

    BLEDevice::init(robotId != nullptr ? robotId : DEFAULT_ROBOT_NAME);

    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks(this));

    BLEService* service = server->createService(ROBOT_SERVICE_UUID);

    BLECharacteristic* rx = service->createCharacteristic(
        ROBOT_RX_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    rx->setCallbacks(new RxCallbacks(this));

    txCharacteristic = service->createCharacteristic(
        ROBOT_TX_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    // Bluedroid clients need the standard CCCD descriptor to subscribe
    // reliably to notifications.
    txCharacteristic->addDescriptor(new BLE2902());

    service->start();

    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(ROBOT_SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->start();
}

void BleTransport::loop(uint32_t nowMs) {
    if (handshakePending_ && connected_ && txCharacteristic != nullptr) {
        char hello[MAX_FRAME_LENGTH + 1] = {};
        if (encodeHello(robotId_, hello, sizeof(hello))) {
            txCharacteristic->setValue(reinterpret_cast<uint8_t*>(hello), strlen(hello));
            txCharacteristic->notify();
        }
        sendResponse({ResponseType::READY, CommandType::INVALID, 0});
        handshakePending_ = false;
    }

    char frame[MAX_FRAME_LENGTH + 1] = {};

    portENTER_CRITICAL(&frameMux);
    if (queueOverflow_) {
        queueOverflow_ = false;
        frameHead_ = 0;
        frameTail_ = 0;
        frameCount_ = 0;
        portEXIT_CRITICAL(&frameMux);
        if (handler_ != nullptr) handler_("QUEUE_OVERFLOW", nowMs);
        return;
    }
    if (frameCount_ == 0) { portEXIT_CRITICAL(&frameMux); return; }
    const uint8_t length = frameQueue_[frameHead_].length;
    memcpy(frame, frameQueue_[frameHead_].data, length + 1);
    frameHead_ = static_cast<uint8_t>((frameHead_ + 1) % FRAME_QUEUE_CAPACITY);
    --frameCount_;
    portEXIT_CRITICAL(&frameMux);

    if (handler_ != nullptr) {
        handler_(frame, nowMs);
    }
}

bool BleTransport::connected() const {
    return connected_;
}

bool BleTransport::authenticated() const {
    return authenticated_;
}

void BleTransport::setAuthenticated(bool authenticated) {
    authenticated_ = authenticated;

    if (!authenticated) {
        // The safety loop observes this false state and stops the motors.
        // Authorization is deliberately not persisted anywhere else.
    }
}

bool BleTransport::sendResponse(const Response& response) {
    if (!connected_ || txCharacteristic == nullptr) return false;

    char frame[MAX_FRAME_LENGTH + 1] = {};
    if (!encodeResponse(response, frame, sizeof(frame))) return false;

    txCharacteristic->setValue(
        reinterpret_cast<uint8_t*>(frame),
        strlen(frame)
    );
    txCharacteristic->notify();
    return true;
}

void BleTransport::handleConnectionState(bool connected) {
    connected_ = connected;

    if (connected) {
        handshakePending_ = true;
    } else {
        // Never carry authorization across a disconnect.
        authenticated_ = false;
        portENTER_CRITICAL(&frameMux);
        frameHead_ = 0;
        frameTail_ = 0;
        frameCount_ = 0;
        queueOverflow_ = false;
        portEXIT_CRITICAL(&frameMux);
        handshakePending_ = false;
    }
}

void BleTransport::handleRx(const uint8_t* data, size_t length) {
    if (data == nullptr || length == 0 || length > MAX_FRAME_LENGTH) {
        return;
    }

    size_t count = length;
    while (count > 0 &&
           (data[count - 1] == '\n' || data[count - 1] == '\r')) {
        --count;
    }

    if (count == 0 || count > MAX_FRAME_LENGTH) return;

    for (size_t i = 0; i < count; ++i) {
        const uint8_t c = data[i];

        // Printable ASCII only. No embedded control characters.
        if (c < 0x20 || c > 0x7E) return;
    }

    portENTER_CRITICAL(&frameMux);
    if (frameCount_ < FRAME_QUEUE_CAPACITY) {
        PendingFrame& slot = frameQueue_[frameTail_];
        slot.length = static_cast<uint8_t>(count);
        memcpy(slot.data, data, count);
        slot.data[count] = '\0';
        frameTail_ = static_cast<uint8_t>((frameTail_ + 1) % FRAME_QUEUE_CAPACITY);
        ++frameCount_;
    } else {
        // A saturated command queue is a safety fault: the caller will
        // receive an invalid frame and the main safety path will stop motors.
        queueOverflow_ = true;
    }
    portEXIT_CRITICAL(&frameMux);
}

} // namespace trevor
