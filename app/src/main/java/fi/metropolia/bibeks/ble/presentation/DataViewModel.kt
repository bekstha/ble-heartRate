package fi.metropolia.bibeks.ble.presentation

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.DataReceiveManager
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DataViewModel @Inject constructor(
    private val dataReceiveManager: DataReceiveManager
) : ViewModel() {

    var pressure by mutableStateOf<Float?>(null)
        private set

    var accelerometer by mutableStateOf<Triple<Float, Float, Float>?>(null)
        private set

    var gyroscope by mutableStateOf<Triple<Float, Float, Float>?>(null)
        private set

    var timestampAcc by mutableStateOf<Long?>(null)
        private set

    var ecgSamples by mutableStateOf<List<Pair<Long, Long>>?>(null)
        private set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Uninitialized)
        private set

    var initializingMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // History for graphing: (timestamp, value) pairs
    private val pressureHistory = mutableStateListOf<Pair<Long, Float>>()
    private val accelerometerHistory = mutableStateListOf<Pair<Long, Triple<Float, Float, Float>>>()
    private val gyroscopeHistory = mutableStateListOf<Pair<Long, Triple<Float, Float, Float>>>()

    private fun subscribeToChanges() {
        viewModelScope.launch {
            dataReceiveManager.data.collect { result ->
                when (result) {
                    is Resource.Success -> {
                        connectionState = result.data.connectionState
                        pressure = result.data.pressure
                        accelerometer = result.data.accelerometer
                        gyroscope = result.data.gyroscope
                        timestampAcc = result.data.timestampAcc
                        ecgSamples = result.data.ecgSamples

                        // Add to history for graphing (limit to last 60 points, e.g., 1 minute at 1 Hz)
                        val timestamp = System.currentTimeMillis()
                        pressure?.let { p ->
                            pressureHistory.add(Pair(timestamp, p))
                            if (pressureHistory.size > 60) pressureHistory.removeAt(0)
                        }
                        accelerometer?.let { acc ->
                            accelerometerHistory.add(Pair(timestamp, acc))
                            if (accelerometerHistory.size > 60) accelerometerHistory.removeAt(0)
                        }
                        gyroscope?.let { gyr ->
                            gyroscopeHistory.add(Pair(timestamp, gyr))
                            if (gyroscopeHistory.size > 60) gyroscopeHistory.removeAt(0)
                        }
                    }
                    is Resource.Loading -> {
                        initializingMessage = result.message
                        connectionState = ConnectionState.CurrentlyInitializing
                    }
                    is Resource.Error -> {
                        errorMessage = result.errorMessage
                        connectionState = ConnectionState.Uninitialized
                    }
                }
            }
        }
    }

    fun initializeConnection() {
        errorMessage = null
        Log.d("DataViewModel", "Initializing connection to data sensor...")
        subscribeToChanges()
        dataReceiveManager.startReceiving()
    }

    fun disconnect() {
        Log.d("DataViewModel", "Disconnecting from data sensor...")
        dataReceiveManager.disconnect()
    }

    fun reconnect() {
        Log.d("DataViewModel", "Reconnecting to data sensor...")
        dataReceiveManager.reconnect()
    }

    fun clearError() {
        errorMessage = null
    }

    fun reset() {
        pressure = null
        accelerometer = null
        gyroscope = null
        timestampAcc = null
        ecgSamples = null
        connectionState = ConnectionState.Uninitialized
        initializingMessage = null
        errorMessage = null
        pressureHistory.clear()
        accelerometerHistory.clear()
        gyroscopeHistory.clear()
    }

    // Expose history for graphing
    fun getPressureHistory(): List<Pair<Long, Float>> = pressureHistory
    fun getAccelerometerHistory(): List<Pair<Long, Triple<Float, Float, Float>>> = accelerometerHistory
    fun getGyroscopeHistory(): List<Pair<Long, Triple<Float, Float, Float>>> = gyroscopeHistory

    override fun onCleared() {
        super.onCleared()
        dataReceiveManager.closeConnection()
        reset()
    }
}