package fi.metropolia.bibeks.ble.data

data class TempHumidityResult(
    val temperature: Float,
    val humidity: Float,
    val connectionState: ConnectionState
)
