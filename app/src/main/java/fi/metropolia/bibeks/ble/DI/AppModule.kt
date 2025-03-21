package fi.metropolia.bibeks.ble.di  // Lowercase "di"

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fi.metropolia.bibeks.ble.data.DataReceiveManager
import fi.metropolia.bibeks.ble.data.HeartRateReceiveManger
import fi.metropolia.bibeks.ble.data.PressureReceiveManager
import fi.metropolia.bibeks.ble.data.TemperatureAndHumidityReceiveManager
import fi.metropolia.bibeks.ble.data.ble.DataBLEReceiveManager
import fi.metropolia.bibeks.ble.data.ble.HeartRateBLEReceiveManager
import fi.metropolia.bibeks.ble.data.ble.PressureBLEReceiveManager
import fi.metropolia.bibeks.ble.data.ble.TemperatureAndHumidityBLEReceiveManager
//import fi.metropolia.bibeks.ble.data.ble.TemperatureAndHumidityBLEReceiveManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBluetoothAdapter(@ApplicationContext context: Context): BluetoothAdapter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    @Provides
    @Singleton
    fun provideTempHumidityReceiveManager(
        @ApplicationContext context: Context,
        bluetoothAdapter: BluetoothAdapter
    ): TemperatureAndHumidityReceiveManager {
        return TemperatureAndHumidityBLEReceiveManager(bluetoothAdapter, context)
    }

    @Provides
    @Singleton
    fun provideHeartRateReceiveManager(
        @ApplicationContext context: Context,
        bluetoothAdapter: BluetoothAdapter
    ): HeartRateReceiveManger {
        return HeartRateBLEReceiveManager(bluetoothAdapter, context)
    }

    @Provides
    @Singleton
    fun providePressureReceiveManager(
        @ApplicationContext context: Context,
        bluetoothAdapter: BluetoothAdapter
    ): PressureReceiveManager {
        return PressureBLEReceiveManager(bluetoothAdapter, context)
    }

    @Provides
    @Singleton
    fun provideDataReceiveManager(
        bluetoothAdapter: BluetoothAdapter,
        @ApplicationContext context: Context
    ): DataReceiveManager {
        return DataBLEReceiveManager(bluetoothAdapter, context)
    }
}