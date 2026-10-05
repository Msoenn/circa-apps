package org.circa.companion

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.circa.companion.model.Density
import org.circa.companion.ui.swipeRightBack

/**
 * Flashlight: the whole panel white at maximum window brightness, screen kept on while open. A tap switches
 * white <-> red (night vision). Back or a swipe right exits; the brightness override is only on this window, and
 * is cleared explicitly on stop as well.
 */
class FlashlightActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val systemDensity = LocalDensity.current
            val widthPx = (LocalConfiguration.current.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(Density.densityFor(widthPx), systemDensity.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                var red by rememberSaveable { mutableStateOf(false) }
                var hint by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(2500); hint = false }
                val bg = if (red) androidx.compose.ui.graphics.Color(0xFFFF1A1A) else androidx.compose.ui.graphics.Color.White
                Box(
                    Modifier.fillMaxSize().background(bg).testTag("flashlight")
                        .semantics { testTagsAsResourceId = true }
                        .swipeRightBack { finish() }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { red = !red },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (hint) {
                        Text(
                            if (red) "Tap for white" else "Tap for red", color = androidx.compose.ui.graphics.Color(0xFF1B1B21),
                            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 30.dp),
                        )
                    }
                }
            }
        }
    }

    private fun setBrightness(b: Float) {
        val lp = window.attributes
        lp.screenBrightness = b
        window.attributes = lp
    }

    override fun onStop() {
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
    }
}
