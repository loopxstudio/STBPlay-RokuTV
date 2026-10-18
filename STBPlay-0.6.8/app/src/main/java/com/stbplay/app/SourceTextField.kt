package com.stbplay.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation

/** Remote focus selects the field; activating it explicitly starts text input. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SourceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    error: String? = null,
    onSubmit: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Unspecified
) {
    var editing by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf(false) }
    var restoreNavigationFocus by remember { mutableStateOf(false) }
    var finishedAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val navigationFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun finishEditing(restoreFocus: Boolean = true) {
        restoreNavigationFocus = restoreFocus
        finishedAt = android.os.SystemClock.uptimeMillis()
        editing = false
        if (restoreFocus) focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            keyboard?.show()
        } else if (restoreNavigationFocus) {
            navigationFocus.requestFocus()
            restoreNavigationFocus = false
        }
    }
    BackHandler(enabled = editing) { finishEditing() }

    if (editing) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, label = label,
            singleLine = singleLine, visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = keyboardType),
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            keyboardActions = KeyboardActions(onDone = { finishEditing(); onSubmit?.invoke() }),
            modifier = modifier.focusRequester(editorFocus)
                // Keep the edit session while the TV keyboard takes focus.
                // OK/Enter have no app-level exit shortcut; Done or Back finishes it.

        )
    } else {
        // A disabled visual field cannot start an input session. Its clickable parent is
        // the single remote-focus target and handles OK/Enter as well as touch/click.
        Box(modifier.focusRequester(navigationFocus)
            .onFocusChanged { highlighted = it.isFocused }
            .onPreviewKeyEvent {
                val activation = it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.DirectionCenter
                if (activation) {
                    // Consume the entire activation sequence ourselves. An IME Done may
                    // finish editing on key-down and then dispatch its release here.
                    // Only a fresh press, started after that finish, may reopen it.
                    if (it.type == KeyEventType.KeyUp && it.nativeKeyEvent.downTime > finishedAt) {
                        editing = true
                    }
                    true
                } else false
            }
            .clickable(role = Role.Button, onClickLabel = "Edit field") {
                editing = true
            }) {
            OutlinedTextField(
                value = value, onValueChange = {}, label = label,
                enabled = false, singleLine = singleLine,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = LocalContentColor.current,
                    disabledLabelColor = if (highlighted) AccentBlue else Muted,
                    disabledBorderColor = if (error != null) MaterialTheme.colorScheme.error else if (highlighted) AccentBlue else Muted,
                    disabledSupportingTextColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = if (highlighted) Panel2 else androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    }
}
