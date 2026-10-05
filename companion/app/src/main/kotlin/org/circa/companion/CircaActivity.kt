package org.circa.companion

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.wear.compose.material3.AppScaffold
import org.circa.companion.model.Accent
import org.circa.companion.model.Density
import org.circa.companion.ui.Theme
import org.circa.companion.ui.swipeRightBack

/**
 * Base of the Companion apps (Media, Weather, Agenda, Find phone): black round panel, the Circa accent
 * (Settings.Secure circa_accent_color), the 200 dp logical width every Circa app uses, swipe right = back.
 */
abstract class CircaActivity : ComponentActivity() {
    @Composable protected abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        setContent {
            val systemDensity = LocalDensity.current
            val widthPx = (LocalConfiguration.current.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(Density.densityFor(widthPx), systemDensity.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                Theme(accent()) {
                    AppScaffold(timeText = {}) {
                        Box(
                            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                                .semantics { testTagsAsResourceId = true }
                                .swipeRightBack { onBackPressedDispatcher.onBackPressed() },
                        ) { Content() }
                    }
                }
            }
        }
    }

    private fun accent(): Accent {
        val argb = runCatching { android.provider.Settings.Secure.getInt(contentResolver, "circa_accent_color") }.getOrNull()
        return Accent.fromArgb(argb) ?: Accent.DEFAULT
    }

    protected fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
