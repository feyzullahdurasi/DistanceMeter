#include <Arduino.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// EEPROM Adresleri
#define EEPROM_SIZE 8
#define RADIUS_ADDR 0  // Tekerlek yarıçapını saklamak için EEPROM adresi

// Rotary Encoder Pinleri
#define ENCODER_PIN_A 32
#define ENCODER_PIN_B 33

// Tekerlek Parametreleri - varsayılan değer
#define DEFAULT_WHEEL_RADIUS 33.0  // mm
float WHEEL_RADIUS = DEFAULT_WHEEL_RADIUS;  // Çalışma zamanında değiştirilebilir
float WHEEL_CIRCUMFERENCE;  // Tekerlek çevresi, yarıçapa göre dinamik hesaplanacak
#define ENCODER_STEPS_PER_REVOLUTION 20  // HW-040 20 puls

// Değişkenler
volatile long encoderPos = 0;
volatile long lastEncoderPos = 0;
float totalDistance = 0.0;  // mm cinsinden
bool directionForward = true;  // Hareket yönü kontrolü için

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

// Tekerlek çevresini hesapla
void calculateWheelCircumference() {
  WHEEL_CIRCUMFERENCE = WHEEL_RADIUS * PI;
  
  // Konsola yazdır
  Serial.print("Tekerlek Yarıçapı: ");
  Serial.print(WHEEL_RADIUS);
  Serial.print(" mm | Tekerlek Çevresi: ");
  Serial.println(WHEEL_CIRCUMFERENCE);
}

// Yarıçapı EEPROM'a kaydet
void saveRadiusToEEPROM() {
  EEPROM.put(RADIUS_ADDR, WHEEL_RADIUS);
  EEPROM.commit();
  Serial.println("Tekerlek yarıçapı EEPROM'a kaydedildi: " + String(WHEEL_RADIUS) + " mm");
}

// Yarıçapı EEPROM'dan oku
void loadRadiusFromEEPROM() {
  float savedRadius;
  EEPROM.get(RADIUS_ADDR, savedRadius);
  
  // Değer geçerliyse kullan, değilse varsayılan değeri kullan
  if (savedRadius > 0 && savedRadius < 1000) {  // Makul bir aralık kontrolü
    WHEEL_RADIUS = savedRadius;
    Serial.println("Tekerlek yarıçapı EEPROM'dan yüklendi: " + String(WHEEL_RADIUS) + " mm");
  } else {
    WHEEL_RADIUS = DEFAULT_WHEEL_RADIUS;
    Serial.println("EEPROM'da geçerli yarıçap bulunamadı, varsayılan kullanılıyor: " + String(WHEEL_RADIUS) + " mm");
    saveRadiusToEEPROM();  // Varsayılan değeri kaydet
  }
  
  // Çevreyi hesapla
  calculateWheelCircumference();
}

// A pini kesme fonksiyonu - Yönü dikkate alarak çalışacak şekilde güncellendi
void IRAM_ATTR encoderISR() {
  unsigned long currentTime = millis();
  
  if (currentTime - lastDebounceTime > debounceDelay) {
    // A pini yüksek seviyede ve B pini düşük seviyede ise ileri hareket
    // A pini yüksek seviyede ve B pini yüksek seviyede ise geri hareket
    if (digitalRead(ENCODER_PIN_A) == HIGH) {
      if (digitalRead(ENCODER_PIN_B) == LOW) {
        encoderPos++;
        directionForward = true;
      } else {
        encoderPos--;
        directionForward = false;
      }
    } else {
      if (digitalRead(ENCODER_PIN_B) == HIGH) {
        encoderPos++;
        directionForward = true;
      } else {
        encoderPos--;
        directionForward = false;
      }
    }
    
    lastDebounceTime = currentTime;
  }
}

void setup() {
  // Seri port başlatma
  Serial.begin(115200);
  
  // EEPROM başlatma
  EEPROM.begin(EEPROM_SIZE);
  
  // Yarıçapı EEPROM'dan yükle
  loadRadiusFromEEPROM();
  
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
}

void loop() {
  // Pozisyon değişti mi kontrol et
  if (encoderPos != lastEncoderPos) {
    // Kaç adım hareket ettiğini hesapla
    long steps = encoderPos - lastEncoderPos;
    
    // Mesafeyi hesapla - yön bilgisini kullanarak
    float distance = (float)steps * WHEEL_CIRCUMFERENCE / ENCODER_STEPS_PER_REVOLUTION;
    
    // Toplam mesafeyi güncelle - yön bilgisini kullanarak (- değer olabilir)
    totalDistance += distance;  // Yön bilgisini koruyoruz, mutlak değer almıyoruz
    
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
    // SET_RADIUS komutu - tekerlek yarıçapını değiştirme (örn: SET_RADIUS:45.5)
    else if (incomingCommand.startsWith("SET_RADIUS:")) {
      String radiusStr = incomingCommand.substring(11); // "SET_RADIUS:" kısmını kaldır
      float newRadius = radiusStr.toFloat();
      
      // Geçerli bir yarıçap değeri mi kontrol et
      if (newRadius > 0) {
        WHEEL_RADIUS = newRadius;
        calculateWheelCircumference();
        saveRadiusToEEPROM();
        
        // Onay gönder
        SerialBT.println("RADIUS_OK:" + String(WHEEL_RADIUS, 2));
        Serial.println("Yeni tekerlek yarıçapı ayarlandı: " + String(WHEEL_RADIUS) + " mm");
      } else {
        // Hata mesajı gönder
        SerialBT.println("RADIUS_ERROR:Geçersiz değer");
        Serial.println("Geçersiz yarıçap değeri: " + radiusStr);
      }
    }
    // GET_RADIUS komutu - mevcut tekerlek yarıçapını gönder
    else if (incomingCommand == "GET_RADIUS") {
      SerialBT.println("RADIUS:" + String(WHEEL_RADIUS, 2));
      Serial.println("Mevcut yarıçap değeri gönderildi: " + String(WHEEL_RADIUS) + " mm");
    }
    // ADD_RADIUS komutu - mevcut ölçüme tekerlek yarıçapını ekle
    else if (incomingCommand == "ADD_RADIUS") {
      // Mesafeye tekerlek yarıçapı kadar ekleme yap
      totalDistance += WHEEL_RADIUS;
      Serial.println("Mesafeye tekerlek yarıçapı eklendi: " + String(WHEEL_RADIUS) + " mm");
      
      // Onay gönder
      SerialBT.println("RADIUS_ADDED:" + String(WHEEL_RADIUS, 2));
    }
    
    // Komutu temizle
    incomingCommand = "";
    commandComplete = false;
  }
  
  // Kısa gecikme
  delay(10);
}