package com.nexa.ai.data.files

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DocumentExtractor {

    private const val MAX_CHARS = 24_000

    suspend fun extract(context: Context, uri: Uri): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val name = queryName(context, uri) ?: "document"
            val lower = name.lowercase()
            val text = when {
                lower.endsWith(".pdf") -> extractPdf(context, uri)
                lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ->
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                else -> null
            } ?: return@withContext null
            val trimmed = text.trim().take(MAX_CHARS)
            if (trimmed.isBlank()) null else name to trimmed
        }

    private fun extractPdf(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().getText(doc)
                }
            }
        }.getOrNull()

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
}
