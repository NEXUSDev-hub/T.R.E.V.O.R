package com.trevor.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

class RobotBleManager(
    context: Context,
    private val listener: Listener
) {
    enum class State {
        DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, READY, CONTROL_ACTIVE
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onRobotFound(device: BluetoothDevice)
        fun onFrame(frame: String)
        fun onError(message: String)
    }

    companion object {
        private val SERVICE = UUID.fromString(RobotBleProtocol.SERVICE_UUID)
        private val RX = UUID.fromString(RobotBleProtocol.RX_UUID)
        private val TX = UUID.fromString(RobotBleProtocol.TX_UUID)
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 10_000L
        private const val GATT_WRITE_TIMEOUT_MS = 3_000L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        @SuppressLint("MissingPermission")
        get() = adapter?.bluetoothLeScanner

    @Volatile var state: State = State.DISCONNECTED
        private set

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanRunning = false
    private var authenticated = false
    private var writePending = false

    private val scanStop = Runnable { stopScanInternal() }
    private val writeTimeout = Runnable {
        if (writePending) {
            writePending = false
            listener.onError("BLE write timeout")
            disconnect()
        }
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            listener.onError("Bluetooth permissions are not granted")
            return
        }
        val bt = adapter
        val bleScanner = scanner
        if (bt == null || bleScanner == null) {
            listener.onError("Bluetooth LE is unavailable")
            return
        }
        if (!bt.isEnabled) {
            listener.onError("Bluetooth is disabled")
            return
        }

        stopScanInternal()
        updateState(State.SCANNING)
        scanRunning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(scanStop, SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            listener.onError("Bluetooth permissions are not granted")
            return
        }
        stopScanInternal()
        disconnect()
        authenticated = false
        updateState(State.CONNECTING)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        handler.removeCallbacks(writeTimeout)
        writePending = false
        authenticated = false
        rxCharacteristic = null
        txCharacteristic = null
        val current = gatt
        gatt = null
        if (current != null) {
            if (hasRequiredPermissions()) {
                @SuppressLint("MissingPermission")
                fun closeGatt() {
                    current.disconnect()
                    current.close()
                }
                closeGatt()
            } else {
                current.close()
            }
        }
        updateState(State.DISCONNECTED)
    }

    fun sendClientReady(): Boolean = send(RobotBleProtocol.CLIENT_READY)

    fun sendHeartbeat(): Boolean =
        authenticated && send(RobotBleProtocol.HEARTBEAT)

    fun sendCommand(direction: String, speed: Int): Boolean =
        authenticated && state == State.CONTROL_ACTIVE &&
            sendBytes(RobotBleProtocol.command(direction, speed))

    fun markAuthenticated() {
        if (state == State.AUTHENTICATING) {
            authenticated = true
            updateState(State.READY)
        }
    }

    fun beginControl() {
        if (authenticated && state == State.READY) {
            updateState(State.CONTROL_ACTIVE)
        }
    }

    fun stopMotion(): Boolean =
        authenticated && sendBytes(RobotBleProtocol.command("STOP", 0))

    @SuppressLint("MissingPermission")
    private fun send(value: String): Boolean = sendBytes(value.toByteArray(Charsets.US_ASCII))

    @SuppressLint("MissingPermission")
    private fun sendBytes(bytes: ByteArray): Boolean {
        if (!hasRequiredPermissions()) return false
        if (gatt == null || rxCharacteristic == null || state == State.DISCONNECTED) return false
        if (writePending) return false

        val characteristic = rxCharacteristic ?: return false
        writePending = true
        handler.removeCallbacks(writeTimeout)
        handler.postDelayed(writeTimeout, GATT_WRITE_TIMEOUT_MS)

        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt!!.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            gatt!!.writeCharacteristic(characteristic)
        }

        if (!accepted) {
            writePending = false
            handler.removeCallbacks(writeTimeout)
        }
        return accepted
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!scanRunning) return
        scanner?.stopScan(scanCallback)
        scanRunning = false
        handler.removeCallbacks(scanStop)
        if (state == State.SCANNING) updateState(State.DISCONNECTED)
    }

    private fun updateState(newState: State) {
        state = newState
        listener.onStateChanged(newState)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            listener.onRobotFound(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanRunning = false
            listener.onError("BLE scan failed: $errorCode")
            updateState(State.DISCONNECTED)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS ||
                newState != android.bluetooth.BluetoothProfile.STATE_CONNECTED
            ) {
                authenticated = false
                rxCharacteristic = null
                txCharacteristic = null
                if (gatt === g) {
                    gatt = null
                    g.close()
                }
                updateState(State.DISCONNECTED)
                listener.onError("BLE connection lost (status $status)")
                return
            }
            updateState(State.AUTHENTICATING)
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("BLE service discovery failed: $status")
                disconnect()
                return
            }
            val service = g.getService(SERVICE)
            rxCharacteristic = service?.getCharacteristic(RX)
            txCharacteristic = service?.getCharacteristic(TX)
            if (rxCharacteristic == null || txCharacteristic == null) {
                listener.onError("TREVOR robot service is incomplete")
                disconnect()
                return
            }

            val tx = txCharacteristic!!
            val notificationEnabled = g.setCharacteristicNotification(tx, true)
            val descriptor = tx.getDescriptor(CCCD)
            if (!notificationEnabled || descriptor == null) {
                listener.onError("Could not enable robot notifications")
                disconnect()
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(descriptor)) {
                listener.onError("Could not subscribe to robot notifications")
                disconnect()
                return
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Robot notification subscription failed: $status")
                disconnect()
                return
            }
            sendClientReady()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != TX) return
            RobotBleProtocol.parseLine(characteristic.value)?.let(listener::onFrame)
        }

        private fun handleCharacteristicWrite(status: Int) {
            writePending = false
            handler.removeCallbacks(writeTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("BLE write failed: $status")
            }
        }

        @Deprecated("Required for Android 12 and below")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicWrite(status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicWrite(status)
        }
    }
}
