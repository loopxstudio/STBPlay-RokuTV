package com.stbplay.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable
internal fun ConnectionIndicator(viewModel: MainViewModel) {
    Column {
        Text(viewModel.connectionStatus, color = when {
            viewModel.connectionFailed -> Color(0xFFFF9D9D)
            viewModel.loading -> Color(0xFFFFC66D)
            else -> AccentBlue
        }, fontSize = 11.sp)
        if (viewModel.connectionStatus == "CONNECTING") TextButton(onClick = { viewModel.cancelConnection() }) { Text("Cancel connection") }
        else if (viewModel.connectionFailed) TextButton(enabled = !viewModel.loading, onClick = { viewModel.lastProtocol?.let { viewModel.connectSource(it) } }) { Text("Retry connection") }
    }
}
