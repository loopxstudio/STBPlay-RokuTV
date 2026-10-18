package com.stbplay.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.*
import com.stbplay.app.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishingSecurityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun node(text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (if (contains) n.text?.contains(text) == true else n.text?.toString() == text) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun click(text: String) {
        fun actionable(): AccessibilityNodeInfo? { var target = node(text); while (target != null && (!target.isEnabled || !target.isClickable)) target = target.parent; return target }
        await("Clickable $text") { actionable() != null }
        assertTrue(actionable()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    @Test fun protectedStorageMigratesLegacyValuesAndRejectsTampering() = runBlocking<Unit> {
        val prefName = "legacy_secure_test"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${prefName}_$name", mode)
        }
        val raw = isolated.getSharedPreferences("stbplay_preferences", Context.MODE_PRIVATE)
        raw.edit().clear().putString("xtream_password", "LEGACY_SUPER_SECRET_0672").commit()
        val prefs = Preferences(isolated)
        assertEquals("LEGACY_SUPER_SECRET_0672", prefs.xtreamCredentials().password)
        assertTrue(SecureValues.encrypted(raw.getString("xtream_password", "")!!))
        val one = SecureValues.encrypt("secret"); val two = SecureValues.encrypt("secret")
        assertNotEquals(one, two); assertEquals("secret", SecureValues.decrypt(one))
        val bytes = android.util.Base64.decode(one.substringAfter(':'), android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = one.substringBefore(':') + ":" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { SecureValues.decrypt(tampered) }
        val name = "legacy-protected-0672.db"
        context.deleteDatabase(name)
        val old = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val id = old.playlistDao().insertStored(PlaylistEntity(name = "Legacy", protocol = SourceProtocol.XTREAM,
            url = "https://provider.invalid", usernameOrMac = "legacy_user", password = "LEGACY_SUPER_SECRET_0672"))
        old.mediaDao().insertAllStored(listOf(MediaEntity("live-1", id, "News", MediaKind.LIVE, "https://provider.invalid/SECRET_STREAM_0672", "News")))
        old.favoriteDao().saveStored(listOf(FavoriteEntity(id, "live-1", "SECRET_FAVORITE_PAYLOAD_0672")))
        old.close()
        val updated = Room.databaseBuilder(context, AppDatabase::class.java, name).addCallback(ProtectedDatabaseMigration).build()
        try {
            assertEquals("LEGACY_SUPER_SECRET_0672", updated.playlistDao().getPlaylistById(id)!!.password)
            assertTrue(SecureValues.encrypted(updated.playlistDao().getAllPlaylistsStored().first().single().password))
            assertTrue(SecureValues.encrypted(updated.mediaDao().getAllStored(id).first().single().streamUrl))
            assertEquals("https://provider.invalid/SECRET_STREAM_0672", updated.mediaDao().getAll(id).first().single().streamUrl)
            assertTrue(SecureValues.encrypted(updated.favoriteDao().getSavedStored(id).single().payload))
            assertEquals("SECRET_FAVORITE_PAYLOAD_0672", updated.favoriteDao().getSaved(id).single().payload)
        } finally { updated.close() }
        assertFalse(String(context.getDatabasePath(name).readBytes(), Charsets.ISO_8859_1).contains("LEGACY_SUPER_SECRET_0672"))
        context.deleteDatabase(name); raw.edit().clear().commit()
    }
    @Test fun deletingASourceAlsoForgetsItsLastCredentialsAndHistory() = runBlocking<Unit> {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("forget0672_$name", mode)
        }
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        val prefs = Preferences(isolated)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore(); lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), prefs, db); store.put("test", vm) }
        try {
            val source = PlaylistEntity(name = "Private", protocol = SourceProtocol.XTREAM, url = "http://provider.invalid", usernameOrMac = "user", password = "private", isActive = true)
            val id = db.playlistDao().insert(source)
            await("Active profile restored") { var ok = false; main { ok = vm.activePlaylist?.id == id }; ok }
            val key = Preferences.sourceKey("XTREAM", source.url, source.usernameOrMac)
            prefs.recordPlayback(key, MediaItem("movie-1", "Movie", "http://provider.invalid/user/private", kind = MediaKind.MOVIE), 1000, 10000)
            assertEquals("private", prefs.xtreamCredentials().password)
            main { vm.deletePlaylist(source.copy(id = id)) }
            await("Source removed") { runBlocking { db.playlistDao().getPlaylistById(id) == null } && prefs.xtreamCredentials().password.isEmpty() }
            assertTrue(prefs.watchHistory(key).isEmpty())
        } finally { main { store.clear() }; db.close() }
    }
    @Test fun aboutContainsPublisherAndPrivacyAndDataResetRequiresPin() {
        val lock = AppLockStore(context); lock.disable(); assertTrue(lock.setPin("1234"))
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore(); lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), Preferences(context), db); store.put("test", vm) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { MaterialTheme { androidx.compose.foundation.layout.Column { AboutSettings() } } } }
                await("Publisher appears") { node("Krishnendu Patra", true) != null && node("krsptrbeez@gmail.com", true) != null }
                click("Privacy policy")
                await("Policy accessible in app") { node("STBPlay Privacy Policy", true) != null }
                click("Close")
                scenario.onActivity { it.setContent { MaterialTheme { PrivacyAndDataSettings(vm) } } }
                click("Clear all local data")
                await("Protected reset asks for current PIN") { node("Current PIN") != null }
                click("Current PIN")
                await("PIN editor focused") { instrumentation.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true }
                val editor = instrumentation.uiAutomation.rootInActiveWindow!!.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)!!
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0000") }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                instrumentation.waitForIdleSync(); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click("Clear local data")
                await("Wrong PIN cannot erase app data") { node("Incorrect PIN.") != null }
                assertTrue(lock.enabled)
                click("Cancel")
                assertTrue(lock.enabled)
            }
        } finally { lock.disable(); main { store.clear() }; db.close() }
    }
    @Test fun tvOnlyManifestExcludesBackupPipAndLegacyBackgroundWork() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS or PackageManager.GET_PERMISSIONS)
        assertTrue(info.reqFeatures!!.any { it.name == "android.software.leanback" && it.flags and FeatureInfo.FLAG_REQUIRED != 0 })
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        val banner = context.getDrawable(com.stbplay.app.R.drawable.stbplay_tv_banner)!!
        val scale = context.resources.displayMetrics.density
        assertEquals((160 * scale).toInt(), banner.intrinsicWidth)
        assertEquals((90 * scale).toInt(), banner.intrinsicHeight)
    }
}
