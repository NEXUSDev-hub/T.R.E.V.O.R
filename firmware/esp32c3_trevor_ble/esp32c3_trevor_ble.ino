#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <cstdlib>

// T.R.E.V.O.R. BLE robot firmware for ESP32-C3 + TB6612FNG.
// GPIO values are examples: verify your exact board and wiring before power-up.
static constexpr int AIN1 = 2;
static constexpr int AIN2 = 3;
static constexpr int PWMA = 4;
static constexpr int BIN1 = 5;
static constexpr int BIN2 = 6;
static constexpr int PWMB = 7;
static constexpr int STBY = 10;

static constexpr int MAX_SPEED = 255;
static constexpr uint32_t COMMAND_TIMEOUT_MS = 500;
static constexpr uint32_t STATUS_INTERVAL_MS = 1000;

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* RX_UUID      = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char* TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

BLECharacteristic* txCharacteristic = nullptr;
volatile bool clientConnected = false;
volatile bool moving = false;
int currentLeft = 0;
int currentRight = 0;
uint32_t lastCommandAt = 0;
uint32_t lastStatusAt = 0;

void stopMotors() {
  digitalWrite(AIN1, LOW);
  digitalWrite(AIN2, LOW);
  digitalWrite(BIN1, LOW);
  digitalWrite(BIN2, LOW);
  analogWrite(PWMA, 0);
  analogWrite(PWMB, 0);
  digitalWrite(STBY, LOW);
  currentLeft = 0;
  currentRight = 0;
  moving = false;
}

void setMotor(int speed, int in1, int in2, int pwm) {
  speed = constrain(speed, -MAX_SPEED, MAX_SPEED);
  digitalWrite(in1, speed > 0 ? HIGH : LOW);
  digitalWrite(in2, speed < 0 ? HIGH : LOW);
  analogWrite(pwm, abs(speed));
}

void driveMotors(int left, int right) {
  currentLeft = constrain(left, -MAX_SPEED, MAX_SPEED);
  currentRight = constrain(right, -MAX_SPEED, MAX_SPEED);
  if (currentLeft == 0 && currentRight == 0) {
    stopMotors();
    return;
  }
  digitalWrite(STBY, HIGH);
  setMotor(currentLeft, AIN1, AIN2, PWMA);
  setMotor(currentRight, BIN1, BIN2, PWMB);
  moving = true;
  lastCommandAt = millis();
}

void notifyText(const String& text) {
  if (txCharacteristic == nullptr || !clientConnected) return;
  txCharacteristic->setValue(text.c_str());
  txCharacteristic->notify();
}

void sendAck(long sequence, bool ok) {
  notifyText(String("ACK,") + sequence + (ok ? ",OK\n" : ",ERR\n"));
}

bool parseInteger(const String& text, int& value) {
  if (text.length() == 0 || text.length() > 6) return false;
  char* end = nullptr;
  long parsed = strtol(text.c_str(), &end, 10);
  if (end == text.c_str() || *end != '\0' || parsed < -MAX_SPEED || parsed > MAX_SPEED) return false;
  value = static_cast<int>(parsed);
  return true;
}

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0 || line.length() > 48) return;

  // Emergency stop is deliberately simple and accepted even if the sequence is malformed.
  if (line.startsWith("S")) {
    stopMotors();
    lastCommandAt = millis();
    int comma = line.indexOf(',');
    long seq = comma >= 0 ? line.substring(comma + 1).toInt() : -1;
    sendAck(seq, true);
    return;
  }

  // D,<left>,<right>,<sequence>
  if (!line.startsWith("D,")) {
    sendAck(-1, false);
    return;
  }
  int first = line.indexOf(',', 2);
  int second = first < 0 ? -1 : line.indexOf(',', first + 1);
  if (first < 0 || second < 0) {
    sendAck(-1, false);
    return;
  }
  int left = 0;
  int right = 0;
  String leftText = line.substring(2, first);
  String rightText = line.substring(first + 1, second);
  String seqText = line.substring(second + 1);
  if (!parseInteger(leftText, left) || !parseInteger(rightText, right) ||
      seqText.length() == 0 || seqText.length() > 9) {
    sendAck(-1, false);
    return;
  }
  char* end = nullptr;
  long sequence = strtol(seqText.c_str(), &end, 10);
  if (end == seqText.c_str() || *end != '\0' || sequence < 0 || sequence > 999999) {
    sendAck(-1, false);
    return;
  }
  if (!clientConnected) {
    stopMotors();
    sendAck(sequence, false);
    return;
  }
  driveMotors(left, right);
  lastCommandAt = millis();
  sendAck(sequence, true);
}

class TrevorServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    clientConnected = true;
  }

  void onDisconnect(BLEServer*) override {
    clientConnected = false;
    stopMotors();
    BLEDevice::startAdvertising();
  }
};

class TrevorCommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    std::string raw = characteristic->getValue();
    if (raw.empty()) return;
    static String buffer;
    for (char c : raw) {
      if (c == '\n' || c == '\r') {
        if (buffer.length() > 0) handleCommand(buffer);
        buffer = "";
      } else if (buffer.length() < 48) {
        buffer += c;
      } else {
        buffer = "";
        stopMotors();
        notifyText("FAULT,COMMAND_TOO_LONG\n");
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
  Serial.println("T.R.E.V.O.R. BLE ready; motors stopped.");
}

void loop() {
  const uint32_t now = millis();
  if (moving && (!clientConnected || now - lastCommandAt > COMMAND_TIMEOUT_MS)) {
    stopMotors();
    notifyText("FAULT,MOVEMENT_TIMEOUT\n");
  }
  if (clientConnected && now - lastStatusAt >= STATUS_INTERVAL_MS) {
    lastStatusAt = now;
    // Battery millivolts are -1 until a voltage-divider/sensor is installed.
    notifyText(String("STAT,") + currentLeft + "," + currentRight + ",-1,0\n");
  }
  delay(10);
}
