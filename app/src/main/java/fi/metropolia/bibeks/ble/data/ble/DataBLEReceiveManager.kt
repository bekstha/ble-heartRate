package fi.metropolia.bibeks.ble.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.DataReceiveManager
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
class DataBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : DataReceiveManager {

    private val DEVICE_MAC = "40:4C:CA:47:11:6A"
    private val DEVICE_NAME = "ESP32 HRM"

    private val DATA_SERVICE_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val DATA_CHARACTERISTIC_UUID = UUID.fromString("987f6543-21af-47d3-b8cd-526614174000")

    override val data: MutableSharedFlow<Resource<DataResult>> = MutableSharedFlow()

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
            Log.d("DataBLE", "Found device: ${device.address}, Name: ${device.name}")
            if (device.address == DEVICE_MAC || device.name == DEVICE_NAME) {
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to ESP32 HRM data sensor..."))
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
                    Log.d("DataBLE", "Connected to GATT server")
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@DataBLEReceiveManager.gatt = gatt
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("DataBLE", "Disconnected from GATT server")
                    isPolling = false // Stop polling on disconnect
                    coroutineScope.launch {
                        data.emit(
                            Resource.Success(
                                data = DataResult(
                                    0f,
                                    Triple(0f, 0f, 0f),
                                    Triple(0f, 0f, 0f),
                                    0L,
                                    emptyList(),
                                    ConnectionState.Disconnected
                                )
                            )
                        )
                    }
                    gatt.close()
                }
            } else {
                Log.e("DataBLE", "Connection failed, status: $status")
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
                Log.d("DataBLE", "Services discovered, status: $status")
                //printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                val success = requestMtu(517)
                Log.d("DataBLE", "MTU request initiated: $success")
                coroutineScope.launch {
                    delay(3000)
                    if (gatt != null) {
                        Log.d("DataBLE", "MTU timeout, proceeding with default MTU")
                        proceedToNotifications(gatt)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("DataBLE", "MTU changed to $mtu, status: $status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("DataBLE", "MTU change failed, status: $status")
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "MTU change failed, status: $status"))
                }
                return
            }
            proceedToNotifications(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic) {
                when (uuid) {
                    DATA_CHARACTERISTIC_UUID -> {
                        val rawValue = value.toHexString()
                        Log.d("DataBLE", "Received data (notify): $rawValue")
                        val dataResult = parseData(value)
                        coroutineScope.launch {
                            data.emit(Resource.Success(data = dataResult))
                        }
                        Log.d("DataResult", "Notify result: $dataResult")
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
                    DATA_CHARACTERISTIC_UUID -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val rawValue = value.toHexString()
                            Log.d("DataBLE", "Read data: $rawValue")
                            val dataResult = parseData(value)
                            coroutineScope.launch {
                                data.emit(Resource.Success(data = dataResult))
                            }
                            Log.d("DataResult", "Read result: $dataResult")
                        } else {
                            Log.e("DataBLE", "Read failed, status: $status")
                            coroutineScope.launch {
                                data.emit(Resource.Error(errorMessage = "Failed to read data, status: $status"))
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun proceedToNotifications(gatt: BluetoothGatt) {
        val characteristic = findCharacteristic(DATA_SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("DataBLE", "Characteristic $DATA_CHARACTERISTIC_UUID not found")
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Could not find data characteristic"))
            }
            return
        }
/*        Log.d(
            "DataBLE",
            "Found characteristic: ${characteristic.uuid}, Properties: ${characteristic.printProperties()}"
        )*/
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            enableNotification(characteristic)
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            Log.d("DataBLE", "Starting data polling")
            startPolling(gatt, characteristic)
        } else {
            Log.e("DataBLE", "Characteristic lacks NOTIFY or READ properties")
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
            Log.e(
                "DataBLE",
                "CCCD descriptor not found for ${characteristic.uuid}, relying on polling"
            )
            return
        }
        if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
            Log.e("DataBLE", "Failed to enable notifications for ${characteristic.uuid}")
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Failed to enable notifications"))
            }
            return
        }
        descriptor.value = payload
        val success = gatt?.writeDescriptor(descriptor)
        Log.d("DataBLE", "Writing CCCD descriptor: $success")
    }

    private fun startPolling(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        isPolling = true
        coroutineScope.launch {
            while (isPolling && gatt != null) {
                val success = gatt.readCharacteristic(characteristic)
                Log.d("DataBLE", "Polling read request: $success")
                delay(1000) // Poll every 1 second
            }
            if (!isPolling) {
                Log.d("DataBLE", "Polling stopped")
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
        } ?: Log.e("DataBLE", "GATT is null, cannot write descriptor")
    }

    private fun findCharacteristic(
        serviceUUID: UUID,
        characteristicUUID: UUID
    ): BluetoothGattCharacteristic? {
        return gatt?.services?.find { it.uuid == serviceUUID }
            ?.characteristics?.find { it.uuid == characteristicUUID }
    }

    private fun parseData(data: ByteArray): DataResult {
        Log.d("DataBLE", "Raw data: ${data.toHexString()}, Length: ${data.size}")
        if (data.size < 54) return DataResult(
            0f,
            Triple(0f, 0f, 0f),
            Triple(0f, 0f, 0f),
            0L,
            emptyList(),
            ConnectionState.Connected
        )

        return try {
            // Byte 0: Packet start identifier (1)
            val packetStart = data[0].toInt()

            // Bytes 1-4: Pressure (float, kPa)
            val pressureKPa = ByteBuffer.wrap(data, 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .float
            val pressurePsi = pressureKPa * 0.145038f // Convert kPa to PSI

            // Bytes 5-28: Accelerometer and Gyroscope (6 floats)
            val accX = ByteBuffer.wrap(data, 5, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val accY = ByteBuffer.wrap(data, 9, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val accZ = ByteBuffer.wrap(data, 13, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val gyrX = ByteBuffer.wrap(data, 17, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val gyrY = ByteBuffer.wrap(data, 21, 4).order(ByteOrder.LITTLE_ENDIAN).float
            val gyrZ = ByteBuffer.wrap(data, 25, 4).order(ByteOrder.LITTLE_ENDIAN).float

            // Bytes 29-32: Timestamp for accelerometer (signed long)
            val timestampAcc =
                ByteBuffer.wrap(data, 29, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()

            // Bytes 33-52: 4 ECG samples + timestamps (4 × (signed long + signed long))
            val ecgSamples = mutableListOf<Pair<Long, Long>>()
            for (i in 0 until 4) {
                val offset = 33 + i * 8
                val ecg =
                    ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                val timestamp =
                    ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                ecgSamples.add(Pair(ecg, timestamp))
            }

            // Byte 53: Packet end identifier
            val packetEnd = data[53].toInt()

            Log.d(
                "DataBLE",
                "Parsed: Pressure=$pressurePsi PSI, Acc=($accX, $accY, $accZ), Gyr=($gyrX, $gyrY, $gyrZ), Timestamp=$timestampAcc, ECG=$ecgSamples, PacketEnd=$packetEnd,"
            )

            DataResult(
                pressure = pressurePsi.coerceIn(0f, 25f),
                accelerometer = Triple(accX, accY, accZ),
                gyroscope = Triple(gyrX, gyrY, gyrZ),
                timestampAcc = timestampAcc,
                ecgSamples = ecgSamples,
                connectionState = ConnectionState.Connected
            )
        } catch (e: Exception) {
            Log.e("DataBLE", "Parsing error: $e")
            DataResult(
                0f,
                Triple(0f, 0f, 0f),
                Triple(0f, 0f, 0f),
                0L,
                emptyList(),
                ConnectionState.Connected
            )
        }
    }

    override fun startReceiving() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            coroutineScope.launch {
                data.emit(Resource.Error(errorMessage = "Bluetooth is disabled or unavailable"))
            }
            Log.e("DataBLE", "Bluetooth adapter is null or disabled")
            return
        }
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning for ESP32 HRM data sensor..."))
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
        val characteristic = findCharacteristic(DATA_SERVICE_UUID, DATA_CHARACTERISTIC_UUID)
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