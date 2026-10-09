package com.trevor.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE transport and safety layer for T.R.E.V.O.R.
 *
 * Wire protocol (UTF-8, newline terminated):
 *   D,<left -255..255>,<right -255..255>,<sequence>\n
 *   S,<sequence>\n
 * ESP32 notifications: ACK,<sequence>,OK or ACK,<sequence>,ERR.
 */
class TrevorRobotBleController(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class DeviceItem(val device: BluetoothDevice, val name: String, val address: String)

    var onStatus: ((String) -> Unit)? = null
    var onDevices: ((List<DeviceItem>) -> Unit)? = null
    var onTelemetry: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, DeviceItem>()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var scannerCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var writePending = false
    private var linkReady = false
    private var sequence = 0
    private var holdLeft = 0
    private var holdRight = 0
    private var holdActive = false
    private val holdRefresh = object : Runnable {
        override fun run() {
            if (!holdActive || !linkReady) return
            enqueueDrive(holdLeft, holdRight, priority = false)
            handler.postDelayed(this, 150L)
        }
    }
    private var writeTimeout: Runnable? = null

    private val manager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
            postStatus("Bluetooth permission required")
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            postStatus("Turn Bluetooth on, then scan again")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            postStatus("BLE scanner unavailable")
            return
        }
        stopScan()
        found.clear()
        postDevices(emptyList())
        postStatus("Scanning for nearby BLE devices…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = try { device.address } catch (_: SecurityException) { null } ?: return
                val name = try { device.name } catch (_: SecurityException) { null }
                    ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
                found[address] = DeviceItem(device, name, address)
                postDevices(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                postStatus("BLE scan failed ($errorCode)")
            }
        }
        scannerCallback = callback
        try {
            scanner.startScan(callback)
            val timeout = Runnable {
                stopScan()
                postStatus("Scan complete")
            }
            scanTimeout = timeout
            handler.postDelayed(timeout, 10_000L)
        } catch (_: SecurityException) {
            scannerCallback = null
            postStatus("Bluetooth scan permission was revoked")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        val callback = scannerCallback ?: return
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) {}
        scannerCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(item: DeviceItem) {
        if (!hasPermissions()) {
            postStatus("Bluetooth permission required")
            return
        }
        stopScan()
        disconnect()
        postStatus("Connecting to ${item.name}…")
        try {
            gatt = item.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            postStatus("Bluetooth connect permission was revoked")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    postStatus("Connection failed ($status)")
                    closeGatt(g)
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        postStatus("Connected; discovering robot service…")
                        try {
                            if (!g.discoverServices()) {
                                postStatus("Could not start GATT service discovery")
                                closeGatt(g)
                            }
                        } catch (_: SecurityException) {
                            postStatus("Bluetooth permission was revoked")
                            closeGatt(g)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        linkReady = false
                        writeCharacteristic = null
                        notifyCharacteristic = null
                        stopHold()
                        postStatus("Disconnected; motors should stop")
                        closeGatt(g)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val service = g.getService(SERVICE_UUID)
                val rx = service?.getCharacteristic(RX_UUID)
                val tx = service?.getCharacteristic(TX_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                    postStatus("Robot service/characteristics missing")
                    closeGatt(g)
                    return@post
                }
                val cccd = tx.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    postStatus("Robot status notifications are unsupported")
                    closeGatt(g)
                    return@post
                }
                writeCharacteristic = rx
                notifyCharacteristic = tx
                try {
                    if (!g.setCharacteristicNotification(tx, true)) {
                        postStatus("Could not enable robot status notifications")
                        closeGatt(g)
                        return@post
                    }
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    if (!started) {
                        postStatus("Could not subscribe to robot status")
                        closeGatt(g)
                    }
                } catch (_: SecurityException) {
                    postStatus("Bluetooth permission was revoked")
                    closeGatt(g)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt || descriptor.uuid != CCCD_UUID) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    linkReady = true
                    postStatus("Robot link ready")
                    enqueueStop()
                } else {
                    postStatus("Could not subscribe to robot status ($status)")
                    closeGatt(g)
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (g !== gatt) return@post
                writeTimeout?.let(handler::removeCallbacks)
                writeTimeout = null
                writePending = false
                if (status != BluetoothGatt.GATT_SUCCESS) postStatus("BLE command write failed ($status)")
                flushWriteQueue()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != TX_UUID) return
            val message = value.toString(Charsets.UTF_8).trim()
            handler.post {
                if (message.startsWith("ACK,")) postStatus("Robot link ready · $message")
                else onTelemetry?.invoke(message)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val message = characteristic.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
                handler.post {
                    if (message.startsWith("ACK,")) postStatus("Robot link ready · $message")
                    else onTelemetry?.invoke(message)
                }
            }
        }
    }

    /** Starts continuous movement. Call stopDrive() on pointer release/cancel. */
    fun startDrive(left: Int, right: Int) {
        if (!linkReady) {
            postStatus("Connect to a robot first")
            return
        }
        holdLeft = left.coerceIn(-255, 255)
        holdRight = right.coerceIn(-255, 255)
        holdActive = true
        handler.removeCallbacks(holdRefresh)
        enqueueDrive(holdLeft, holdRight, priority = false)
        handler.postDelayed(holdRefresh, 150L)
    }

    fun stopDrive() {
        stopHold()
        enqueueStop()
    }

    private fun stopHold() {
        holdActive = false
        handler.removeCallbacks(holdRefresh)
        holdLeft = 0
        holdRight = 0
    }

    private fun nextSequence(): Int {
        sequence = (sequence + 1) % 1_000_000
        return sequence
    }

    private fun enqueueDrive(left: Int, right: Int, priority: Boolean) {
        val seq = nextSequence()
        enqueue(TrevorRobotProtocol.drive(left, right, seq), priority)
    }

    private fun enqueueStop() {
        val seq = nextSequence()
        enqueue(TrevorRobotProtocol.stop(seq), priority = true)
    }

    private fun enqueue(command: String, priority: Boolean) {
        if (!linkReady || writeCharacteristic == null || gatt == null) {
            if (command.startsWith("S,")) return
            postStatus("Connect to a robot first")
            return
        }
        val bytes = command.toByteArray(Charsets.UTF_8)
        if (priority) {
            writeQueue.clear()
            writeQueue.addFirst(bytes)
        } else {
            if (writeQueue.size >= 8) writeQueue.removeLast()
            writeQueue.addLast(bytes)
        }
        flushWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushWriteQueue() {
        if (writePending || !linkReady || writeQueue.isEmpty()) return
        val activeGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val bytes = writeQueue.removeFirst()
        try {
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }
            if (!started) {
                postStatus("Could not queue robot command")
                if (bytes.firstOrNull() != 'S'.code.toByte()) writeQueue.clear()
                return
            }
            writePending = true
            val timeout = Runnable {
                writePending = false
                writeQueue.clear()
                postStatus("BLE write timed out; stop and reconnect")
                // A fresh STOP is attempted after the queue is reset.
                enqueueStop()
            }
            writeTimeout = timeout
            handler.postDelayed(timeout, 1_200L)
        } catch (_: SecurityException) {
            writePending = false
            writeQueue.clear()
            postStatus("Bluetooth permission was revoked")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        stopHold()
        linkReady = false
        writeQueue.clear()
        writePending = false
        writeTimeout?.let(handler::removeCallbacks)
        writeTimeout = null
        val active = gatt
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (active != null) {
            try { active.disconnect() } catch (_: SecurityException) {}
            try { active.close() } catch (_: SecurityException) {}
        }
    }

    private fun closeGatt(g: BluetoothGatt) {
        if (gatt === g) gatt = null
        linkReady = false
        writeCharacteristic = null
        notifyCharacteristic = null
        writeQueue.clear()
        writePending = false
        writeTimeout?.let(handler::removeCallbacks)
        writeTimeout = null
        try { g.close() } catch (_: SecurityException) {}
    }

    private fun postStatus(message: String) = handler.post { onStatus?.invoke(message) }
    private fun postDevices(items: List<DeviceItem>) = handler.post { onDevices?.invoke(items) }
}
