package fi.metropolia.bibeks.ble.data

import fi.metropolia.bibeks.ble.data.ble.DataResult
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface DataReceiveManager {
    val data: MutableSharedFlow<Resource<DataResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()
}