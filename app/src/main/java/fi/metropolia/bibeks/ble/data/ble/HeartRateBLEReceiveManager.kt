package fi.metropolia.bibeks.ble.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.HeartRateReceiveManger
import fi.metropolia.bibeks.ble.data.HeartRateResult
import fi.metropolia.bibeks.ble.data.TempHumidityResult
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@SuppressLint("MissingPermission")
class HeartRateBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : HeartRateReceiveManger {

    private val DEVICE_NAME = "Polar HR Sensor"

    // Heart Rate Service & Characteristic UUIDs
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_CHARACTERISTIC_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    override val data: MutableSharedFlow<Resource<HeartRateResult>> = MutableSharedFlow()


    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private val coroutineScope = CoroutineScope(Dispatchers.Default)


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to Polar HR Sensor..."))
                }
                if (isScanning) {
                    result.device.connectGatt(context, false, gattCallback , BluetoothDevice.TRANSPORT_LE)
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
            if (status == BluetoothGatt.GATT_SUCCESS){
                if (newState == BluetoothProfile.STATE_CONNECTED){
                    coroutineScope.launch{
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@HeartRateBLEReceiveManager.gatt = gatt
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = HeartRateResult(0, emptyList(), ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }
            } else {
                gatt.close()
                currentConnectionAttempt += 1
                coroutineScope.launch {
                    data.emit(Resource.Loading(message ="Attempting to connect $currentConnectionAttempt/$MAX_CONNECTION_ATTEMPTS"))
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
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(200) // mtu is maximum data amount, default 20 bytes
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristic = findCharacteristic(HEART_RATE_SERVICE_UUID, HEART_RATE_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find temp and humidity publisher"))
                }
                return
            }
            enableNotification(characteristic)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            with(characteristic) {
                when (uuid) {
                    UUID.fromString(HEART_RATE_CHARACTERISTIC_UUID.toString()) -> {
                        val heartRateResult = parseHeartRateData(value)
                        coroutineScope.launch {
                            data.emit(Resource.Success(data = heartRateResult))
                        }
                        Log.d("heartRateResult", heartRateResult.toString())
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB") // CCCD UUID
        val payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
                Log.d("HeartRateBLEManager", "Failed to set characteristic notification")
                return
            }
            writeDescriptor(cccdDescriptor, payload)
        }
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner ?: error("BluetoothLeScanner is not available")
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        gatt?.let {
            descriptor.value = payload
            it.writeDescriptor(descriptor)
        } ?: Log.e("HeartRateBLEManager", "GATT is null, cannot write descriptor")
    }

    private fun findCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): BluetoothGattCharacteristic? {
        return gatt?.services?.find { it.uuid == serviceUUID }
            ?.characteristics?.find { it.uuid == characteristicUUID }
    }

    private fun parseHeartRateData(data: ByteArray): HeartRateResult {
        if (data.isEmpty()) {
            return HeartRateResult(0, emptyList(),  ConnectionState.Connected)
        }

        var offset = 0
        val flag = data[offset++].toInt()
        val is16Bit = flag and 0x01 != 0
        val heartRate = if (is16Bit) {
            if (offset + 1 >= data.size) return HeartRateResult(0, emptyList(), ConnectionState.Connected)
            (data[offset++].toInt() and 0xFF) or ((data[offset++].toInt() and 0xFF) shl 8)
        } else {
            if (offset >= data.size) return HeartRateResult(0,emptyList(), ConnectionState.Connected)
            data[offset++].toInt() and 0xFF
        }

        val rrIntervals = mutableListOf<Int>()
        while (offset + 1 < data.size) {
            val rrValue = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            rrIntervals.add(rrValue)
            offset += 2
        }

        return HeartRateResult(heartRate, rrIntervals, ConnectionState.Connected)
    }

    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning for Polar HR Sensor..."))
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
        coroutineScope.launch(Dispatchers.IO) {
            gatt?.disconnect()
        }
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristic(HEART_RATE_SERVICE_UUID,
            HEART_RATE_CHARACTERISTIC_UUID
        )
        characteristic?.let { disableNotification(it) }
        gatt?.close()
        gatt = null // Clear the reference
    }

    private fun disableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        characteristic.getDescriptor(cccdUUID)?.let { cccdDescriptor ->
            gatt?.setCharacteristicNotification(characteristic, false)
            writeDescriptor(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }
}