package fi.metropolia.bibeks.ble.data
data class PressureResult(
    val pressure: Float,
    val connectionState: ConnectionState
)