// ESP32 蓝牙小车固件（Arduino IDE）
// 支持单字母控制和自然语言命令：前进2米、跑3秒、左转1.5秒
// 换算：1 秒约等于 0.75 米
#include <BluetoothSerial.h>
#include "esp_task_wdt.h"

BluetoothSerial SerialBT;

#define IN1 25
#define IN2 26
#define IN3 27
#define IN4 14
#define ENA 16
#define ENB 17

const float METERS_PER_SECOND = 0.75f;
const unsigned long HEARTBEAT_MS = 20;
const int PWM_LEFT_FORWARD = 250;
const int PWM_RIGHT_FORWARD = 200;
const int PWM_LEFT_BACKWARD = 250;
const int PWM_RIGHT_BACKWARD = 10;
const int PWM_LEFT_TURN = 250;
const int PWM_RIGHT_TURN = 200;

volatile char currentCmd = 'S';
int pwmChL, pwmChR;
String rxBuffer;

struct MotionPlan {
  char cmd;
  bool timed;
  unsigned long durationMs;
};

void moveForward() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, PWM_LEFT_FORWARD);
  ledcWrite(pwmChR, PWM_RIGHT_FORWARD);
}

void moveBackward() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH);
  ledcWrite(pwmChL, PWM_LEFT_BACKWARD);
  ledcWrite(pwmChR, PWM_RIGHT_BACKWARD);
}

void turnLeft() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, PWM_LEFT_TURN);
  ledcWrite(pwmChR, PWM_RIGHT_TURN);
}

void turnRight() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH);
  ledcWrite(pwmChL, PWM_LEFT_TURN);
  ledcWrite(pwmChR, PWM_RIGHT_TURN);
}

void stopCar() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW); digitalWrite(IN4, LOW);
  ledcWrite(pwmChL, 0);
  ledcWrite(pwmChR, 0);
}

void runMotion(char cmd) {
  switch (cmd) {
    case 'F': moveForward(); break;
    case 'B': moveBackward(); break;
    case 'L': turnLeft(); break;
    case 'R': turnRight(); break;
    default: stopCar(); break;
  }
}

unsigned long secondsToMs(float seconds) {
  if (seconds <= 0.0f) return 0;
  return (unsigned long)(seconds * 1000.0f + 0.5f);
}

unsigned long metersToMs(float meters) {
  if (meters <= 0.0f) return 0;
  return secondsToMs(meters / METERS_PER_SECOND);
}

float parseNumberBefore(const String &s, int endIndex) {
  int start = endIndex - 1;
  while (start >= 0) {
    char c = s[start];
    if ((c >= '0' && c <= '9') || c == '.' || c == '-') start--;
    else break;
  }
  start++;
  if (start >= endIndex) return -1.0f;
  String number = s.substring(start, endIndex);
  return number.toFloat();
}

float findUnitValue(const String &s, const String &unit) {
  int index = s.indexOf(unit);
  if (index < 0) return -1.0f;
  return parseNumberBefore(s, index);
}

char inferCommand(const String &s) {
  if (s.indexOf("停止") >= 0 || s.indexOf("停下") >= 0 || s.indexOf("stop") >= 0) return 'S';
  if (s.indexOf("左转") >= 0 || s.indexOf("向左") >= 0 || s.indexOf("left") >= 0) return 'L';
  if (s.indexOf("右转") >= 0 || s.indexOf("向右") >= 0 || s.indexOf("right") >= 0) return 'R';
  if (s.indexOf("后退") >= 0 || s.indexOf("向后") >= 0 || s.indexOf("back") >= 0) return 'B';
  if (s.indexOf("前进") >= 0 || s.indexOf("向前") >= 0 || s.indexOf("跑") >= 0 || s.indexOf("forward") >= 0) return 'F';
  return 0;
}

MotionPlan parseCommand(String raw) {
  MotionPlan plan = {'S', false, 0};
  String s = raw;
  s.trim();
  s.toLowerCase();
  s.replace(" ", "");

  if (s.length() == 1) {
    char c = toupper(s[0]);
    if (strchr("FBRLS", c)) {
      plan.cmd = c;
      return plan;
    }
  }

  plan.cmd = inferCommand(s);
  if (plan.cmd == 0) return plan;

  float seconds = findUnitValue(s, "秒");
  if (seconds < 0.0f) seconds = findUnitValue(s, "sec");
  if (seconds >= 0.0f) {
    plan.timed = true;
    plan.durationMs = secondsToMs(seconds);
    return plan;
  }

  float meters = findUnitValue(s, "米");
  if (meters < 0.0f) meters = findUnitValue(s, "m");
  if (meters >= 0.0f) {
    plan.timed = true;
    plan.durationMs = metersToMs(meters);
    return plan;
  }

  return plan;
}

unsigned long timedEndMs = 0;

void startTimedMove(char cmd, unsigned long durationMs) {
  if (durationMs == 0) {
    currentCmd = 'S';
    timedEndMs = 0;
    stopCar();
    return;
  }
  currentCmd = cmd;
  timedEndMs = millis() + durationMs;
  runMotion(cmd);
}

void updateTimedMove() {
  if (timedEndMs == 0) return;
  if ((long)(millis() - timedEndMs) >= 0) {
    timedEndMs = 0;
    currentCmd = 'S';
    stopCar();
  } else {
    runMotion(currentCmd);
  }
}

void handleCommand(String raw) {
  MotionPlan plan = parseCommand(raw);
  if (plan.cmd == 0) return;
  if (plan.cmd == 'S') {
    timedEndMs = 0;
    currentCmd = 'S';
    stopCar();
    return;
  }
  if (plan.timed) startTimedMove(plan.cmd, plan.durationMs);
  else {
    timedEndMs = 0;
    currentCmd = plan.cmd;
    runMotion(plan.cmd);
  }
}

void setup() {
  pwmChL = ledcAttach(ENA, 5000, 8);
  pwmChR = ledcAttach(ENB, 5000, 8);

  Serial.begin(115200);
  esp_task_wdt_deinit();
  setCpuFrequencyMhz(80);
  SerialBT.begin("ESP32_Car");
  Serial.println("Bluetooth ready");

  pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);
  stopCar();

  delay(1000);
  moveForward();
  delay(2000);
  stopCar();
}

void loop() {
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\r' || c == '\n') {
      if (rxBuffer.length() > 0) {
        handleCommand(rxBuffer);
        rxBuffer = "";
      }
    } else {
      rxBuffer += c;
      if (rxBuffer.length() > 80) {
        handleCommand(rxBuffer);
        rxBuffer = "";
      }
    }
  }

  if (rxBuffer.length() == 1) {
    char c = toupper(rxBuffer[0]);
    if (strchr("FBRLS", c)) {
      timedEndMs = 0;
      currentCmd = c;
      runMotion(c);
      rxBuffer = "";
      return;
    }
  }

  updateTimedMove();
  if (timedEndMs != 0) return;

  switch (currentCmd) {
    case 'F': moveForward(); break;
    case 'B': moveBackward(); break;
    case 'L': turnLeft(); break;
    case 'R': turnRight(); break;
    default: stopCar(); break;
  }
}
