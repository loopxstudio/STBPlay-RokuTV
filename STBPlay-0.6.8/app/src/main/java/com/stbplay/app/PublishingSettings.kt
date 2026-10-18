package com.stbplay.app

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stbplay.app.data.AppLockStore
import com.stbplay.app.data.Preferences
import com.stbplay.app.data.PinHash
import com.stbplay.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StorageReadyGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val preparation by com.stbplay.app.data.db.ProtectedDatabaseMigration.progress.collectAsState()
    LaunchedEffect(attempt) {
        failed = try { withContext(Dispatchers.IO) {
            // This app no longer schedules guide work. Stop legacy jobs and remove
            // their input database, which could contain old credential-bearing URLs.
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancelAll()
            context.deleteDatabase("androidx.work.workdb")
            listOf("androidx.work.workdb", "androidx.work.workdb-wal", "androidx.work.workdb-shm", "androidx.work.workdb-journal").forEach {
                java.io.File(context.noBackupFilesDir, it).delete()
            }
            Preferences(context)
            AppDatabase.get(context).openHelper.writableDatabase
        }; false } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { true }
        ready = !failed
    }
    if (ready) content()
    else MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (failed) {
                    Text("Saved data could not be opened")
                    Text("Try opening your saved sources again. Your profiles have not been removed.")
                    OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Try again") }
                    Text("If saved data cannot be recovered, resetting removes profiles, history, favorites and PIN.")
                    var confirm by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { confirm = true }) { Text("Reset local data") }
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Reset STBPlay?") },
                        text = { Text("Permanently remove this app's local data? External provider accounts are not deleted.") },
                        confirmButton = { TextButton(onClick = { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData() }) { Text("Reset") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
                } else { CircularProgressIndicator(); Text(preparation) }
            }
        }
    }
}

@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    var policy by remember { mutableStateOf(false) }
    var browserUnavailable by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    InfoPanel("About", "STBPlay ${BuildConfig.VERSION_NAME}\nPublisher: ${BuildConfig.PUBLISHER_NAME}\nSupport: ${BuildConfig.SUPPORT_EMAIL}\n\nDevice support\nAndroid TV and Google TV with remote navigation.\n\nSTBPlay is an independent player. It supplies no channels, subscriptions or content. Use sources you are authorized to access.")
    OutlinedButton(onClick = { policy = true }) { Text("Privacy policy") }
    OutlinedButton(onClick = { licenses = true }) { Text("App Licenses") }
    if (licenses) {
        val body = remember { context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() } }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("App Licenses") },
            text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(scroll).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionDown || it.key == Key.DirectionUp)) {
                    val target = (scroll.value + if (it.key == Key.DirectionDown) 120 else -120).coerceIn(0, scroll.maxValue)
                    if (target != scroll.value) { scope.launch { scroll.animateScrollTo(target) }; true } else false
                } else false
            }.focusable()) { Text(body) } },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
    }
    if (policy) {
        val body = remember {
            context.assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
                .replace("{{publisherName}}", BuildConfig.PUBLISHER_NAME).replace("{{supportEmail}}", BuildConfig.SUPPORT_EMAIL)
        }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { policy = false }, title = { Text("Privacy policy") },
            text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(scroll).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionDown || it.key == Key.DirectionUp)) {
                    val target = (scroll.value + if (it.key == Key.DirectionDown) 120 else -120).coerceIn(0, scroll.maxValue)
                    if (target != scroll.value) { scope.launch { scroll.animateScrollTo(target) }; true } else false
                } else false
            }.focusable()) { Text(body) } },
            confirmButton = { TextButton(onClick = { policy = false }) { Text("Close") } },
            dismissButton = { if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) TextButton(onClick = {
                browserUnavailable = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }.isFailure
            }) { Text("View online") } })
    }
    if (browserUnavailable) Text("No web browser is available. The complete privacy policy is available above.")
}

@Composable
internal fun PrivacyAndDataSettings(viewModel: MainViewModel) {
    val context = LocalContext.current
    var clearData by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local data")
        OutlinedButton(onClick = { clearHistory = true }) { Text("Clear watch history") }
        OutlinedButton(onClick = { clearData = true }) { Text("Clear all local data") }
        if (cleared) Text("Watch history cleared.")
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Clear watch history?") },
        text = { Text("Saved profiles, favorites and your PIN will remain.") },
        confirmButton = { TextButton(onClick = { viewModel.clearWatchHistory(); clearHistory = false; cleared = true }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } })
    if (clearData) {
        val store = remember { AppLockStore(context) }
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { if (!busy) clearData = false }, title = { Text("Clear all local data?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Permanently remove saved profiles, login details, favorites, history, settings and PIN. STBPlay will close. External provider accounts are not deleted.")
                if (store.enabled) SourceTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, { Text("Current PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword, error = error)
                else error?.let { Text(it) }
            } },
            confirmButton = { TextButton(enabled = !busy && (!store.enabled || PinHash.valid(pin)), onClick = {
                busy = true
                scope.launch {
                    val allowed = !store.enabled || withContext(Dispatchers.IO) { store.verify(pin) }
                    if (allowed) (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                    else { error = if (store.waitSeconds() > 0) "Wait ${store.waitSeconds()} seconds before trying again." else "Incorrect PIN."; pin = ""; busy = false }
                }
            }) { Text(if (busy) "Clearing…" else "Clear local data") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { clearData = false }) { Text("Cancel") } })
    }
}
