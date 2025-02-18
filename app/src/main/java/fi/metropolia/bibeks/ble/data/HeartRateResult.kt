package fi.metropolia.bibeks.ble.data

data class HeartRateResult(
    val heartRate: Int,
    val rrIntervals: List<Int>,
    val connectionState: ConnectionState
)