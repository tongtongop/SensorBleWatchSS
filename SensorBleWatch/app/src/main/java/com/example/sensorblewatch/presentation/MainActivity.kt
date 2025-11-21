// 👇 change this line if your package is different
package com.example.sensorblewatch.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    // 👇 THESE are Compose state holders – when they change, UI updates
    private var accelState by mutableStateOf(Triple(0f, 0f, 0f))
    private var gyroState by mutableStateOf(Triple(0f, 0f, 0f))
    private var isAdvertising by mutableStateOf(false)

    // BLE
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                startBleAdvertising()
            } else {
                Log.w("SensorBleWatch", "BLE permissions NOT granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Surface {
                    SensorBleScreen(
                        accel = accelState,
                        gyro = gyroState,
                        isAdvertising = isAdvertising,
                        onToggleAdvertising = { toggleBleAdvertising() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensors()
    }

    private fun registerSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // 👇 update Compose state (UI will refresh)
                accelState = Triple(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroState = Triple(event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // not needed
    }

    // ---- BLE logic ----

    private fun toggleBleAdvertising() {
        if (!isAdvertising) {
            // flip state immediately for UI
            isAdvertising = true

            val missing = blePermissions.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(blePermissions)
            } else {
                startBleAdvertising()
            }
        } else {
            stopBleAdvertising()
        }
    }


    @SuppressLint("MissingPermission")
    private fun startBleAdvertising() {
        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e("SensorBleWatch", "Bluetooth adapter not available or disabled")
            return
        }

        bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e("SensorBleWatch", "BLE advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        // 👉 Now we use real accel+gyro payload (12 bytes)
        val payload = buildPayloadFromSensors()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x1234, payload) // 0x1234 = example manufacturer ID
            .setIncludeDeviceName(false)          // KEEP false to avoid size issues
            .build()

        Log.i("SensorBleWatch", "startBleAdvertising() called with sensor payload")
        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }



    @SuppressLint("MissingPermission")
    private fun stopBleAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false       // 👈 also updates button text
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.i("SensorBleWatch", "BLE advertising started")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("SensorBleWatch", "BLE advertising failed: $errorCode")
            isAdvertising = false
        }
    }

    /** Pack accel/gyro into 12 bytes: ax,ay,az,gx,gy,gz as signed shorts */
    private fun buildPayloadFromSensors(): ByteArray {
        fun toShort1000(v: Float): Short {
            val scaled = (v * 1000).toInt()
            return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val ax = toShort1000(accelState.first)
        val ay = toShort1000(accelState.second)
        val az = toShort1000(accelState.third)

        val gx = toShort1000(gyroState.first)
        val gy = toShort1000(gyroState.second)
        val gz = toShort1000(gyroState.third)

        fun sBytes(s: Short) = byteArrayOf(
            ((s.toInt() shr 8) and 0xFF).toByte(),
            (s.toInt() and 0xFF).toByte()
        )

        return sBytes(ax) + sBytes(ay) + sBytes(az) +
                sBytes(gx) + sBytes(gy) + sBytes(gz)
    }
}

@Composable
fun SensorBleScreen(
    accel: Triple<Float, Float, Float>,
    gyro: Triple<Float, Float, Float>,
    isAdvertising: Boolean,
    onToggleAdvertising: () -> Unit
) {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Accel (x, y, z):\n" +
                            "%.2f, %.2f, %.2f".format(accel.first, accel.second, accel.third),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Gyro (x, y, z):\n" +
                            "%.2f, %.2f, %.2f".format(gyro.first, gyro.second, gyro.third),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isAdvertising) "Advertising: ON" else "Advertising: OFF",
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onToggleAdvertising() }) {
                    Text(if (isAdvertising) "Stop BLE" else "Start BLE")
                }
            }
        }
    }
}

