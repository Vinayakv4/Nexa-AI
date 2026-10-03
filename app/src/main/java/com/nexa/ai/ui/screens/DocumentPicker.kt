package com.nexa.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.nexa.ai.vm.ChatViewModel

@Composable
fun DocumentPickerEffect(viewModel: ChatViewModel, trigger: Int) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.loadDocument(uri)
        }
    }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            launcher.launch(
                arrayOf(
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "text/csv"
                )
            )
        }
    }
}
