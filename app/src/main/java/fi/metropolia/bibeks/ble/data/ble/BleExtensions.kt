package fi.metropolia.bibeks.ble.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import java.util.Locale

// const val CCCD_DESCRIPTOR_UUID = "0000180D-0000-1000-8000-00805F9B34FB"
const val CCCD_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"


fun BluetoothGatt.printGattTable() {
    if (services.isEmpty()) {
        Log.d("BluetoothGatt", "No service and characteristics available, call discoverServices() first?")
        return
    }

    services.forEach { service ->
        val characteristicsTable = service.characteristics.joinToString(
            separator = "\n|--",
            prefix = "|--"
        ) { char->
            var description = "${char.uuid}: ${char.printProperties()}"
            if (char.descriptors.isNotEmpty()) {
                description += "\n" + char.descriptors.joinToString(
                    separator = "\n|-----",
                    prefix = "|-----"
                ) { descriptor ->
                    "${descriptor.uuid}: ${descriptor.printProperties()}"
                }
            }
            description
        }
        Log.d("BluetoothGatt", "Service ${service.uuid}\nCharacteristics:\n$characteristicsTable")
    }
}

fun BluetoothGattCharacteristic.printProperties(): String = mutableListOf<String>().apply {
    if (isReadable()) add("READABLE")
    if (isWritable()) add("WRITABLE")
    if (isWritableWithoutResponse()) add("WRITABLE WITHOUT RESPONSE")
    if (IsIndicatable()) add("INDICATABLE")
    if (isNotifiable()) add("NOTIFIABLE")
    if (isEmpty()) add("EMPTY")
}.joinToString()

fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

fun BluetoothGattCharacteristic.IsIndicatable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean =
    properties and property != 0

fun BluetoothGattDescriptor.printProperties(): String = mutableListOf<String>().apply {
    if (isReadable()) add("READABLE")
    if (isWritable()) add("WRITABLE")
    if (isEmpty()) add("EMPTY")
}.joinToString()

fun BluetoothGattDescriptor.isReadable(): Boolean =
    containsProperty(BluetoothGattDescriptor.PERMISSION_READ)

fun BluetoothGattDescriptor.isWritable(): Boolean =
    containsProperty(BluetoothGattDescriptor.PERMISSION_WRITE)

fun BluetoothGattDescriptor.containsProperty(permission: Int): Boolean =
    permissions and permission != 0

fun BluetoothGattDescriptor.isCCD() =
    uuid.toString().uppercase(Locale.US) == CCCD_DESCRIPTOR_UUID.uppercase(Locale.US)

fun ByteArray.toHexString(): String =
    joinToString(separator = " ", prefix = "0x" ) { String.format("%02X", it)}