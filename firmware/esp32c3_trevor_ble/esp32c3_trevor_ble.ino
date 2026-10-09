#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// T.R.E.V.O.R. BLE robot firmware for ESP32-C3 + TB6612FNG.
// BLE protocol matches TrevorRobotBleController.kt (Nordic-UART UUIDs).
// IMPORTANT: verify these GPIOs against your exact board and wiring before power-up.
static constexpr int AIN1 = 2;
static constexpr int AIN2 = 3;
static constexpr int PWMA = 4;
static constexpr int BIN1 = 5;
static constexpr int BIN2 = 6;
static constexpr int PWMB = 7;
static constexpr int STBY = 10;

static constexpr int MOTOR_SPEED = 170; // 0..255
static constexpr uint32_t COMMAND_TIMEOUT_MS = 500;

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* RX_UUID      = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char* TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

BLECharacteristic* txCharacteristic = nullptr;
volatile bool clientConnected = false;
volatile bool moving = false;
uint32_t lastCommandAt = 0;

void stopMotors() {
  digitalWrite(AIN1, LOW);
  digitalWrite(AIN2, LOW);
  digitalWrite(BIN1, LOW);
  digitalWrite(BIN2, LOW);
  analogWrite(PWMA, 0);
  analogWrite(PWMB, 0);
  digitalWrite(STBY, LOW);
  moving = false;
}

void drive(bool leftForward, bool rightForward) {
  digitalWrite(STBY, HIGH);
  digitalWrite(AIN1, leftForward ? HIGH : LOW);
  digitalWrite(AIN2, leftForward ? LOW : HIGH);
  digitalWrite(BIN1, rightForward ? HIGH : LOW);
  digitalWrite(BIN2, rightForward ? LOW : HIGH);
  analogWrite(PWMA, MOTOR_SPEED);
  analogWrite(PWMB, MOTOR_SPEED);
  moving = true;
  lastCommandAt = millis();
}

void executeCommand(char command) {
  switch (command) {
    case 'F': drive(true, true);  break;
    case 'B': drive(false, false); break;
    case 'L': drive(false, true); break;
    case 'R': drive(true, false); break;
    case 'S':
    default:  stopMotors(); break;
  }
  lastCommandAt = millis();
  if (txCharacteristic != nullptr) {
    const char* label = (command == 'F' || command == 'B' || command == 'L' || command == 'R')
        ? "ACK:MOVE" : "ACK:STOP";
    txCharacteristic->setValue(label);
    if (clientConnected) txCharacteristic->notify();
  }
}

class TrevorServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    clientConnected = true;
  }

  void onDisconnect(BLEServer*) override {
    clientConnected = false;
    stopMotors(); // Always stop if the phone disconnects.
    BLEDevice::startAdvertising();
  }
};

class TrevorCommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    std::string value = characteristic->getValue();
    if (value.empty()) return;
    // The Android app sends a single command followed by a newline.
    for (char c : value) {
      if (c == 'F' || c == 'B' || c == 'L' || c == 'R' || c == 'S') {
        executeCommand(c);
        break;
      }
    }
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(AIN1, OUTPUT);
  pinMode(AIN2, OUTPUT);
  pinMode(PWMA, OUTPUT);
  pinMode(BIN1, OUTPUT);
  pinMode(BIN2, OUTPUT);
  pinMode(PWMB, OUTPUT);
  pinMode(STBY, OUTPUT);
  stopMotors();

  BLEDevice::init("TREVOR-ROBOT");
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new TrevorServerCallbacks());

  BLEService* service = server->createService(SERVICE_UUID);
  txCharacteristic = service->createCharacteristic(
      TX_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  txCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic* rxCharacteristic = service->createCharacteristic(
      RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rxCharacteristic->setCallbacks(new TrevorCommandCallbacks());

  service->start();
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("T.R.E.V.O.R. robot BLE ready. Motors remain stopped until commanded.");
}

void loop() {
  // Independent firmware failsafe: the app must not be the only safety layer.
  if (moving && (!clientConnected || millis() - lastCommandAt > COMMAND_TIMEOUT_MS)) {
    stopMotors();
  }
  delay(10);
}
