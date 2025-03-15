package fi.metropolia.bibeks.ble.presentation

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import fi.metropolia.bibeks.ble.data.ConnectionState
import fi.metropolia.bibeks.ble.presentation.permissions.PermissionUtils
import fi.metropolia.bibeks.ble.presentation.permissions.SystemBroadcastReceiver

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PressureScreen(
    onBluetoothStateChanged: () -> Unit,
    viewModel: PressureViewModel = hiltViewModel()
) {
    // Listening to Bluetooth state changes
    SystemBroadcastReceiver(systemAction = BluetoothAdapter.ACTION_STATE_CHANGED) { bluetoothState ->
        val action = bluetoothState?.action ?: return@SystemBroadcastReceiver
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            onBluetoothStateChanged()
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions = PermissionUtils.permission)
    val lifecycleOwner = LocalLifecycleOwner.current
    val bleConnectionState = viewModel.connectionState

    DisposableEffect(
        key1 = lifecycleOwner,
        effect = {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    permissionState.launchMultiplePermissionRequest()
                    // Start reconnecting if permissions are granted
                    if (permissionState.allPermissionsGranted && bleConnectionState == ConnectionState.Disconnected) {
                        viewModel.reconnect()
                    }
                }
                if (event == Lifecycle.Event.ON_STOP) {
                    // Disconnect when lifecycle stops
                    if (bleConnectionState == ConnectionState.Connected) {
                        viewModel.disconnect()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    )

    LaunchedEffect(key1 = permissionState.allPermissionsGranted) {
        Log.d("PressureScreen", "Permissions granted: ${permissionState.allPermissionsGranted}")
        if (permissionState.allPermissionsGranted) {
            if (bleConnectionState == ConnectionState.Uninitialized) {
                viewModel.initializeConnection()
            }
        } else {
            Log.w("PressureScreen", "Permissions not granted yet")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(1f)
                .border(
                    BorderStroke(
                        5.dp, Color.Blue
                    ),
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (bleConnectionState) {
                ConnectionState.CurrentlyInitializing -> {
                    Log.d("PressureScreen", "State: Initializing, Message: ${viewModel.initializingMessage}")
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        viewModel.initializingMessage?.let {
                            Text(text = it)
                        }
                    }
                }
                ConnectionState.Uninitialized -> {
                    Log.d("PressureScreen", "State: Uninitialized, Error: ${viewModel.errorMessage}")
                    if (!permissionState.allPermissionsGranted) {
                        Text(
                            text = "Go to the app settings and allow the missing permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    } else if (viewModel.errorMessage != null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = viewModel.errorMessage!!)
                            Button(
                                onClick = { viewModel.initializeConnection() },
                                enabled = permissionState.allPermissionsGranted
                            ) {
                                Text("Try again")
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.initializeConnection() },
                            enabled = permissionState.allPermissionsGranted
                        ) {
                            Text("Initialize connection")
                        }
                    }
                }
                ConnectionState.Connected -> {
                    Log.d("PressureScreen", "State: Connected, Pressure: ${viewModel.pressure}")
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Pressure: ${viewModel.pressure ?: "N/A"} PSI",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                ConnectionState.Disconnected -> {
                    Log.d("PressureScreen", "State: Disconnected")
                    Button(
                        onClick = { viewModel.initializeConnection() },
                        enabled = permissionState.allPermissionsGranted
                    ) {
                        Text("Initialize again")
                    }
                }
            }
        }
    }
}