package com.stbplay.app.data

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Black-box UI smoke; minified installations are also checked independently with adb/UI Automator. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun node(text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (if (contains) n.text?.contains(text) == true else n.text?.toString() == text) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun click(text: String) {
        fun target(): AccessibilityNodeInfo? { var n = node(text); while (n != null && (!n.isClickable || !n.isEnabled)) n = n.parent; return n }
        await("Clickable $text") { target() != null }
        assertTrue(target()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun scroll() {
        fun visit(n: AccessibilityNodeInfo): Boolean {
            if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { if (visit(it)) return true }
            return false
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
    }
    @Test fun launchAboutAndPrivacyWorkWithoutPrivateAppApis() {
        val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName, "${context.packageName}.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<Activity>(intent).use {
            await("Main app is ready") { node("Settings") != null }
            click("Settings")
            repeat(6) { if (node("Privacy policy")?.isVisibleToUser != true) scroll() }
            assertNotNull(node("Krishnendu Patra", true))
            assertNotNull(node("krsptrbeez@gmail.com", true))
            assertNull(node("Playback & Anti-Buffering"))
            assertNull(node("TV Guide"))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "settings-0672.png").outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            }
            click("Privacy policy")
            await("Built-in privacy policy opens") { node("STBPlay Privacy Policy", true) != null }
            click("Close")
            assertEquals("0.6.8", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            if (InstrumentationRegistry.getArguments().getString("release") == "true") {
                assertEquals("Release is not debuggable", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
            }
        }
    }
}
