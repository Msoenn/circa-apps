package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import org.circa.settings.SettingsController
import org.circa.settings.model.SettingsPage

/*
 * Widgets the Wi-Fi / Storage / Date & time pages share (kept out of SettingsScreen.kt so the parallel
 * page work merges cleanly): a ListPage variant with its own title and IME handling, a centred note
 * item, a pill text field on the system IME, the Wi-Fi signal glyph and a few outlined icons.
 */

/**
 * [ListPage] with a caller-chosen [title] (a network name) and, with [ime], the window set to resize
 * for the soft keyboard and the list padded above it, so a focused text field stays visible.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun TitledListPage(
    controller: SettingsController,
    page: SettingsPage,
    title: String = page.title,
    ime: Boolean = false,
    scrollState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    if (ime) AdjustResizeWhileShown()
    PageFrame(controller, page) {
        val focusRequester = remember { FocusRequester() }
        val rotary = RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
        val spec = rememberTransformationSpec()
        ScreenScaffold(
            scrollState = scrollState,
            timeText = { TimeText() },
            modifier = Modifier.fillMaxSize().testTag(settingsPageTag(page)).let { if (ime) it.imePadding() else it },
        ) { padding ->
            PagedPillList(
                scrollState = scrollState,
                contentPadding = PaddingValues(
                    top = (padding.calculateTopPadding() - 14.dp).coerceAtLeast(0.dp),
                    bottom = padding.calculateBottomPadding(),
                ),
                focusRequester = focusRequester,
                rotaryBehavior = rotary,
            ) {
                item(key = "header") {
                    ListHeader(modifier = Modifier.transformedHeight(this, spec)) {
                        Text(
                            title,
                            modifier = Modifier.padding(horizontal = 22.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                content(spec)
            }
        }
    }
}

/**
 * MainActivity draws edge to edge, where the default soft-input mode pans the whole window up under
 * the keyboard (the title and the field leave the round screen). While a page with a text field is
 * shown, ask for resize instead; Compose then reports the keyboard as `WindowInsets.ime`.
 */
@Composable
private fun AdjustResizeWhileShown() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.softInputMode
        @Suppress("DEPRECATION")
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { if (before != null) window.setSoftInputMode(before) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** A centred grey line (empty states, hints, status). */
internal fun TransformingLazyColumnScope.noteItem(
    id: String,
    text: String,
    color: Color? = null,
) = item(key = id) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).testTag(rowTag(id)),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A one-line text field in a row-sized pill, typed on the system IME (no on-screen keyboard of our
 * own). [password] masks the text unless [revealed]; the eye at the end flips [revealed].
 */
@Composable
internal fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    revealed: Boolean = false,
    onToggleReveal: (() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    leadingIcon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .focusRequester(focusRequester)
            // A hardware / injected ENTER (adb `input keyevent 66`, an IME that sends key events
            // instead of an editor action) does what the IME action key does.
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) onImeAction()
                    true
                } else false
            }
            .testTag(tag),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_MIN_HEIGHT)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.surfaceContainer)
                    .padding(start = if (leadingIcon != null) 14.dp else 18.dp, end = if (onToggleReveal != null) 6.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, Modifier.size(20.dp), tint = ROW_ICON_TINT)
                    Box(Modifier.size(8.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    inner()
                }
                if (onToggleReveal != null) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { onToggleReveal() }.testTag("${tag}_reveal"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (revealed) CircaSymbols.Outlined.VisibilityOff else CircaSymbols.Outlined.Visibility,
                            contentDescription = if (revealed) "Hide password" else "Show password",
                            modifier = Modifier.size(22.dp),
                            tint = ROW_ICON_TINT,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Wi-Fi signal glyph: the four-band fan Wear draws, [level] (0..3) bands lit and the rest dim, with a
 * small lock badge for a secured network. -1 = out of range (all dim).
 */
@Composable
internal fun WifiSignalIcon(level: Int, secured: Boolean, tint: Color = ROW_ICON_TINT) {
    Box(Modifier.size(ICON)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val apex = Offset(w / 2f, size.height * 0.86f)
            val rMax = size.height * 0.78f
            // Full fan dim, then the lit part on top: radius grows with the level.
            fun fan(r: Float, color: Color) = drawArc(
                color = color, startAngle = 225f, sweepAngle = 90f, useCenter = true,
                topLeft = Offset(apex.x - r, apex.y - r), size = Size(2 * r, 2 * r),
            )
            fan(rMax, tint.copy(alpha = 0.28f))
            if (level >= 0) fan(rMax * (level + 1) / 4f, tint)
        }
        if (secured) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(Color(0xFF2F3036)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CircaSymbols.Outlined.Lock, contentDescription = "Secured", modifier = Modifier.size(9.dp), tint = tint)
            }
        }
    }
}

