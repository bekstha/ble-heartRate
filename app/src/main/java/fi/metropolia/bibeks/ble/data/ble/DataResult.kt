package fi.metropolia.bibeks.ble.data.ble

import fi.metropolia.bibeks.ble.data.ConnectionState

data class DataResult (
    val pressure: Float, // PSI
    val accelerometer: Triple<Float, Float, Float>, // AccX, AccY, AccZ
    val gyroscope: Triple<Float, Float, Float>, // GyrX, GyrY, GyrZ
    val timestampAcc: Long, // Timestamp for accelerometer (ms)
    val ecgSamples: List<Pair<Long, Long>>, // List of (ECG value, timestamp) pairs
    val connectionState: ConnectionState
)