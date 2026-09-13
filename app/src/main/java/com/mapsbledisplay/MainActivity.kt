package com.mapsbledisplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
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

        /** Kopplungsdialog pro App-Start nur einmal von selbst anbieten. */
        private var pairOffered = false
    }

    private lateinit var binding: ActivityMainBinding
    /** Auto-Verbinden in dieser Activity schon scharf geschaltet? */
    private var autoArmed = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Nur die BLE-Berechtigungen sind Pflicht; POST_NOTIFICATIONS wird
        // mit angefragt, darf aber fehlen (dann fehlt nur die Dienst-Notiz).
        if (hasAllPermissions()) {
            armAutoConnect()
            // Android 10/11: Hintergrund-Standort fuer Scan-Ergebnisse im
            // Hintergrund muss separat angefragt werden.
            if (Permissions.needsBackgroundLocation(this)) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else toast(getString(R.string.perm_denied))
    }

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { BackgroundScan.start(this) }

    // System-Kopplungsdialog (Companion Device Manager)
    private val pairLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        CompanionPairing.finish(this)
        refreshPairUi()
        if (result.resultCode == Activity.RESULT_OK && CompanionPairing.isPaired(this)) {
            toast(getString(R.string.pair_done))
        }
        armAutoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BleManager.init(applicationContext)
        CompanionPairing.ensureObserving(this)
        binding.btnPair.setOnClickListener {
            if (!BleManager.isBluetoothOn()) toast(getString(R.string.bt_off))
            else if (!hasAllPermissions()) permissionLauncher.launch(requestablePermissions())
            else startPairing()
        }
        // Erststart: die Ersteinrichtung fuehrt durch Berechtigungen und Kopplung
        // (keine eigenen Dialoge hier, sonst ueberlagern sie sich). Danach:
        // Berechtigungen anfragen bzw. Auto-Verbinden scharf schalten.
        if (!SetupActivity.isDone(this)) {
            pairOffered = true
            SetupActivity.start(this)
        } else if (hasAllPermissions()) {
            autoArmed = true
            armAutoConnect()
        } else permissionLauncher.launch(requestablePermissions())

        binding.btnNotifAccess.setOnClickListener { RestrictedSettings.openNotificationAccess(this) }
        binding.btnRestricted.setOnClickListener { RestrictedSettings.openAppInfo(this) }
        binding.btnAutostart.setOnClickListener { XiaomiAutostart.openSettings(this) }

        binding.btnConnect.setOnClickListener {
            if (BleManager.state.value == BleManager.State.CONNECTED ||
                BleManager.state.value == BleManager.State.CONNECTING
            ) {
                // Manuell trennen = Auto-Verbinden pausieren, bis die App
                // wieder geoeffnet oder "verbinden" gedrueckt wird.
                DeviceService.stop(this)
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

        binding.btnMenu.setOnClickListener { view ->
            androidx.appcompat.widget.PopupMenu(this, view).apply {
                menu.add(0, 1, 0, R.string.menu_setup)
                menu.add(0, 2, 1, R.string.menu_help)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> SetupActivity.start(this@MainActivity)
                        2 -> showHelpDialog()
                    }
                    true
                }
            }.show()
        }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        // Zurueck aus der Ersteinrichtung: jetzt Auto-Verbinden scharf schalten
        if (!autoArmed && SetupActivity.isDone(this) && hasAllPermissions()) {
            autoArmed = true
            armAutoConnect()
        }
        refreshNotifAccess()
        refreshPairUi()
        refreshAutostart()
        // Selbstheilung: Listener nach einem Prozess-Kill neu binden, ohne dass
        // der Nutzer den Zugriff aus- und einschalten muss (siehe ListenerRebind).
        ListenerRebind.healSoon(this)
    }

    /** Xiaomi: Hinweis + Knopf, solange Autostart verweigert ist. */
    private fun refreshAutostart() {
        val denied = XiaomiAutostart.isAllowed(this) == false
        binding.tvAutostart.isVisible = denied
        binding.btnAutostart.isVisible = denied
    }

    // ------------------------------------------------------------ Auto-Connect
    /**
     * Hintergrund-Scan registrieren (verbindet, sobald das Geraet eingeschaltet
     * wird) und - wenn Bluetooth an ist - sofort einmal aktiv suchen, damit
     * ein bereits laufendes Geraet ohne Wartezeit verbunden wird.
     */
    private fun armAutoConnect() {
        BackgroundScan.start(this)
        // Noch nicht gekoppelt: einmal die Kopplung anbieten, BEVOR verbunden
        // wird - verbunden advertised das Geraet nicht und der Dialog faende es nicht.
        if (needsPairing() && !pairOffered && BleManager.isBluetoothOn()) {
            pairOffered = true
            startPairing()
            return
        }
        if (CompanionPairing.inProgress) return
        if (BleManager.isBluetoothOn() &&
            BleManager.state.value == BleManager.State.DISCONNECTED
        ) BleManager.startScanAndConnect()
    }

    // ---------------------------------------------------------------- Koppeln
    private fun needsPairing() =
        CompanionPairing.isSupported(this) && !CompanionPairing.isPaired(this)

    private fun startPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        pairOffered = true
        CompanionPairing.start(
            this,
            onDialog = { sender ->
                pairLauncher.launch(IntentSenderRequest.Builder(sender).build())
            },
            onFailed = {
                CompanionPairing.finish(this)
                toast(getString(R.string.pair_failed))
                refreshPairUi()
                armAutoConnect()
            }
        )
    }

    private fun refreshPairUi() {
        val show = needsPairing()
        binding.btnPair.isVisible = show
        binding.tvPairHint.isVisible = show
    }

    // ----------------------------------------------------------- Berechtigungen
    private fun requiredBlePermissions(): Array<String> = Permissions.requiredBle()

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
        if (hasAllPermissions()) armAutoConnect()
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
        // Browser-Installation: Android sperrt den Zugriff -> Anleitung zur Freigabe,
        // sobald der Nutzer es einmal versucht hat (erst dann bietet Android sie an)
        val restricted = !granted && RestrictedSettings.tried(this) &&
            RestrictedSettings.isRestricted(this)
        binding.tvRestricted.isVisible = restricted
        // Xiaomi/HyperOS: Schalter ganz unten in der App-Info, sonst Menue oben rechts
        if (restricted) binding.tvRestricted.setText(
            if (XiaomiAutostart.isXiaomi()) R.string.restricted_hint_xiaomi else R.string.restricted_hint
        )
        binding.btnRestricted.isVisible = restricted
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
                    BleManager.state.collect { st -> refreshBleStatus(st) }
                }
                launch {
                    BackgroundScan.active.collect { refreshBleStatus(BleManager.state.value) }
                }
                launch {
                    BleManager.state.collect { st ->
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

    private fun refreshBleStatus(st: BleManager.State) {
        binding.tvBleStatus.text = getString(
            when (st) {
                BleManager.State.DISCONNECTED ->
                    if (BackgroundScan.active.value) R.string.ble_auto_armed
                    else R.string.ble_disconnected
                BleManager.State.SCANNING -> R.string.ble_scanning
                BleManager.State.CONNECTING -> R.string.ble_connecting
                BleManager.State.CONNECTED -> R.string.ble_connected
            }
        )
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
