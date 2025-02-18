package fi.metropolia.bibeks.ble.data

sealed interface ConnectionState {
    object  Connected: ConnectionState
    object  Disconnected: ConnectionState
    object  Uninitialized: ConnectionState
    object  CurrentlyInitializing: ConnectionState
}