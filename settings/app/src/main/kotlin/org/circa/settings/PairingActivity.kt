package org.circa.settings

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.circa.settings.data.BluetoothData
import org.circa.settings.data.QuickSettings
import org.circa.settings.data.SystemSettings
import org.circa.settings.model.Density
import org.circa.settings.model.Pairing
import org.circa.settings.model.PairingMode
import org.circa.settings.ui.PairingRequest
import org.circa.settings.ui.PairingScreen
import org.circa.settings.ui.Theme

/**
 * The stack's PAIRING_REQUEST arrives here before AOSP Settings sees it (priority 100, ordered
 * broadcast). A CDM-associated device asking only for consent is accepted silently (what Settings'
 * BluetoothPairingRequest does); everything else opens the round [PairingActivity] and the broadcast
 * is aborted so Settings does not also put up its dialog or notification. If the activity can't be
 * started the broadcast goes on to Settings, so a pairing is never left without a prompt.
 */
class PairingRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        val bt = BluetoothData(context)
        val device = pairingDevice(intent, bt) ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
        if (Pairing.mode(variant) == PairingMode.CONSENT && bt.canBondWithoutDialog(device)) {
            bt.confirm(device, true)
            if (isOrderedBroadcast) abortBroadcast()
            return
        }
        val started = runCatching {
            context.startActivity(
                Intent(context, PairingActivity::class.java)
                    .putExtras(intent)
                    .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            true
        }.onFailure { Log.w("CircaBt", "pairing screen", it) }.getOrDefault(false)
        if (started && isOrderedBroadcast) abortBroadcast()
    }
}

/**
 * Round, full-screen pairing prompt (replaces Settings' BluetoothPairingDialog):
 * numeric comparison / consent ask with a cross and a tick, PIN / passkey entry on the round keypad,
 * display-only codes are shown with a cross. Answers go to the stack the way AOSP's
 * BluetoothPairingController does. Finishes when the bond state of the device leaves BONDING, on
 * PAIRING_CANCEL, or after the user's cross / BACK (which cancels the bond).
 */
class PairingActivity : ComponentActivity() {

    private lateinit var bt: BluetoothData
    private val request = mutableStateOf<PairingRequest?>(null)

    /** Set once an answer went out: leaving afterwards must not cancel the bond. */
    private var answered = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (d == null || d.address != request.value?.device?.address) return
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                        answered = true
                        finish()
                    }
                }
                ACTION_PAIRING_CANCEL -> { answered = true; finish() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bt = BluetoothData(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (!take(intent)) { finish(); return }
        registerReceiver(
            bondReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(ACTION_PAIRING_CANCEL)
            },
            RECEIVER_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this) { cancel() }
        val accent = SystemSettings(this, QuickSettings(this)).read().accent
        setContent {
            val configuration = LocalConfiguration.current
            val system = LocalDensity.current
            val widthPx = (configuration.screenWidthDp * system.density).toInt()
            val density = androidx.compose.ui.unit.Density(Density.densityFor(widthPx), system.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                Theme(accent) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { testTagsAsResourceId = true }) {
                        request.value?.let { r ->
                            PairingScreen(r, onAnswer = ::answer, onCancel = ::cancel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new request (another device, or a retry with a new variant) replaces the shown one.
        answered = false
        if (!take(intent)) finish()
    }

    /** Reads a PAIRING_REQUEST's extras; DISPLAY_* variants are acknowledged right away (as AOSP does). */
    private fun take(intent: Intent): Boolean {
        val device = pairingDevice(intent, bt) ?: return false
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
        val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
        val r = PairingRequest(device, bt.labelOf(device), variant, Pairing.formatKey(variant, key))
        request.value = r
        // Settings' notifyDialogDisplayed: tell the stack the code is on screen.
        when (variant) {
            Pairing.DISPLAY_PASSKEY -> bt.confirm(device, true)
            Pairing.DISPLAY_PIN -> r.code?.let { bt.setPin(device, it) }
        }
        return true
    }

    /** The tick / the keypad's submit. [entered] is the typed PIN or passkey (null for confirm). */
    private fun answer(entered: String?) {
        val r = request.value ?: return
        val ok = when (Pairing.mode(r.variant)) {
            PairingMode.CONFIRM_CODE, PairingMode.CONSENT -> bt.confirm(r.device, true)
            PairingMode.ENTER_PIN -> entered != null && bt.setPin(r.device, entered)
            PairingMode.ENTER_PASSKEY -> entered != null && bt.setPasskey(r.device, entered)
            PairingMode.SHOW_CODE, PairingMode.UNSUPPORTED -> false
        }
        Log.i("CircaBt", "pairing answer variant=${r.variant} ok=$ok")
        answered = true
        // Wait for the bond result (the receiver finishes us); fall back to closing if the call failed.
        if (!ok) finish() else request.value = r.copy(waiting = true)
    }

    private fun cancel() {
        val r = request.value
        if (r != null && !answered) {
            if (Pairing.mode(r.variant) == PairingMode.CONFIRM_CODE || Pairing.mode(r.variant) == PairingMode.CONSENT) {
                bt.confirm(r.device, false)
            }
            bt.cancelBond(r.device)
        }
        answered = true
        finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bondReceiver) }
        super.onDestroy()
    }

    private companion object {
        /** BluetoothDevice.ACTION_PAIRING_CANCEL (@hide). */
        const val ACTION_PAIRING_CANCEL = "android.bluetooth.device.action.PAIRING_CANCEL"
    }
}

/**
 * The device of a PAIRING_REQUEST. On debuggable builds only, a test may name it by address
 * ([EXTRA_TEST_ADDRESS]) because `am broadcast` can't carry a BluetoothDevice parcelable; the
 * broadcast is protected and the receiver needs BLUETOOTH_PRIVILEGED, so only root can do that.
 */
internal fun pairingDevice(intent: Intent, bt: BluetoothData): BluetoothDevice? =
    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        ?: if (android.os.Build.TYPE != "user") bt.device(intent.getStringExtra(EXTRA_TEST_ADDRESS)) else null

internal const val EXTRA_TEST_ADDRESS = "org.circa.settings.extra.TEST_ADDRESS"
