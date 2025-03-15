package fi.metropolia.bibeks.ble.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.PressureReceiveManager
import fi.metropolia.bibeks.ble.data.PressureResult
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject

@SuppressLint("MissingPermission")
class PressureBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : PressureReceiveManager {

    private val DEVICE_MAC = "40:4C:CA:47:11:6A"
    private val DEVICE_NAME = "ESP32 HRM"

    private val PRESSURE_SERVICE_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val PRESSURE_CHARACTERISTIC_UUID = UUID.fromString("987f6543-21af-47d3-b8cd-526614174000")

    override val data: MutableSharedFlow<Resource<PressureResult>> = MutableSharedFlow()

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private var isPolling = false
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d("PressureBLE", "Found device: ${device.address}, Name: ${device.name}")
            if (device.address == DEVICE_MAC || device.name == DEVICE_NAME) {
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to ESP32-C6 pressure sensor..."))
                }
                if (isScanning) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }

    private var currentConnectionAttempt = 1
    private val MAX_CONNECTION_ATTEMPTS = 5

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("PressureBLE", "Connected to GATT server")
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@PressureBLEReceiveManager.gatt = gatt
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("PressureBLE", "Disconnected from GATT server")
                    isPolling = false // Stop polling on disconnect
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = PressureResult(0f, ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }
            } else {
                Log.e("PressureBLE", "Connection failed, status: $status")
                gatt.close()
                currentConnectionAttempt += 1
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Attempting to connect $currentConnectionAttempt/$MAX_CONNECTION_ATTEMPTS"))
                }
                if (currentConnectionAttempt <= MAX_CONNECTION_ATTEMPTS) {
                    startReceiving()
                } else {
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to BLE device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt) {
                Log.d("PressureBLE", "Services discovered, status: $status")
                //printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                val success = requestMtu(517)
                Log.d("PressureBLE", "MTU request initiated: $success")
                coroutineScope.launch {
                    delay(3000)
                    if (gatt != null) {
                        Log.d("PressureBLE", "MTU timeout, proceeding with default MTU")
                        proceedToNotifications(gatt)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("PressureBLE", "MTU changed to $mtu, status: $status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("PressureBLE", "MTU change failed, status: $status")
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "MTU change failed, status: $status"))
                }
                return
            }
            proceedToNotifications(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            with(characteristic) {
                when (uuid) {
                    PRESSURE_CHARACTERISTIC_UUID -> {
                        val rawValue = value.toHexString()
                        Log.d("PressureBLE", "Received data (notify): $rawValue")
                        val pressureResult = parsePressureData(value)
                        coroutineScope.launch {
                            data.emit(Resource.Success(data = pressureResult))
                        }
                        Log.d("PressureResult", "Notify result: $pressureResult")
                    }
                    else -> Unit
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (uuid) {
                    PRESSURE_CHARACTERISTIC_UUID -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val rawValue = value.toHexString()
                            Log.d("PressureBLE", "Read data: $rawValue")
                            val pressureResult = parsePressureData(value)
                            coroutineScope.launch {
                                data.emit(Resource.Success(data = pressureResult))
                            }
                            Log.d("PressureResult", "Read result: $pressureResult")
                        } else {
                            Log.e("PressureBLE", "Read failed, status: $status")
                            coroutineScope.launch {
                                data.emit(Resource.Error(errorMessage = "Failed to read pressure, status: $status"))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun proceedToNotifications(gatt: BluetoothGatt) {
        val characteristic = findCharacteristic(PRESSURE_SERVICE_UUID, PRESSURE_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("PressureBLE", "Characteristic $PRESSURE_CHARACTERISTIC_UUID not found")
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Could not find pressure characteristic"))
            }
            return
        }
        //Log.d("PressureBLE", "Found characteristic: ${characteristic.uuid}, Properties: ${characteristic.printProperties()}")
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            enableNotification(characteristic)
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            Log.d("PressureBLE", "Starting pressure polling")
            startPolling(gatt, characteristic)
        } else {
            Log.e("PressureBLE", "Characteristic lacks NOTIFY or READ properties")
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Characteristic cannot be read or notified"))
            }
        }
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val descriptor = characteristic.getDescriptor(cccdUuid)
        if (descriptor == null) {
            Log.e("PressureBLE", "CCCD descriptor not found for ${characteristic.uuid}, relying on polling")
            return
        }
        if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
            Log.e("PressureBLE", "Failed to enable notifications for ${characteristic.uuid}")
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Failed to enable notifications"))
            }
            return
        }
        descriptor.value = payload
        val success = gatt?.writeDescriptor(descriptor)
        Log.d("PressureBLE", "Writing CCCD descriptor: $success")
    }

    private fun startPolling(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        isPolling = true
        coroutineScope.launch {
            while (isPolling && gatt != null) {
                val success = gatt.readCharacteristic(characteristic)
                Log.d("PressureBLE", "Polling read request: $success")
                delay(1000) // Poll every 1 second, adjust as needed
            }
            if (!isPolling) {
                Log.d("PressureBLE", "Polling stopped")
            }
        }
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner ?: error("BluetoothLeScanner is not available")
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        gatt?.let {
            descriptor.value = payload
            it.writeDescriptor(descriptor)
        } ?: Log.e("PressureBLE", "GATT is null, cannot write descriptor")
    }

    private fun findCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): BluetoothGattCharacteristic? {
        return gatt?.services?.find { it.uuid == serviceUUID }
            ?.characteristics?.find { it.uuid == characteristicUUID }
    }

    private fun parsePressureData(data: ByteArray): PressureResult {
        Log.d("PressureBLE", "Raw data: ${data.toHexString()}, Length: ${data.size}")
        if (data.size < 36 + 4) return PressureResult(0f, ConnectionState.Connected)
        val pressure = try {
            val raw = ByteBuffer.wrap(data, 36, 4) // Offset 36: 00 E1 FF 07
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            // Scale 24-bit raw (0-16777215) to 0-25 PSI
            val psi = (raw.toFloat() / 16777215f) * 25f
            psi.coerceIn(0f, 25f) // Clamp to valid range
        } catch (e: Exception) {
            Log.e("PressureBLE", "Parsing error: $e")
            0f
        }
        return PressureResult(pressure, ConnectionState.Connected)
    }

    override fun startReceiving() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Bluetooth is disabled or unavailable"))
            }
            Log.e("PressureBLE", "Bluetooth adapter is null or disabled")
            return
        }
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning for ESP32-C6 pressure sensor..."))
        }
        isScanning = true
        bleScanner.startScan(null, scanSettings, scanCallback)
    }

    override fun reconnect() {
        coroutineScope.launch(Dispatchers.IO) {
            gatt?.connect()
        }
    }

    override fun disconnect() {
        isPolling = false // Stop polling on disconnect
        coroutineScope.launch(Dispatchers.IO) {
            gatt?.disconnect()
        }
    }

    override fun closeConnection() {
        isPolling = false // Stop polling on close
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristic(PRESSURE_SERVICE_UUID, PRESSURE_CHARACTERISTIC_UUID)
        characteristic?.let { disableNotification(it) }
        gatt?.close()
        gatt = null
    }

    private fun disableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        characteristic.getDescriptor(cccdUUID)?.let { cccdDescriptor ->
            gatt?.setCharacteristicNotification(characteristic, false)
            writeDescriptor(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
}