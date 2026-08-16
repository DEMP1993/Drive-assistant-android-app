package com.mapsbledisplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE-Central: scannt nach dem Drive Assistant (ESP32), verbindet sich und
 * schreibt Navi-Updates in die Charakteristik. Gefunden wird das Geraet
 * ueber die Service-UUID, nicht ueber den Namen.
 *
 * Als Singleton (object) implementiert, damit Activity UND
 * NotificationListenerService dieselbe Verbindung teilen.
 */
@SuppressLint("MissingPermission") // Berechtigungen werden in MainActivity geprueft
object BleManager {

    private const val TAG = "BleManager"

    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NAV_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val ICON_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val MEDIA_CHAR_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    val CMD_CHAR_UUID: UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val DEVICE_NAME = "Drive Assistant"
    private const val SCAN_TIMEOUT_MS = 15_000L

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastSent = MutableStateFlow<String?>(null)
    val lastSent: StateFlow<String?> = _lastSent.asStateFlow()

    private lateinit var appContext: Context
    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var navChar: BluetoothGattCharacteristic? = null
    private var iconChar: BluetoothGattCharacteristic? = null
    private var mediaChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    // Schreib-Koaleszenz: nur den jeweils neuesten Wert senden
    private var pendingPayload: String? = null
    private var pendingIcon: ByteArray? = null
    private var pendingMedia: String? = null
    private var writeInFlight = false

    // Letzter Stand - wird nach einem (Re-)Connect nachgesendet, damit ein
    // zwischenzeitlich neu gestarteter ESP32 nicht leer bleibt.
    private var lastPayload: String? = null
    private var lastIcon: ByteArray? = null
    private var lastMedia: String? = null

    fun init(context: Context) {
        if (!this::appContext.isInitialized) appContext = context.applicationContext
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ---------------------------------------------------------------- Scan
    fun startScanAndConnect() {
        if (_state.value == State.CONNECTED || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "Kein BluetoothLeScanner (Bluetooth aus?)")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _state.value = State.SCANNING
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "Scan gestartet")

        main.postDelayed({
            if (scanning) {
                stopScan()
                if (_state.value == State.SCANNING) _state.value = State.DISCONNECTED
                Log.i(TAG, "Scan-Timeout, nichts gefunden")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            Log.i(TAG, "Gefunden: $name / ${result.device.address}")
            // Service-UUID hat schon gefiltert; Name als zusaetzliche Sicherheit
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan fehlgeschlagen: $errorCode")
            scanning = false
            _state.value = State.DISCONNECTED
        }
    }

    // ------------------------------------------------------------- Connect
    private fun connect(device: BluetoothDevice) {
        _state.value = State.CONNECTING
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    fun disconnect() {
        stopScan()
        navChar = null
        iconChar = null
        mediaChar = null
        cmdChar = null
        writeInFlight = false
        pendingPayload = null
        pendingIcon = null
        pendingMedia = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        _state.value = State.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Verbunden, frage MTU an")
                    g.requestMtu(256)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Getrennt (status=$status)")
                    navChar = null
                    iconChar = null
                    mediaChar = null
                    cmdChar = null
                    writeInFlight = false
                    g.close()
                    if (gatt === g) gatt = null
                    _state.value = State.DISCONNECTED
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU = $mtu, starte Service-Discovery")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(NAV_CHAR_UUID)
            if (ch == null) {
                Log.e(TAG, "Navi-Charakteristik nicht gefunden")
                disconnect()
                return
            }
            navChar = ch
            // optional (aeltere Firmware hat sie nicht)
            iconChar = service.getCharacteristic(ICON_CHAR_UUID)
            if (iconChar == null) Log.w(TAG, "Icon-Charakteristik nicht vorhanden (alte Firmware?)")
            mediaChar = service.getCharacteristic(MEDIA_CHAR_UUID)
            cmdChar = service.getCharacteristic(CMD_CHAR_UUID)
            if (mediaChar == null) Log.w(TAG, "Media-Charakteristik nicht vorhanden (alte Firmware?)")
            _state.value = State.CONNECTED
            Log.i(TAG, "Bereit zum Senden")
            // letzten Stand nachsenden (ESP32 koennte neu gestartet sein)
            if (pendingPayload == null) pendingPayload = lastPayload
            if (pendingIcon == null) pendingIcon = lastIcon
            if (pendingMedia == null) pendingMedia = lastMedia
            // Tasten-Notifications abonnieren; flush() folgt nach dem
            // Descriptor-Write (onDescriptorWrite) bzw. sofort, wenn es
            // keine Kommando-Charakteristik gibt.
            if (!subscribeCmd(g)) flush()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            if (pendingPayload != null || pendingIcon != null || pendingMedia != null) flush()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            writeInFlight = false
            Log.i(TAG, "Kommando-Notifications abonniert (status=$status)")
            flush()
        }

        // Tasten-Kommando vom ESP32 (1 Byte: 'P' / 'N' / 'V')
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == CMD_CHAR_UUID && value.isNotEmpty()) {
                main.post { MediaWatcher.handleCommand(value[0]) }
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                c.uuid == CMD_CHAR_UUID && v.isNotEmpty()
            ) {
                main.post { MediaWatcher.handleCommand(v[0]) }
            }
        }
    }

    /** Abonniert die Kommando-Charakteristik; true = Descriptor-Write laeuft. */
    @Suppress("DEPRECATION")
    private fun subscribeCmd(g: BluetoothGatt): Boolean {
        val ch = cmdChar ?: return false
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD_UUID) ?: return false
        writeInFlight = true
        val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
        if (!ok) writeInFlight = false
        return ok
    }

    // ---------------------------------------------------------------- Senden
    /** Sendet eine Navi-Anweisung (koalesziert: nur der neueste Wert zaehlt). */
    fun send(nav: NavData) = sendRaw(nav.toPayload())

    fun sendRaw(payload: String) {
        pendingPayload = payload
        lastPayload = payload
        if (_state.value == State.CONNECTED) flush()
    }

    /** Sendet die Manoever-Icon-Bitmap (Format siehe PROTOCOL.md). */
    fun sendIcon(data: ByteArray) {
        pendingIcon = data
        lastIcon = data
        if (_state.value == State.CONNECTED) flush()
    }

    /** Sendet den Media-Status ("state|title|artist", siehe PROTOCOL.md). */
    fun sendMedia(payload: String) {
        pendingMedia = payload
        lastMedia = payload
        if (_state.value == State.CONNECTED) flush()
    }

    /** Schreibt ausstehende Werte: erst Navi-Text, dann Icon, dann Media. */
    private fun flush() {
        if (writeInFlight) return
        val g = gatt ?: return

        pendingPayload?.let { payload ->
            val ch = navChar ?: return
            pendingPayload = null
            if (writeChar(g, ch, payload.toByteArray(Charsets.UTF_8))) {
                _lastSent.value = payload
            }
            return
        }
        pendingIcon?.let { data ->
            val ch = iconChar ?: run { pendingIcon = null; return }
            pendingIcon = null
            if (writeChar(g, ch, data)) return
        }
        pendingMedia?.let { payload ->
            val ch = mediaChar ?: run { pendingMedia = null; return }
            pendingMedia = null
            writeChar(g, ch, payload.toByteArray(Charsets.UTF_8))
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        writeInFlight = true
        val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
        if (!ok) {
            writeInFlight = false
            Log.w(TAG, "writeCharacteristic abgelehnt")
        }
        return ok
    }
}
