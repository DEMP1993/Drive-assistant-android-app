package com.mapsbledisplay

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import com.mapsbledisplay.databinding.ActivitySetupBinding

/**
 * Ersteinrichtung: fuehrt Schritt fuer Schritt durch alles, was die App
 * braucht. Erscheint beim ersten Start von selbst und laesst sich spaeter
 * ueber das Menue (⋮) der Hauptseite erneut starten.
 *
 * Jeder Schritt prueft seinen Zustand bei jedem Zurueckkehren (onResume) neu:
 * erledigt -> "Weiter", offen -> Aktions-Knopf + "Ueberspringen".
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "setup"
        private const val KEY_DONE = "wizard_done"
        private const val KEY_INDEX = "index"
        private const val COLOR_OK = 0xFF30D158.toInt()
        private const val COLOR_OPEN = 0xFFC62828.toInt()

        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

        fun start(context: Context) =
            context.startActivity(Intent(context, SetupActivity::class.java))
    }

    private enum class Step { WELCOME, BLUETOOTH, NOTIF, AUTOSTART, PAIR, BATTERY, DONE }

    private lateinit var b: ActivitySetupBinding
    private lateinit var steps: List<Step>
    private var index = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (Permissions.hasBle(this)) BackgroundScan.start(this)
        render()
    }

    private val pairLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        CompanionPairing.finish(this)
        render()
    }

    private val simpleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        BleManager.init(applicationContext)

        steps = buildList {
            add(Step.WELCOME)
            add(Step.BLUETOOTH)
            add(Step.NOTIF)
            if (XiaomiAutostart.isXiaomi()) add(Step.AUTOSTART)
            add(Step.PAIR)
            add(Step.BATTERY)
            add(Step.DONE)
        }
        index = savedInstanceState?.getInt(KEY_INDEX) ?: 0

        b.btnNext.setOnClickListener { next() }
        b.btnSkip.setOnClickListener { next() }
        b.btnBack.setOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    private fun next() {
        if (index < steps.lastIndex) {
            index++
            render()
        } else finishSetup()
    }

    private fun back() {
        if (index > 0) {
            index--
            render()
        } else finishSetup()
    }

    /** Einmal durchlaufen (oder bewusst verlassen) = kein automatischer Start mehr. */
    private fun finishSetup() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        finish()
    }

    // ------------------------------------------------------------------ Anzeige
    private fun render() {
        val step = steps[index]
        b.tvProgress.text = getString(R.string.setup_progress, index + 1, steps.size)
        b.btnBack.isVisible = index > 0
        b.tvExtra.isVisible = false
        b.tvExtra.setTextColor(COLOR_OPEN)
        b.btnExtra.isVisible = false
        b.btnAction.isVisible = true

        when (step) {
            Step.WELCOME -> {
                show(R.string.setup_welcome_title, R.string.setup_welcome_body, done = null)
                b.btnAction.isVisible = false
                b.btnNext.setText(R.string.setup_start)
            }
            Step.BLUETOOTH -> {
                show(R.string.setup_bt_title, R.string.setup_bt_body, Permissions.hasBle(this))
                action(R.string.setup_bt_action) {
                    permissionLauncher.launch(Permissions.requestable())
                }
            }
            Step.NOTIF -> {
                val granted = NotificationManagerCompat.getEnabledListenerPackages(this)
                    .contains(packageName)
                val restricted = RestrictedSettings.isRestricted(this)
                val wasBlocked = RestrictedSettings.wasBlocked(this)
                show(
                    R.string.setup_notif_title,
                    // Browser-Installation: Sperre schon VOR dem ersten Versuch erklaeren
                    if (!granted && (restricted || wasBlocked)) R.string.setup_notif_restricted_body
                    else R.string.setup_notif_body,
                    granted
                )
                when {
                    granted -> action(R.string.btn_notif_access) {
                        RestrictedSettings.openNotificationAccess(this)
                    }
                    // Teil 1: einmal gegen die Sperre laufen - erst danach bietet
                    // Android den Freigabe-Schalter an
                    restricted && !RestrictedSettings.tried(this) ->
                        action(R.string.setup_notif_try) { RestrictedSettings.openNotificationAccess(this) }
                    // Teil 2: Freigabe erteilen
                    restricted -> {
                        action(R.string.btn_restricted) { RestrictedSettings.openAppInfo(this) }
                        b.tvExtra.setText(
                            if (XiaomiAutostart.isXiaomi()) R.string.setup_unlock_xiaomi
                            else R.string.setup_unlock_other
                        )
                        b.tvExtra.isVisible = true
                        b.btnExtra.setText(R.string.setup_notif_try_again)
                        b.btnExtra.setOnClickListener { RestrictedSettings.openNotificationAccess(this) }
                        b.btnExtra.isVisible = true
                    }
                    // Teil 3 (Freigabe erkannt) bzw. normaler Weg: Zugriff einschalten
                    else -> {
                        action(R.string.btn_notif_access) { RestrictedSettings.openNotificationAccess(this) }
                        if (wasBlocked) {
                            b.tvExtra.setText(R.string.setup_unlock_done)
                            b.tvExtra.setTextColor(0xFF30D158.toInt())
                            b.tvExtra.isVisible = true
                        }
                    }
                }
            }
            Step.AUTOSTART -> {
                show(
                    R.string.setup_autostart_title, R.string.setup_autostart_body,
                    XiaomiAutostart.isAllowed(this) != false
                )
                action(R.string.btn_autostart) { XiaomiAutostart.openSettings(this) }
            }
            Step.PAIR -> renderPair()
            Step.BATTERY -> {
                val pm = getSystemService(PowerManager::class.java)
                show(
                    R.string.setup_battery_title,
                    // HyperOS oeffnet statt des Android-Dialogs eine eigene Akku-Seite
                    if (XiaomiAutostart.isXiaomi()) R.string.setup_battery_body_xiaomi
                    else R.string.setup_battery_body,
                    pm.isIgnoringBatteryOptimizations(packageName)
                )
                action(R.string.setup_battery_action) { requestBatteryExemption() }
            }
            Step.DONE -> {
                show(R.string.setup_done_title, R.string.setup_done_body, done = null)
                val connected = BleManager.state.value == BleManager.State.CONNECTED
                b.btnAction.isVisible = connected
                action(R.string.btn_test) {
                    BleManager.sendRaw("turn-right|200 m|Teststrasse")
                    BleManager.sendIcon(byteArrayOf('I'.code.toByte(), 0, 0))
                    Toast.makeText(this, R.string.test_sent, Toast.LENGTH_SHORT).show()
                }
                b.btnNext.setText(R.string.setup_finish)
            }
        }
    }

    private fun renderPair() {
        if (!CompanionPairing.isSupported(this)) {
            // Android 11 und aelter: keine Kopplung, einfach einmal verbinden
            val connected = BleManager.state.value == BleManager.State.CONNECTED
            show(R.string.setup_pair_title, R.string.setup_connect_body, connected)
            action(R.string.btn_connect) {
                if (BleManager.isBluetoothOn()) BleManager.startScanAndConnect()
                else Toast.makeText(this, R.string.bt_off, Toast.LENGTH_SHORT).show()
            }
            return
        }
        show(R.string.setup_pair_title, R.string.setup_pair_body, CompanionPairing.isPaired(this))
        action(R.string.setup_pair_action) { startPairing() }
    }

    /**
     * Titel, Text und Status setzen. done == null: Schritt ohne Pruefung
     * (Willkommen/Fertig) -> nur "Weiter".
     */
    private fun show(title: Int, body: Int, done: Boolean?) {
        b.tvTitle.setText(title)
        b.tvBody.setText(body)
        b.tvStatus.isVisible = done != null
        if (done != null) {
            b.tvStatus.setText(if (done) R.string.setup_status_done else R.string.setup_status_open)
            b.tvStatus.setTextColor(if (done) COLOR_OK else COLOR_OPEN)
        }
        val finished = done != false
        b.btnNext.isVisible = finished
        b.btnNext.setText(R.string.setup_next)
        b.btnSkip.isVisible = !finished
    }

    private fun action(text: Int, onClick: () -> Unit) {
        b.btnAction.setText(text)
        b.btnAction.setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- Aktionen
    @SuppressLint("MissingPermission") // nur mit erteilter Bluetooth-Berechtigung erreichbar
    private fun startPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!Permissions.hasBle(this)) {
            permissionLauncher.launch(Permissions.requestable())
            return
        }
        if (!BleManager.isBluetoothOn()) {
            simpleLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        CompanionPairing.start(
            this,
            onDialog = { pairLauncher.launch(IntentSenderRequest.Builder(it).build()) },
            onFailed = {
                CompanionPairing.finish(this)
                Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show()
                render()
            }
        )
    }

    @SuppressLint("BatteryLife") // Begleit-App fuer ein Geraet: Ausnahme ist hier gewollt
    private fun requestBatteryExemption() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            simpleLauncher.launch(request)
        } catch (e: Exception) {
            simpleLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
