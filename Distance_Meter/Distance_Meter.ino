#include <Arduino.h>
#include <BluetoothSerial.h>

// Rotary Encoder Pinleri
#define ENCODER_PIN_A 32
#define ENCODER_PIN_B 33

// Tekerlek Parametreleri
#define WHEEL_RADIUS 33.0  // mm
#define WHEEL_CIRCUMFERENCE (2.0 * WHEEL_RADIUS * PI)  // mm
#define ENCODER_STEPS_PER_REVOLUTION 20  // HW-040 genellikle 20 pulsa sahiptir

// Değişkenler
volatile long encoderPos = 0;
volatile long lastEncoderPos = 0;
float totalDistance = 0.0;  // mm cinsinden

// Debounce için değişkenler
volatile unsigned long lastDebounceTime = 0;
unsigned long debounceDelay = 1;  // ms

// Bluetooth
BluetoothSerial SerialBT;
unsigned long lastSendTime = 0;
const int sendInterval = 500;  // Her 500ms'de bir veri gönder

// Gelen komut buffer'ı
String incomingCommand = "";
bool commandComplete = false;

// A pini kesme fonksiyonu
void IRAM_ATTR encoderISR() {
  unsigned long currentTime = millis();
  
  if (currentTime - lastDebounceTime > debounceDelay) {
    // A pini yüksek seviyede ve B pini düşük seviyede ise ileri hareket
    // A pini yüksek seviyede ve B pini yüksek seviyede ise geri hareket
    if (digitalRead(ENCODER_PIN_A) == HIGH) {
      if (digitalRead(ENCODER_PIN_B) == LOW) {
        encoderPos++;
      } else {
        encoderPos--;
      }
    } else {
      if (digitalRead(ENCODER_PIN_B) == HIGH) {
        encoderPos++;
      } else {
        encoderPos--;
      }
    }
    
    lastDebounceTime = currentTime;
  }
}

void setup() {
  // Seri port başlatma
  Serial.begin(115200);
  
  // Bluetooth başlatma
  SerialBT.begin("ESP32MesafeOlcer");
  Serial.println("Bluetooth cihazi aktif, 'ESP32MesafeOlcer' adı ile eşleşebilirsiniz");
  
  // Encoder pinlerini ayarla
  pinMode(ENCODER_PIN_A, INPUT_PULLUP);
  pinMode(ENCODER_PIN_B, INPUT_PULLUP);
  
  // Kesme (Interrupt) ayarları
  attachInterrupt(digitalPinToInterrupt(ENCODER_PIN_A), encoderISR, CHANGE);
  
  // Hoş geldiniz mesajı
  Serial.println("Tekerlek Mesafe Ölçer - ESP32");
  Serial.println("--------------------------------");
  Serial.println("Tekerlek Yarıçapı: " + String(WHEEL_RADIUS) + " mm");
  Serial.println("Tekerlek Çevresi: " + String(WHEEL_CIRCUMFERENCE) + " mm");
}

void loop() {
  // Pozisyon değişti mi kontrol et
  if (encoderPos != lastEncoderPos) {
    // Kaç adım hareket ettiğini hesapla
    long steps = encoderPos - lastEncoderPos;
    
    // Mesafeyi hesapla
    float distance = (float)steps * WHEEL_CIRCUMFERENCE / ENCODER_STEPS_PER_REVOLUTION;
    
    // Toplam mesafeyi güncelle
    totalDistance += abs(distance);  // Mutlak değer alarak toplam yolu hesapla
    
    // Son pozisyonu güncelle
    lastEncoderPos = encoderPos;
    
    // Seri porta mesafe bilgisini yaz
    Serial.print("Anlık Adım: ");
    Serial.print(steps);
    Serial.print(" | Toplam Adım: ");
    Serial.print(encoderPos);
    Serial.print(" | Toplam Mesafe (mm): ");
    Serial.print(totalDistance);
    Serial.print(" | Toplam Mesafe (m): ");
    Serial.println(totalDistance / 1000.0);
  }
  
  // Belirli aralıklarla Bluetooth üzerinden veri gönder
  unsigned long currentMillis = millis();
  if (currentMillis - lastSendTime >= sendInterval) {
    lastSendTime = currentMillis;
    
    // Mesafe bilgisini gönder (metre cinsinden)
    float distanceInMeters = totalDistance / 1000.0;
    SerialBT.println(String(distanceInMeters, 3));  // 3 ondalık basamak hassasiyetle gönder
  }
  
  // Bluetooth üzerinden gelen komutları kontrol et
  while (SerialBT.available()) {
    char inChar = (char)SerialBT.read();
    
    // Yeni satır veya satır sonu karakteri komutu tamamlar
    if (inChar == '\n' || inChar == '\r') {
      if (incomingCommand.length() > 0) {
        commandComplete = true;
      }
    } else {
      incomingCommand += inChar;
    }
  }
  
  // Gelen komutu işle
  if (commandComplete) {
    Serial.print("Gelen komut: ");
    Serial.println(incomingCommand);
    
    // RESET komutu - mesafe sıfırlama
    if (incomingCommand == "RESET") {
      encoderPos = 0;
      lastEncoderPos = 0;
      totalDistance = 0.0;
      Serial.println("Mesafe sıfırlandı!");
      
      // Sıfırlama onayı gönder
      SerialBT.println("RESET_OK");
    }
    
    // Komutu temizle
    incomingCommand = "";
    commandComplete = false;
  }
  
  // Kısa gecikme
  delay(10);
}