package com.stbplay.app.data

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.MainActivity
import com.stbplay.app.SourceTextField
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceFieldNavigationTest {
    @Test fun arrowsSelectAndOkEnterEnableWhileBackFinishesEditing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var firstValue = ""
        var passwordValue = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        var first by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        val focus = remember { FocusRequester() }
                        Column {
                            SourceTextField(first, { first = it; firstValue = it }, { Text("First URL") }, Modifier.focusRequester(focus))
                            SourceTextField(password, { password = it; passwordValue = it }, { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                        }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                }
            }
            fun acceptsInput(): Boolean {
                var accepting = false
                scenario.onActivity { accepting = (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isAcceptingText }
                return accepting
            }
            fun awaitInput(expected: Boolean) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var stableSince = 0L
                while (SystemClock.uptimeMillis() < deadline) {
                    if (acceptsInput() == expected) {
                        if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                        // A closing IME can report false before Compose restores the
                        // navigation target on the next frame. Wait for a stable state.
                        if (SystemClock.uptimeMillis() - stableSince >= 250) return
                    } else stableSince = 0L
                    SystemClock.sleep(20)
                }
                assertEquals("Text input state", expected, acceptsInput())
            }
            instrumentation.waitForIdleSync()
            awaitInput(false)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            awaitInput(false)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitInput(true)
            instrumentation.sendStringSync("abc")
            val firstDeadline = SystemClock.uptimeMillis() + 5_000
            var firstApplied = false
            while (!firstApplied && SystemClock.uptimeMillis() < firstDeadline) {
                scenario.onActivity { firstApplied = firstValue == "abc" }
                SystemClock.sleep(20)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("abc", firstValue); assertEquals("", passwordValue) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // The IME consumes the first Back to hide its window. If it leaves
            // the input connection active, the next Back reaches our edit handler.
            SystemClock.sleep(350)
            if (acceptsInput()) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitInput(false)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            awaitInput(false)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            awaitInput(true)
            instrumentation.sendStringSync("secret")
            val passwordDeadline = SystemClock.uptimeMillis() + 5_000
            var passwordApplied = false
            while (!passwordApplied && SystemClock.uptimeMillis() < passwordDeadline) {
                scenario.onActivity { passwordApplied = passwordValue == "secret" }
                SystemClock.sleep(20)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("abc", firstValue); assertEquals("secret", passwordValue) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // The IME consumes the first Back to hide its window. If it leaves
            // the input connection active, the next Back reaches our edit handler.
            SystemClock.sleep(350)
            if (acceptsInput()) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitInput(false)
        }
    }
    @Test fun tvOnScreenKeyboardAcceptsRepeatedRemoteSelectionsAndDone() = checkKeyboard(KeyEvent.KEYCODE_DPAD_CENTER)

    private fun checkKeyboard(activationKey: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        val settings = ui.serviceInfo
        settings.flags = settings.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = settings
        val old = android.provider.Settings.Secure.getString(context.contentResolver, "show_ime_with_hard_keyboard") ?: "0"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")).bufferedReader().use { it.readText() }
        // Keep the platform keyboard service alive. Force-stopping it here can
        // leave its system input session dead before the test editor is shown.
        var value = ""
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
            assertTrue(message, condition())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    var text by remember { mutableStateOf("") }
                    val focus = remember { FocusRequester() }
                    Column { SourceTextField(text, { text = it; value = it }, { Text("Remote typing test") }, Modifier.focusRequester(focus)) }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                } } }
                instrumentation.waitForIdleSync()
                fun fieldTarget(): android.view.accessibility.AccessibilityNodeInfo? {
                    fun find(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                        if (n.text?.toString() == "Remote typing test") return n
                        for (i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
                        return null
                    }
                    var n = ui.rootInActiveWindow?.let(::find)
                    while (n != null && (!n.isClickable || !n.isEnabled)) n = n.parent
                    return n
                }
                await("Navigation field is available") { fieldTarget() != null }
                assertTrue(fieldTarget()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
                await("Navigation field has focus") { fieldTarget()?.isFocused == true }
                await("Activity window is focused before editing") {
                    var focused = false; scenario.onActivity { focused = it.hasWindowFocus() }; focused
                }
                instrumentation.sendKeyDownUpSync(activationKey)
                fun editing(): Boolean { var active = false; scenario.onActivity { active = (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isAcceptingText }; return active }
                await("Editor accepts input after remote OK") { editing() }
                await("Real on-screen keyboard opens") {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys input_method")).bufferedReader().use { it.readText().contains("mInputShown=true") }
                }
                SystemClock.sleep(300)
                // These are actual remote key events to the IME, not sendStringSync
                // or accessibility setText, which missed the 0.6.7.2 regression.
                repeat(3) { index ->
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Remote selected character ${index+1} reaches the editor") { var length = 0; scenario.onActivity { length = value.length }; length >= index+1 }
                    assertTrue("Selecting a keyboard letter must keep editing active", editing())
                }
                repeat(4) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
                // This TV keyboard wraps horizontally. From the bottom space bar,
                // three rights select Done; extra rights wrap back to other keys.
                repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Remote keyboard Done finishes editing") { !editing() }
                scenario.onActivity { assertTrue(value.length >= 3) }
            }
        } finally { ui.executeShellCommand("settings put secure show_ime_with_hard_keyboard $old").close() }
    }

}
