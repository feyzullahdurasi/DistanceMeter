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

    private fun resetDistance() {
        // Mesafe değerini sıfırla - ESP32'ye reset komutu gönder
        if (bluetoothSocket?.isConnected == true) {
            connectedThread?.write("RESET")
            _distanceRaw.value = 0.0
            updateDistanceDisplay(0.0)
        } else {
            _distanceRaw.value = 0.0
            updateDistanceDisplay(0.0)
        }
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
        private val handler = Handler(Looper.getMainLooper())

        init {
            var tmpIn: InputStream? = null

            try {
                tmpIn = mmSocket.inputStream
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when creating input stream", e)
            }

            mmInStream = tmpIn
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

                            try {
                                val distanceInMeters = line.toFloat().toDouble()
                                handler.post {
                                    _distanceRaw.value = distanceInMeters
                                    updateDistanceDisplay(distanceInMeters)
                                }
                            } catch (e: NumberFormatException) {
                                // Geçersiz sayı formatı
                                Log.e(TAG, "Geçersiz veri alındı: $line")
                            }
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

        // Cihaza veri gönder
        fun write(input: String) {
            try {
                mmSocket.outputStream.write(input.toByteArray())
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
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
    onDeviceClick: (BluetoothDevice) -> Unit,
    onWheelRadiusChange: (Double) -> Unit
) {
    var wheelRadiusInput by rememberSaveable { mutableStateOf(wheelRadius.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Başlık
        Text(
            text = "Tekerlek Mesafe Ölçer",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        // Tekerlek Yarıçapı Ayarı
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tekerlek Yarıçapı",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = wheelRadiusInput,
                            onValueChange = { newValue ->
                                // Sadece sayıları ve nokta işaretini kabul et
                                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                    wheelRadiusInput = newValue
                                }
                            },
                            label = { Text("Yarıçap (mm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(onClick = {
                            try {
                                val radiusValue = wheelRadiusInput.toDoubleOrNull() ?: wheelRadius
                                if (radiusValue > 0) {
                                    onWheelRadiusChange(radiusValue)
                                    isEditing = false
                                }
                            } catch (e: Exception) {
                                // Geçersiz değer durumunda varsayılan değere geri dön
                                wheelRadiusInput = wheelRadius.toString()
                            }
                        }) {
                            Text("Kaydet")
                        }
                    } else {
                        Text(
                            text = "$wheelRadius mm",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )

                        Button(onClick = { isEditing = true }) {
                            Text("Değiştir")
                        }
                    }
                }
            }
        }

        // Mesafe göstergesi kartı
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Toplam Ölçülen Mesafe",
                    fontSize = 18.sp,
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
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = distanceUnit,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }

        // Bağlantı durumu
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Text(
                text = connectionStatus,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp)
            )
        }

        // Butonlar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = onConnectClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Bluetooth Bağlantısı")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onResetClick,
                modifier = Modifier.weight(0.5f)
            ) {
                Text("Sıfırla")
            }
        }

        // Divider
        Divider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Cihaz listesi başlığı
        Text(
            text = "Eşleştirilmiş Cihazlar",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Cihaz listesi
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

                val deviceAddress = device.address

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    ListItem(
                        headlineContent = { Text(deviceName, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(deviceAddress, fontSize = 14.sp) },
                        modifier = Modifier.clickable { onDeviceClick(device) }
                    )
                }
            }
        }
    }
}