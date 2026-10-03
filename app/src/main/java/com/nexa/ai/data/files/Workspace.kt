package com.nexa.ai.data.files

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream

object Workspace {

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Err(val message: String) : Result
    }

    private const val MAX_READ_CHARS = 24_000
    private const val MAX_LIST_ENTRIES = 300
    private const val MAX_DEPTH = 5

    private fun sanitize(path: String): List<String> =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun resolveDir(root: DocumentFile, segments: List<String>, create: Boolean): DocumentFile? {
        var dir = root
        for (seg in segments) {
            val found = dir.findFile(seg)
            dir = when {
                found != null && found.isDirectory -> found
                found == null && create -> dir.createDirectory(seg) ?: return null
                else -> return null
            }
        }
        return dir
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".csv", true) -> "text/csv"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".pdf", true) -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun openWrite(context: Context, dir: DocumentFile, name: String, mode: String): java.io.OutputStream? {
        val uri = dir.findFile(name)?.takeIf { it.isFile }?.uri
            ?: dir.createFile(mimeFor(name), name)?.uri
            ?: return null
        return context.contentResolver.openOutputStream(uri, mode)
    }

    fun list(root: DocumentFile, path: String, recursive: Boolean): Result {
        val dir = if (path.isBlank()) root
        else resolveDir(root, sanitize(path), create = false) ?: return Result.Err("Folder not found: $path")
        val out = mutableListOf<String>()
        walk(dir, path.trim('/'), recursive, 0, out)
        return if (out.isEmpty()) Result.Ok("(empty folder)")
        else Result.Ok(out.take(MAX_LIST_ENTRIES).joinToString("\n"))
    }

    private fun walk(dir: DocumentFile, prefix: String, recursive: Boolean, depth: Int, out: MutableList<String>) {
        if (depth > MAX_DEPTH || out.size >= MAX_LIST_ENTRIES) return
        for (f in dir.listFiles()) {
            val rel = if (prefix.isBlank()) f.name ?: "?" else "$prefix/${f.name}"
            out += if (f.isDirectory) "$rel/" else "$rel (${f.length()} B)"
            if (recursive && f.isDirectory) walk(f, rel, true, depth + 1, out)
            if (out.size >= MAX_LIST_ENTRIES) { out += "(truncated)"; return }
        }
    }

    fun read(context: Context, root: DocumentFile, path: String): Result {
        val segs = sanitize(path)
        if (segs.isEmpty()) return Result.Err("Empty path")
        val dir = resolveDir(root, segs.dropLast(1), create = false) ?: return Result.Err("Folder not found: $path")
        val file = dir.findFile(segs.last())?.takeIf { it.isFile } ?: return Result.Err("File not found: $path")
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                ?: return Result.Err("Could not open file")
        }.fold(
            onSuccess = { text ->
                if (text.length > MAX_READ_CHARS) Result.Ok(text.take(MAX_READ_CHARS) + "\n…(truncated)")
                else Result.Ok(text.ifBlank { "(empty file)" })
            },
            onFailure = { Result.Err("Read failed: ${it.message}") }
        )
    }

    fun write(context: Context, root: DocumentFile, path: String, content: String, append: Boolean): Result {
        val segs = sanitize(path)
        if (segs.isEmpty()) return Result.Err("Empty path")
        val dir = resolveDir(root, segs.dropLast(1), create = true) ?: return Result.Err("Could not create folders for: $path")
        val ok = runCatching {
            openWrite(context, dir, segs.last(), if (append) "wa" else "wt")?.use { os ->
                if (append) os.write("\n".toByteArray())
                os.write(content.toByteArray())
                true
            } ?: false
        }.getOrDefault(false)
        return if (ok) Result.Ok("${if (append) "Appended to" else "Saved"} ${segs.joinToString("/")} (${content.length} chars)")
        else Result.Err("Write failed: $path")
    }

    fun delete(root: DocumentFile, path: String): Result {
        val segs = sanitize(path)
        if (segs.isEmpty()) return Result.Err("Empty path")
        val dir = resolveDir(root, segs.dropLast(1), create = false) ?: return Result.Err("Folder not found: $path")
        val target = dir.findFile(segs.last()) ?: return Result.Err("Not found: $path")
        val ok = deleteRecursive(target)
        return if (ok) Result.Ok("Deleted $path") else Result.Err("Delete failed: $path")
    }

    private fun deleteRecursive(file: DocumentFile): Boolean {
        if (file.isDirectory) file.listFiles().forEach { deleteRecursive(it) }
        return file.delete()
    }

    fun mkdir(root: DocumentFile, path: String): Result {
        resolveDir(root, sanitize(path), create = true) ?: return Result.Err("Could not create folder: $path")
        return Result.Ok("Folder ready: $path")
    }

    fun pdf(context: Context, root: DocumentFile, path: String, content: String): Result {
        val segs = sanitize(path)
        if (segs.isEmpty()) return Result.Err("Empty path")
        val name = segs.last().let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val dir = resolveDir(root, segs.dropLast(1), create = true) ?: return Result.Err("Could not create folders for: $path")
        return runCatching {
            val bytes = renderPdf(content)
            val target = dir.findFile(name)?.takeIf { it.isFile }
                ?: dir.createFile("application/pdf", name)
                ?: throw IllegalStateException("createFile failed")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream failed")
            "PDF saved: ${segs.dropLast(1).plus(name).joinToString("/")} (${content.length} chars)"
        }.fold(
            onSuccess = { Result.Ok(it) },
            onFailure = { Result.Err("PDF failed: ${it.message}") }
        )
    }

    private fun renderPdf(content: String): ByteArray {
        val document = PdfDocument()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = 0xFF5B3FD9.toInt()
        }
        val pageW = 595; val pageH = 842; val margin = 45f; val lineH = 15f
        val maxLines = ((pageH - 2 * margin) / lineH).toInt()
        val wrapped = content.lines().flatMap { wrap(it, 88) }
        val pages = wrapped.chunked(maxLines).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { i, lines ->
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
            val page = document.startPage(info)
            val canvas = page.canvas
            if (i == 0) canvas.drawText("Nexa Workspace", margin, 30f, title)
            var y = margin + 6f
            for (line in lines) {
                canvas.drawText(line, margin, y, body)
                y += lineH
            }
            canvas.drawText("- ${i + 1} -", pageW / 2 - 18f, pageH - 25f, body)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun wrap(line: String, width: Int): List<String> {
        if (line.length <= width) return listOf(line)
        val out = mutableListOf<String>()
        var rest = line
        while (rest.length > width) {
            var cut = rest.lastIndexOf(' ', width)
            if (cut <= 0) cut = width
            out += rest.take(cut)
            rest = rest.drop(cut).trimStart()
        }
        out += rest
        return out
    }
}
