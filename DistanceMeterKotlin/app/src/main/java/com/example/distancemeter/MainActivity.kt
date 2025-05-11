package com.example.distancemeter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.distancemeter.ui.theme.DistanceMeterTheme
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val TAG = "BluetoothApp"
    private val DEVICE_NAME = "ESP32MesafeOlcer"

    // SPP UUID (Serial Port Profile)
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null

    // Durum ve mesafe bilgilerini tutan state değişkenleri
    private val _distanceRaw = mutableStateOf(0.0)
    private val _distanceFormatted = mutableStateOf("0.0")
    private val _distanceUnit = mutableStateOf("cm")
    val distanceFormatted = _distanceFormatted
    val distanceUnit = _distanceUnit

    private val _connectionStatus = mutableStateOf("Bağlantı durumu: Bağlı değil")
    val connectionStatus = _connectionStatus

    private val _deviceList = mutableStateOf<List<BluetoothDevice>>(emptyList())
    val deviceList = _deviceList

    // Tekerlek yarıçapı (varsayılan 33mm)
    private val _wheelRadius = mutableStateOf(33.0)
    val wheelRadius = _wheelRadius

    // Bluetooth izinlerini istemek için
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            findAndConnectDevice()
        } else {
            _connectionStatus.value = "Bluetooth izinleri gerekli"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bluetooth adaptörünü al
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Bluetooth kontrolü
        if (bluetoothAdapter == null) {
            // Bluetooth desteklenmiyor
            finish()
            return
        }

        setContent {
            DistanceMeterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DistanceMeterApp(
                        modifier = Modifier.padding(innerPadding),
                        distanceFormatted = distanceFormatted.value,
                        distanceUnit = distanceUnit.value,
                        connectionStatus = connectionStatus.value,
                        deviceList = deviceList.value,
                        wheelRadius = wheelRadius.value,
                        onConnectClick = { checkBluetoothPermissions() },
                        onResetClick = { resetDistance() },
                        onAddRadiusClick = { addRadius() }, // Yarıçap Ekle butonu için callback
                        onDeviceClick = { device -> connectToDevice(device) },
                        onWheelRadiusChange = { updateWheelRadius(it) }
                    )
                }
            }
        }
    }

    private fun updateWheelRadius(radius: Double) {
        _wheelRadius.value = radius
        // Mevcut mesafeyi yeni yarıçapa göre güncelleyebiliriz
        updateDistanceDisplay(_distanceRaw.value)

        // ESP32'ye yeni yarıçapı gönder
        sendWheelRadiusToESP32(radius)
    }

    private fun sendWheelRadiusToESP32(radius: Double) {
        if (bluetoothSocket?.isConnected == true) {
            val command = "SET_RADIUS:${radius}"
            connectedThread?.write(command)
            _connectionStatus.value = "Yarıçap ESP32'ye gönderiliyor: $radius mm"
            Log.d(TAG, "ESP32'ye yarıçap gönderiliyor: $radius mm")
        } else {
            _connectionStatus.value = "ESP32'ye bağlı değil, yarıçap güncellenemedi"
            Log.d(TAG, "ESP32'ye bağlı değil, yarıçap güncellenemedi")
        }
    }

    // Yarıçap ekleme fonksiyonu
    private fun addRadius() {
        if (bluetoothSocket?.isConnected == true) {
            val command = "ADD_RADIUS"
            connectedThread?.write(command)
            _connectionStatus.value = "Mesafeye yarıçap (${_wheelRadius.value} mm) ekleniyor..."
            Log.d(TAG, "ESP32'ye yarıçap ekleme komutu gönderiliyor")
        } else {
            _connectionStatus.value = "ESP32'ye bağlı değil, yarıçap eklenemedi"
            Log.d(TAG, "ESP32'ye bağlı değil, yarıçap eklenemedi")
        }
    }

    private fun checkBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Android 12 ve üzeri için izinleri kontrol et
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothPermissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            for (permission in bluetoothPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission)
                }
            }
        } else {
            // Android 11 ve altı için izinleri kontrol et
            val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, locationPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(locationPermission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        // Bluetooth açık mı kontrol et
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivity(enableBtIntent)
            } else {
                _connectionStatus.value = "Bluetooth izinleri gerekli"
            }
            return
        }

        findAndConnectDevice()
    }

    private fun findAndConnectDevice() {
        _connectionStatus.value = "Cihazlar aranıyor..."
        _deviceList.value = emptyList()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Eşleştirilmiş cihazları listele
            val pairedDevices = bluetoothAdapter.bondedDevices

            if (pairedDevices.size > 0) {
                _deviceList.value = pairedDevices.toList()

                // ESP32 adlı cihazı otomatik bağla
                for (device in pairedDevices) {
                    if (device.name == DEVICE_NAME) {
                        connectToDevice(device)
                        return
                    }
                }
                _connectionStatus.value = "Cihaz seçin veya eşleştirin"
            } else {
                _connectionStatus.value = "Eşleştirilmiş cihaz bulunamadı"
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val deviceName = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            "Bilinmeyen Cihaz"
        } else {
            device.name ?: "Bilinmeyen Cihaz"
        }

        _connectionStatus.value = "$deviceName cihazına bağlanılıyor..."

        Thread {
            try {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    runOnUiThread {
                        _connectionStatus.value = "Bluetooth izinleri gerekli"
                    }
                    return@Thread
                }

                // Mevcut bağlantıyı kapat
                try {
                    bluetoothSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Soket kapatılırken hata: ${e.message}")
                }

                // Yeni soket oluştur
                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                bluetoothSocket?.connect()

                runOnUiThread {
                    _connectionStatus.value = "$deviceName cihazına bağlandı"
                }

                connectedThread = ConnectedThread(bluetoothSocket!!)
                connectedThread?.start()

                // Bağlantı kurulduktan sonra ESP32'den tekerlek yarıçapını iste
                requestWheelRadiusFromESP32()

            } catch (e: IOException) {
                Log.e(TAG, "Bağlantı hatası: ${e.message}")
                runOnUiThread {
                    _connectionStatus.value = "Bağlantı başarısız: ${e.message}"
                }

                try {
                    bluetoothSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Soket kapatılırken hata: ${e.message}")
                }
            }
        }.start()
    }

    private fun requestWheelRadiusFromESP32() {
        if (bluetoothSocket?.isConnected == true) {
            connectedThread?.write("GET_RADIUS")
            Log.d(TAG, "ESP32'den tekerlek yarıçapı istendi")
        }
    }

    private fun resetDistance() {
        // Mesafe değerini sıfırla - ESP32'ye reset komutu gönder
        if (bluetoothSocket?.isConnected == true) {
            connectedThread?.write("RESET")
            _connectionStatus.value = "Mesafe sıfırlanıyor..."
            Log.d(TAG, "ESP32'ye sıfırlama komutu gönderildi")
        } else {
            _connectionStatus.value = "ESP32'ye bağlı değil, sıfırlama yapılamadı"
        }

        // Yerel değerleri de sıfırla
        _distanceRaw.value = 0.0
        updateDistanceDisplay(0.0)
    }

    private fun updateDistanceDisplay(distanceInMeters: Double) {
        // Mesafe değerini cm cinsine çevir
        val distanceInCm = distanceInMeters * 100.0

        if (distanceInCm >= 100.0) {
            // 100 cm ve üzeri değerleri metre olarak göster (1.01 m)
            _distanceUnit.value = "m"
            _distanceFormatted.value = String.format("%.2f", distanceInCm / 100.0)
        } else {
            // 100 cm'den düşük değerleri cm olarak göster
            _distanceUnit.value = "cm"
            _distanceFormatted.value = String.format("%.1f", distanceInCm)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Bağlantıyı kapat
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Soket kapatılırken hata: ${e.message}")
        }
        connectedThread?.cancel()
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream?
        private val mmOutStream: OutputStream?
        private val handler = Handler(Looper.getMainLooper())

        init {
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null

            try {
                tmpIn = mmSocket.inputStream
                tmpOut = mmSocket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when creating input/output streams", e)
            }

            mmInStream = tmpIn
            mmOutStream = tmpOut
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var numBytes: Int
            var readMessage = ""

            while (true) {
                try {
                    if (mmInStream != null) {
                        numBytes = mmInStream.read(buffer)
                        val receivedData = String(buffer, 0, numBytes)
                        readMessage += receivedData

                        // Satır sonu karakterini kontrol et
                        val endOfLineIndex = readMessage.indexOf('\n')
                        if (endOfLineIndex > -1) {
                            val line = readMessage.substring(0, endOfLineIndex).trim()
                            readMessage = readMessage.substring(endOfLineIndex + 1)

                            // Gelen cevabı işle
                            processReceivedData(line)
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Veri okuma hatası", e)
                    handler.post {
                        _connectionStatus.value = "Bağlantı kesildi"
                    }
                    break
                }
            }
        }

        private fun processReceivedData(data: String) {
            Log.d(TAG, "ESP32'den gelen veri: $data")

            when {
                // Mesafe değeri (sayısal değer)
                data.toFloatOrNull() != null -> {
                    try {
                        val distanceInMeters = data.toFloat().toDouble()
                        handler.post {
                            _distanceRaw.value = distanceInMeters
                            updateDistanceDisplay(distanceInMeters)
                        }
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Geçersiz mesafe verisi: $data")
                    }
                }

                // Sıfırlama onayı
                data == "RESET_OK" -> {
                    handler.post {
                        _connectionStatus.value = "Mesafe başarıyla sıfırlandı"
                        _distanceRaw.value = 0.0
                        updateDistanceDisplay(0.0)
                    }
                }

                // Yarıçap yanıtı
                data.startsWith("RADIUS:") -> {
                    try {
                        val radiusValue = data.substring(7).toFloat().toDouble()
                        handler.post {
                            _wheelRadius.value = radiusValue
                            _connectionStatus.value = "ESP32'den yarıçap alındı: $radiusValue mm"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Yarıçap değeri işlenemedi: $data", e)
                    }
                }

                // Yarıçap ayarlama onayı
                data.startsWith("RADIUS_OK:") -> {
                    try {
                        val radiusValue = data.substring(10).toFloat().toDouble()
                        handler.post {
                            _wheelRadius.value = radiusValue
                            _connectionStatus.value = "Yarıçap ESP32'de ayarlandı: $radiusValue mm"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Yarıçap onayı işlenemedi: $data", e)
                    }
                }

                // Yarıçap ekleme onayı
                data.startsWith("RADIUS_ADDED:") -> {
                    try {
                        val radiusValue = data.substring(13).toFloat().toDouble()
                        handler.post {
                            _connectionStatus.value = "Mesafeye yarıçap eklendi: $radiusValue mm"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Yarıçap ekleme onayı işlenemedi: $data", e)
                    }
                }

                // Yarıçap ayarlama hatası
                data.startsWith("RADIUS_ERROR:") -> {
                    val errorMsg = data.substring(13)
                    handler.post {
                        _connectionStatus.value = "Yarıçap ayarlama hatası: $errorMsg"
                    }
                }

                // Diğer yanıtlar
                else -> {
                    Log.d(TAG, "Tanımlanamayan ESP32 yanıtı: $data")
                }
            }
        }

        // Cihaza veri gönder
        fun write(input: String) {
            try {
                mmOutStream?.write((input + "\n").toByteArray())
                Log.d(TAG, "ESP32'ye gönderilen veri: $input")
            } catch (e: IOException) {
                Log.e(TAG, "Veri gönderilirken hata oluştu", e)
                handler.post {
                    _connectionStatus.value = "Veri gönderme hatası: ${e.message}"
                }
            }
        }

        // Bağlantıyı sonlandır
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}

@Composable
fun DistanceMeterApp(
    modifier: Modifier = Modifier,
    distanceFormatted: String,
    distanceUnit: String,
    connectionStatus: String,
    deviceList: List<BluetoothDevice>,
    wheelRadius: Double,
    onConnectClick: () -> Unit,
    onResetClick: () -> Unit,
    onAddRadiusClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onWheelRadiusChange: (Double) -> Unit
) {
    var wheelRadiusInput by rememberSaveable { mutableStateOf(wheelRadius.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .fillMaxSize()
        ) {
            // Başlık
            Text(
                text = "Mesafe Ölçer",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Tekerlek Yarıçapı Ayarı
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Tekerlek Yarıçapı",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isEditing) {
                            OutlinedTextField(
                                value = wheelRadiusInput,
                                onValueChange = { newValue ->
                                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                        wheelRadiusInput = newValue
                                    }
                                },
                                label = { Text("Yarıçap (mm)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = {
                                val radiusValue = wheelRadiusInput.toDoubleOrNull() ?: wheelRadius
                                if (radiusValue > 0) {
                                    onWheelRadiusChange(radiusValue)
                                    isEditing = false
                                } else {
                                    // Burada kullanıcıya geçersiz bir değer girildiği konusunda geri bildirim verilebilir.
                                    wheelRadiusInput = wheelRadius.toString()
                                }
                            }) {
                                Text("Kaydet")
                            }
                        } else {
                            Text(
                                text = "$wheelRadius mm",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = { isEditing = true }) {
                                Text("Düzenle")
                            }
                        }
                    }
                }
            }

            // Mesafe Göstergesi
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ölçülen Mesafe",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = distanceFormatted,
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = distanceUnit,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }

            // Bağlantı Durumu
            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )

            // Butonlar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Bluetooth")
                }
                Button(
                    onClick = onResetClick,
                    modifier = Modifier.weight(0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Sıfırla")
                }
            }
            Button(
                onClick = onAddRadiusClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Yarıçap Ekle (+ $wheelRadius mm)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Eşleştirilmiş Cihazlar Listesi
            Text(
                text = "Eşleştirilmiş Cihazlar",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(deviceList) { device ->
                    val deviceName = if (ActivityCompat.checkSelfPermission(
                            LocalContext.current,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        device.name ?: "Bilinmeyen Cihaz"
                    } else {
                        "İzin Gerekli"
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        ListItem(
                            headlineContent = { Text(deviceName) },
                            supportingContent = { Text(device.address) },
                            modifier = Modifier.clickable { onDeviceClick(device) }
                        )
                    }
                }
            }
        }
    }
}