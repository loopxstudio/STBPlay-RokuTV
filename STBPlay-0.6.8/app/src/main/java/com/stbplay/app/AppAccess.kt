package com.stbplay.app

import android.app.Application
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import com.stbplay.app.data.AppLockStore
import com.stbplay.app.data.PinHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppAccessSession(application: Application) : AndroidViewModel(application) {
    val store = AppLockStore(application)
    var enabled by mutableStateOf(store.enabled)
        private set
    var unlocked by mutableStateOf(false)
    var generation = 0
        private set
    private var pendingSettingsGeneration: Int? = null
    private val settingsObserver = store.observeChanges {
        enabled = store.enabled
        if (!enabled) unlocked = true
        else if (pendingSettingsGeneration != generation) lock()
    }
    fun beginSettingsChange(): Int { pendingSettingsGeneration = generation; return generation }
    fun cancelSettingsChange() { pendingSettingsGeneration = null }
    override fun onCleared() { store.removeObserver(settingsObserver); super.onCleared() }
    fun refreshEnabled() {
        enabled = store.enabled
        if (!enabled) unlocked = true
    }
    fun settingsChanged(allowUnlock: Boolean, expectedGeneration: Int) {
        pendingSettingsGeneration = null
        enabled = store.enabled
        unlocked = !enabled || (allowUnlock && generation == expectedGeneration)
    }
    fun completeUnlock(expectedGeneration: Int, foreground: Boolean): Boolean {
        if (!enabled || generation != expectedGeneration || !foreground) return false
        unlocked = true
        return true
    }
    fun lock() { generation++; pendingSettingsGeneration = null; unlocked = false }
}

@Composable
internal fun AppAccessGate(session: AppAccessSession, content: @Composable () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    DisposableEffect(session.enabled) {
        if (session.enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    if (!session.enabled || session.unlocked) { content(); return }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableStateOf(session.store.waitSeconds()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(session) { while (true) { session.refreshEnabled(); wait = session.store.waitSeconds(); delay(1000) } }
    fun unlock() {
        if (busy || wait > 0 || !PinHash.valid(pin)) return
        busy = true
        val candidate = pin
        val generation = session.generation
        scope.launch {
            val valid = withContext(Dispatchers.IO) { session.store.verify(candidate) }
            pin = ""; busy = false
            if (valid) {
                if (!session.completeUnlock(generation, activity?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true)) {
                    error = "The app locked again. Enter your PIN to continue."
                }
            } else { wait = session.store.waitSeconds(); error = "Incorrect PIN. Please try again." }
        }
    }
    BackHandler { activity?.finish() }
    MaterialTheme(colorScheme = darkColorScheme(primary = AccentBlue, background = Ink, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 400.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("STBPlay is locked", style = MaterialTheme.typography.headlineSmall)
                Text("Enter your PIN to open the app.")
                SourceTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; error = null } },
                    { Text("PIN") }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword,
                    onSubmit = { unlock() }, error = error)
                if (wait > 0) Text("Too many attempts. Try again in $wait seconds.")
                Button(onClick = { unlock() }, enabled = !busy && wait == 0L && PinHash.valid(pin), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Checking…" else "Unlock")
                }
            }
        }
    }
}

@Composable
internal fun ParentalControlSettings() {
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity ?: return
    val session = activity.accessSession
    val store = session.store
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(mode) {
        while (mode != null) { remaining = store.waitSeconds(); delay(1000) }
    }
    fun open(action: String) { currentPin = ""; newPin = ""; confirmPin = ""; error = null; mode = action; remaining = store.waitSeconds() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Parental control", style = MaterialTheme.typography.titleMedium)
        Text(if (session.enabled) "PIN lock is on. Your PIN is required when opening or returning to the app." else "Optional: require a PIN when opening the app. Currently off.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!session.enabled) OutlinedButton(onClick = { open("enable") }) { Text("Set PIN / enable") }
            else {
                OutlinedButton(onClick = { open("change") }) { Text("Change PIN") }
                OutlinedButton(onClick = { open("disable") }) { Text("Disable PIN lock") }
            }
        }
    }
    mode?.let { action ->
        fun save() {
            if (busy || remaining > 0) return
            if (action != "disable" && (!PinHash.valid(newPin) || newPin != confirmPin)) {
                error = "Enter matching PINs with 4 to 6 digits."; return
            }
            busy = true
            val old = currentPin; val replacement = newPin
            val generation = session.beginSettingsChange()
            scope.launch {
                val message = withContext(Dispatchers.IO) {
                    if (action != "enable" && !store.verify(old)) "Incorrect current PIN."
                    else if (action == "disable") { if (store.disable()) null else "The PIN lock could not be disabled." }
                    else { if (store.setPin(replacement)) null else "The PIN could not be saved." }
                }
                busy = false
                if (message == null) {
                    session.settingsChanged(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED), generation)
                    activity.updatePipPlayback(false)
                    currentPin = ""; newPin = ""; confirmPin = ""; mode = null
                } else { session.cancelSettingsChange(); error = message; currentPin = ""; remaining = store.waitSeconds() }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) mode = null },
            title = { Text(when(action) { "enable" -> "Enable PIN lock"; "change" -> "Change PIN"; else -> "Disable PIN lock" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    fun digits(value: String) = value.length <= 6 && value.all { it in '0'..'9' }
                    if (action != "enable") SourceTextField(currentPin, { if (digits(it)) currentPin = it }, { Text("Current PIN") },
                        modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword)
                    if (action != "disable") {
                        SourceTextField(newPin, { if (digits(it)) newPin = it }, { Text("New PIN (4–6 digits)") }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword)
                        SourceTextField(confirmPin, { if (digits(it)) confirmPin = it }, { Text("Confirm new PIN") }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword)
                        Text("Keep your PIN safe. A forgotten PIN requires clearing app data, which also removes saved profiles. Picture-in-picture and screen capture are disabled while PIN lock is on.", color = Muted)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (remaining > 0) Text("Try again in $remaining seconds.")
                }
            },
            confirmButton = { TextButton(onClick = { save() }, enabled = !busy && remaining == 0L && (action == "enable" || PinHash.valid(currentPin))) { Text(if (busy) "Saving…" else if (action == "disable") "Disable" else "Save") } },
            dismissButton = { TextButton(onClick = { mode = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
