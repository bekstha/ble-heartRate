package fi.metropolia.bibeks.ble.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun Navigation(
    onBluetoothStateChanged:() -> Unit
) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = Screen.StartScreen.route ){
        composable(Screen.StartScreen.route){
            StartScreen(navController = navController)
        }
        composable(Screen.PressureScreen.route){
            DataScreen (onBluetoothStateChanged)
        }
    }
}

sealed class Screen(val route: String) {
    object  StartScreen: Screen("start_screen")
    object  TemperatureHumidityScreen: Screen("temp_humid_screen")
    object  HeartRateScreen: Screen("heart_rate_screen")
    object  PressureScreen: Screen("pressure_screen")
    object  DataScreen: Screen("data_screen")

}