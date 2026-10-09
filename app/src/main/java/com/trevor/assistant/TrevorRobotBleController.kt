package com.trevor.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE transport for the T.R.E.V.O.R. robot.
 *
 * Protocol: Nordic-UART-style GATT service. Commands are single ASCII letters:
 * F=forward, B=back, L=left, R=right, S=stop. The ESP32 firmware must expose
 * the same UUIDs and implement a command timeout that stops the motors.
 */
class TrevorRobotBleController(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }

    data class DeviceItem(val device: BluetoothDevice, val name: String, val address: String)

    var onStatus: ((String) -> Unit)? = null
    var onDevices: ((List<DeviceItem>) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, DeviceItem>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scannerCallback: ScanCallback? = null

    private val manager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasPermissions()) {
            onStatus?.invoke("Bluetooth permission required")
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus?.invoke("Turn Bluetooth on, then scan again")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus?.invoke("BLE scanner unavailable")
            return
        }
        stopScan()
        found.clear()
        onDevices?.invoke(emptyList())
        onStatus?.invoke("Scanning for nearby BLE devices…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
                found[address] = DeviceItem(device, name, address)
                onDevices?.invoke(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                onStatus?.invoke("BLE scan failed ($errorCode)")
            }
        }
        scannerCallback = callback
        scanner.startScan(callback)
        handler.postDelayed({ stopScan(); onStatus?.invoke("Scan complete") }, 10_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scannerCallback ?: return
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) {}
        scannerCallback = null
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    fun connect(item: DeviceItem) {
        if (!hasPermissions()) {
            onStatus?.invoke("Bluetooth permission required")
            return
        }
        stopScan()
        disconnect()
        onStatus?.invoke("Connecting to ${item.name}…")
        gatt = item.device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus?.invoke("Connection failed ($status)")
                    closeGatt(g)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        onStatus?.invoke("Connected; discovering robot service…")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        writeCharacteristic = null
                        onStatus?.invoke("Disconnected")
                        closeGatt(g)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service: BluetoothGattService? = g.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(RX_UUID)
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    writeCharacteristic = characteristic
                    onStatus?.invoke("Robot link ready")
                    send("S")
                } else {
                    onStatus?.invoke("Connected, but robot GATT service was not found")
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) onStatus?.invoke("Command write failed")
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun send(command: String) {
        if (!hasPermissions()) {
            onStatus?.invoke("Bluetooth permission required")
            return
        }
        val characteristic = writeCharacteristic
        val activeGatt = gatt
        if (characteristic == null || activeGatt == null) {
            onStatus?.invoke("Connect to a robot first")
            return
        }
        val bytes = (command.take(1) + "\n").toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = activeGatt.writeCharacteristic(
                characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (result != BluetoothGatt.GATT_SUCCESS) onStatus?.invoke("Could not queue command")
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (!activeGatt.writeCharacteristic(characteristic)) onStatus?.invoke("Could not queue command")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        val active = gatt
        gatt = null
        writeCharacteristic = null
        if (active != null) {
            try { active.disconnect() } catch (_: SecurityException) {}
            try { active.close() } catch (_: SecurityException) {}
        }
    }

    private fun closeGatt(g: BluetoothGatt) {
        if (gatt === g) gatt = null
        writeCharacteristic = null
        try { g.close() } catch (_: SecurityException) {}
    }
}
