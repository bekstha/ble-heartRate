package fi.metropolia.bibeks.ble.presentation

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.data.PressureReceiveManager
import fi.metropolia.bibeks.ble.util.Resource
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PressureViewModel @Inject constructor(
    private val pressureReceiveManager: PressureReceiveManager
) : ViewModel() {

    var pressure by mutableStateOf<Float?>(null)
        private set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Uninitialized)
        private set

    var initializingMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private fun subscribeToChanges() {
        viewModelScope.launch {
            pressureReceiveManager.data.collect { result ->
                when (result) {
                    is Resource.Success -> {
                        connectionState = result.data.connectionState
                        pressure = result.data.pressure
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
        Log.d("PressureViewModel", "Initializing connection to pressure sensor...")
        subscribeToChanges()
        pressureReceiveManager.startReceiving()
    }

    fun disconnect() {
        Log.d("PressureViewModel", "Disconnecting from pressure sensor...")
        pressureReceiveManager.disconnect()
    }

    fun reconnect() {
        Log.d("PressureViewModel", "Reconnecting to pressure sensor...")
        pressureReceiveManager.reconnect()
    }

    fun clearError() {
        errorMessage = null
    }

    fun reset() {
        pressure = null
        connectionState = ConnectionState.Uninitialized
        initializingMessage = null
        errorMessage = null
    }

    override fun onCleared() {
        super.onCleared()
        pressureReceiveManager.closeConnection()
        reset()
    }
}