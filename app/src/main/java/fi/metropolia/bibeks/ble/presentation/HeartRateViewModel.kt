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
import fi.metropolia.bibeks.ble.data.HeartRateReceiveManger
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HeartRateViewModel @Inject constructor(
    private val heartRateReceiveManager: HeartRateReceiveManger
) : ViewModel() {

    var heartRate by mutableStateOf<Float?>(null)
        private set

    var rrIntervals by mutableStateOf<List<Int>>(emptyList())
        private set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Uninitialized)
        private set

    var initializingMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set


    private fun subscribeToChanges() {
        viewModelScope.launch {
            heartRateReceiveManager.data.collect{ result ->
                when (result) {
                    is Resource.Success -> {
                        connectionState = result.data.connectionState
                        heartRate = result.data.heartRate.toFloat()
                        rrIntervals = result.data.rrIntervals

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
        Log.d("HeartRateViewModel", "Initializing connection...")
        subscribeToChanges()
        heartRateReceiveManager.startReceiving()
    }

    fun disconnect() {
        Log.d("HeartRateViewModel", "Disconnecting from heart rate sensor...")
        heartRateReceiveManager.disconnect()
    }

    fun reconnect() {
        Log.d("HeartRateViewModel", "Reconnecting to heart rate sensor...")
        heartRateReceiveManager.reconnect()
    }

    fun clearError() {
        errorMessage = null
    }

    fun reset() {
        heartRate = null
        rrIntervals = emptyList()
        connectionState = ConnectionState.Uninitialized
        initializingMessage = null
        errorMessage = null
    }

    override fun onCleared() {
        super.onCleared()
        heartRateReceiveManager.closeConnection()
        reset()
    }
}