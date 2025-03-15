package fi.metropolia.bibeks.ble.presentation

import android.bluetooth.BluetoothAdapter
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
fun DataScreen(
    onBluetoothStateChanged: () -> Unit,
    viewModel: DataViewModel = hiltViewModel()
) {
    SystemBroadcastReceiver(systemAction = BluetoothAdapter.ACTION_STATE_CHANGED) { intent ->
        val action = intent?.action ?: return@SystemBroadcastReceiver
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            onBluetoothStateChanged()
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions = PermissionUtils.permission)
    val lifecycleOwner = LocalLifecycleOwner.current
    val bleConnectionState = viewModel.connectionState

    DisposableEffect(key1 = lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                permissionState.launchMultiplePermissionRequest()
                if (permissionState.allPermissionsGranted && bleConnectionState == ConnectionState.Uninitialized) {
                    viewModel.initializeConnection()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1.5f)
                .border(
                    BorderStroke(5.dp, Color.Blue),
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (bleConnectionState) {
                ConnectionState.CurrentlyInitializing -> {
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        viewModel.initializingMessage?.let {
                            Text(text = it)
                        }
                    }
                }

                ConnectionState.Uninitialized -> {
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
                                onClick = {
                                    if (permissionState.allPermissionsGranted) {
                                        viewModel.initializeConnection()
                                    }
                                }
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
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Pressure
                        Text(
                            text = "Pressure: ${viewModel.pressure?.let { "%.2f PSI".format(it) } ?: "N/A"}",
                            style = MaterialTheme.typography.titleMedium
                        )

                        // Accelerometer
                        Text(
                            text = "Accelerometer: ${
                                viewModel.accelerometer?.let {
                                    "(X: %.2f, Y: %.2f, Z: %.2f)".format(
                                        it.first,
                                        it.second,
                                        it.third
                                    )
                                } ?: "N/A"
                            }",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Gyroscope
                        Text(
                            text = "Gyroscope: ${
                                viewModel.gyroscope?.let {
                                    "(X: %.2f, Y: %.2f, Z: %.2f)".format(
                                        it.first,
                                        it.second,
                                        it.third
                                    )
                                } ?: "N/A"
                            }",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Timestamp
                        Text(
                            text = "Timestamp: ${viewModel.timestampAcc?.let { "$it ms" } ?: "N/A"}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // ECG Samples
                        Text(
                            text = "ECG Samples: ${viewModel.ecgSamples?.joinToString { "(${it.first}, ${it.second}ms)" } ?: "N/A"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                ConnectionState.Disconnected -> {
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

