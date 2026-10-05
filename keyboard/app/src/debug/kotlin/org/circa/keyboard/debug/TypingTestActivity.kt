package org.circa.keyboard.debug

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug-only typing test screen (src/debug): one EditText per input type the keyboard handles. Every change
 * is logged as `CircaKbTest: <field>=<text>` so emulator tests can check what arrived without a screenshot.
 * Fields have content descriptions kb_plain, kb_number, kb_password, kb_email, kb_uri, kb_phone, kb_multi.
 */
class TypingTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.widthPixels * 0.16f).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad * 2)
        }
        col.addView(TextView(this).apply {
            text = "Keyboard test"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        })
        fun field(id: String, hint: String, type: Int, action: Int = EditorInfo.IME_ACTION_NEXT) {
            col.addView(EditText(this).apply {
                contentDescription = id
                this.hint = hint
                inputType = type
                imeOptions = action
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 14f
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) { Log.i("CircaKbTest", "$id=$s") }
                })
                setOnEditorActionListener { _, actionId, _ ->
                    Log.i("CircaKbTest", "$id action=$actionId"); false
                }
            })
        }
        field("kb_plain", "Plain text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, EditorInfo.IME_ACTION_SEND)
        field("kb_number", "Number", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        field("kb_password", "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE)
        field("kb_email", "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field("kb_uri", "URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)
        field("kb_phone", "Phone", InputType.TYPE_CLASS_PHONE)
        field("kb_multi", "Multi-line", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
        // Scripted tests wait for "ime=true" before tapping keys (a tap before the IME is up hits a field).
        var last: Boolean? = null
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val shown = insets.isVisible(WindowInsets.Type.ime())
            if (shown != last) { last = shown; Log.i("CircaKbTest", "ime=$shown") }
            v.onApplyWindowInsets(insets)
        }
    }
}
