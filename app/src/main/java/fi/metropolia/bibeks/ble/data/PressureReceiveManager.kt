package fi.metropolia.bibeks.ble.data

import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface PressureReceiveManager {
    val data: MutableSharedFlow<Resource<PressureResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()
}