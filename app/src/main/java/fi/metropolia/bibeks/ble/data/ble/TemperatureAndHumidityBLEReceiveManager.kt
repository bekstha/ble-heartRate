package fi.metropolia.bibeks.ble.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.aware.Characteristics
import android.util.Log
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.TempHumidityResult
import fi.metropolia.bibeks.ble.data.TemperatureAndHumidityReceiveManager
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject



@SuppressLint("MissingPermission")
class TemperatureAndHumidityBLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : TemperatureAndHumidityReceiveManager {

    private val DEVICE_NAME = "Polar HR Sensor"
    private val TEMP_HUMIDITY_SERVICE_UUID = "6217FF49-AC7B-547E-EECF-016A06970BA9"
    private val TEMP_HUMIDITY_CHARATCERISTICS_UUID = "6217FF4A-B07D-5DEB-261E-2586752D942E"

/*    private val DEVICE_NAME = "Temp"
    private val TEMP_HUMIDITY_SERVICE_UUID = "0000181A-0000-1000-8000-00805F9B34FB"
    private val TEMP_HUMIDITY_CHARATCERISTICS_UUID = "00002A6E-0000-1000-8000-00805F9B34FB"*/

    override val data: MutableSharedFlow<Resource<TempHumidityResult>> = MutableSharedFlow()

    private  val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback () {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME){
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device..."))
                }
                if (isScanning){
                    result.device.connectGatt(context, false, gattCallback) //gattCallback, BluetoothDevice.TRANSPORT_LE
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }

    private var currentConnectionAttempt = 1
    private var MAX_CONNECTION_ATTEMPTS = 5

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS){
                if (newState == BluetoothProfile.STATE_CONNECTED){
                    coroutineScope.launch{
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@TemperatureAndHumidityBLEReceiveManager.gatt = gatt
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = TempHumidityResult(0f,0f, ConnectionState.Disconnected)))
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
                //printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(517) // mtu is maximum data amount, default 20 bytes
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristic = findCharacteristic(TEMP_HUMIDITY_SERVICE_UUID, TEMP_HUMIDITY_CHARATCERISTICS_UUID)
            if (characteristic == null) {
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find temp and humidity publisher"))
                }
                return
            }
            enableNotification(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            with(characteristic) {
                when(uuid) {
                    UUID.fromString(TEMP_HUMIDITY_CHARATCERISTICS_UUID) -> {
                        val multiplicator = if (value.first().toInt() > 0) -1 else 1
                        val temperature = value[1].toInt() + value[2].toInt()/ 10f
                        val humidity = 100 / 10f
                        val tempHumidityResult = TempHumidityResult(
                            multiplicator * temperature,
                            humidity,
                            ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = tempHumidityResult)
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.IsIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic, true) == false) {
                Log.d("BLEReceiveManger", "set characteistics notification failed")
                return
            }
            writeDescription(cccdDescriptor, payload)
        }
    }

    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        gatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device")
    }

    private fun findCharacteristic(serviceUUID: String, characteristicsUUID: String): BluetoothGattCharacteristic? {
        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }
    }

    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning BLE devices.."))
        }
        isScanning = true
        bleScanner.startScan(null,scanSettings, scanCallback)
    }

    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristic(TEMP_HUMIDITY_SERVICE_UUID, TEMP_HUMIDITY_CHARATCERISTICS_UUID)
        if(characteristic != null) {
            disconnectCharacteristic(characteristic)
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val cccdUUID = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUUID)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic,false) == false) {
                Log.d("TempHumidReceiveManager", "set characteristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }
}