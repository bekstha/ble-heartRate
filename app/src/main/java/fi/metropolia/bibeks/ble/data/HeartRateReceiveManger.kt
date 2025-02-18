package fi.metropolia.bibeks.ble.data

import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface HeartRateReceiveManger {
    val data: MutableSharedFlow<Resource<HeartRateResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()
}