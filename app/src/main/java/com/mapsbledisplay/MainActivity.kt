package com.mapsbledisplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mapsbledisplay.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DOT_GREEN = 0xFF2E7D32.toInt()
        private const val DOT_RED = 0xFFC62828.toInt()
    }

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Nur die BLE-Berechtigungen sind Pflicht; POST_NOTIFICATIONS wird
        // mit angefragt, darf aber fehlen (dann fehlt nur die KeepAlive-Notiz).
        if (hasAllPermissions()) {
            KeepAliveService.start(this)
            BleManager.startScanAndConnect()
        } else toast(getString(R.string.perm_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BleManager.init(applicationContext)
        // Ab Android 14 darf der KeepAlive-FGS (Typ connectedDevice) erst
        // starten, wenn Bluetooth erteilt ist -> beim Erststart zuerst die
        // Berechtigungen anfragen, Service startet dann im Launcher-Callback.
        if (hasAllPermissions()) KeepAliveService.start(this)
        else permissionLauncher.launch(requestablePermissions())

        binding.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnConnect.setOnClickListener {
            if (BleManager.state.value == BleManager.State.CONNECTED ||
                BleManager.state.value == BleManager.State.CONNECTING
            ) {
                BleManager.disconnect()
            } else {
                ensurePermissionsThenScan()
            }
        }

        // Testpaket senden (ohne Maps) -> prueft die BLE-Strecke.
        // Icon-Loeschbefehl mitschicken, sonst zeigt das Display ein evtl.
        // noch gespeichertes Maps-Bitmap statt des sauberen Vektorpfeils.
        binding.btnTest.setOnClickListener {
            BleManager.sendRaw("turn-right|200 m|Teststrasse")
            BleManager.sendIcon(byteArrayOf('I'.code.toByte(), 0, 0))
            toast(getString(R.string.test_sent))
        }

        binding.btnHelp.setOnClickListener { showHelpDialog() }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshNotifAccess()
        // Selbstheilung: MIUI wirft den NotificationListener gern raus, ohne
        // ihn neu zu binden. Bei jedem App-Oeffnen einmal Rebind anfordern -
        // ist der Listener schon gebunden, ist das ein No-Op.
        if (isNotificationAccessGranted()) {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, MapsNotificationListenerService::class.java)
            )
        }
    }

    // ----------------------------------------------------------- Berechtigungen
    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** BLE-Pflichtberechtigungen + optionale, die wir mit anfragen. */
    private fun requestablePermissions(): Array<String> =
        requiredBlePermissions() +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private fun hasAllPermissions(): Boolean =
        requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionsThenScan() {
        if (!BleManager.isBluetoothOn()) {
            toast(getString(R.string.bt_off))
            return
        }
        if (hasAllPermissions()) BleManager.startScanAndConnect()
        else permissionLauncher.launch(requestablePermissions())
    }

    // ------------------------------------------------------ Benachrichtigungszugriff
    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /**
     * Schritt 1 ist erst gruen, wenn der Zugriff erteilt ist UND der Dienst
     * tatsaechlich laeuft. Auf MIUI kommt "erteilt, aber nicht gebunden"
     * haeufig vor - genau dann bleibt das Display leer.
     */
    private fun refreshNotifAccess() {
        val granted = isNotificationAccessGranted()
        val bound = MapsNotificationListenerService.connected.value
        binding.tvNotifStatus.text = getString(
            when {
                !granted -> R.string.notif_missing
                !bound -> R.string.notif_granted_not_bound
                else -> R.string.notif_granted
            }
        )
        setDot(binding.dotStep1, granted && bound)
        binding.btnNotifAccess.isEnabled = true
    }

    private fun setDot(dot: android.widget.TextView, ok: Boolean) {
        dot.setTextColor(if (ok) DOT_GREEN else DOT_RED)
    }

    // ----------------------------------------------------------------- Hilfe
    private fun showHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(
                androidx.core.text.HtmlCompat.fromHtml(
                    getString(R.string.help_text),
                    androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --------------------------------------------------------------- UI-Status
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BleManager.state.collect { st ->
                        binding.tvBleStatus.text = getString(
                            when (st) {
                                BleManager.State.DISCONNECTED -> R.string.ble_disconnected
                                BleManager.State.SCANNING -> R.string.ble_scanning
                                BleManager.State.CONNECTING -> R.string.ble_connecting
                                BleManager.State.CONNECTED -> R.string.ble_connected
                            }
                        )
                        binding.btnConnect.text = getString(
                            if (st == BleManager.State.CONNECTED || st == BleManager.State.CONNECTING)
                                R.string.btn_disconnect else R.string.btn_connect
                        )
                        binding.btnTest.isEnabled = st == BleManager.State.CONNECTED
                        setDot(binding.dotStep2, st == BleManager.State.CONNECTED)
                    }
                }
                launch {
                    BleManager.lastSent.collect { payload ->
                        binding.tvLastSent.text = payload?.let {
                            getString(R.string.last_sent, it)
                        } ?: getString(R.string.last_sent_none)
                        setDot(binding.dotStep3, payload != null)
                    }
                }
                launch {
                    // Bind-Status des Listeners live in Schritt 1 spiegeln
                    MapsNotificationListenerService.connected.collect {
                        refreshNotifAccess()
                    }
                }
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
