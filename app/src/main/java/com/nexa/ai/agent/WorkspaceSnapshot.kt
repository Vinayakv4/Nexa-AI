package com.nexa.ai.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object WorkspaceSnapshot {

    private const val MAX_FILE_BYTES = 800_000
    private const val MAX_TOTAL_BYTES = 40_000_000
    private const val MAX_FILES = 300

    fun dir(context: Context, taskId: Long): File =
        File(context.filesDir, "snapshots/task_$taskId")

    fun create(context: Context, workspaceUri: String, taskId: Long): Boolean {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(workspaceUri)) ?: return false
        val dest = dir(context, taskId)
        dest.deleteRecursively()
        dest.mkdirs()
        val counter = TotalRef(0, 0)
        val ok = walk(context, root, "", dest, counter)
        if (!ok) dest.deleteRecursively()
        return ok
    }

    private class TotalRef(var total: Long, var count: Int) {
        fun add(bytes: Int) { total += bytes; count += 1 }
    }

    private fun walk(
        context: Context,
        dir: DocumentFile,
        prefix: String,
        destRoot: File,
        counter: TotalRef
    ): Boolean {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            val rel = if (prefix.isBlank()) name else "$prefix/$name"
            if (f.isDirectory) {
                File(destRoot, rel).mkdirs()
                if (!walk(context, f, rel, destRoot, counter)) return false
            } else {
                val len = f.length()
                if (len > MAX_FILE_BYTES) {
                    File(destRoot, "$rel.skip").writeText("large:$len")
                    continue
                }
                if (counter.total + len > MAX_TOTAL_BYTES) return true
                runCatching {
                    context.contentResolver.openInputStream(f.uri)?.use { input ->
                        val out = File(destRoot, rel)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { output -> input.copyTo(output) }
                        counter.add(len.toInt())
                    }
                }
            }
        }
        return true
    }

    fun undo(context: Context, workspaceUri: String, taskId: Long): Pair<Int, Int> {
        val snap = dir(context, taskId)
        if (!snap.exists()) return 0 to 0
        val root = DocumentFile.fromTreeUri(context, Uri.parse(workspaceUri)) ?: return 0 to 0

        // Build desired set from snapshot
        val desired = mutableMapOf<String, File>()
        snap.walkTopDown().forEach { f ->
            if (f.isFile && !f.name.endsWith(".skip")) {
                desired[f.relativeTo(snap).path] = f
            }
        }
        // Build current set
        val current = mutableSetOf<String>()
        fun collect(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                val rel = if (prefix.isBlank()) name else "$prefix/$name"
                if (f.isDirectory) collect(f, rel) else current += rel
            }
        }
        collect(root, "")

        var restored = 0
        var deleted = 0

        // Delete files created after snapshot (not folders this pass; keep folders)
        val toDelete = current - desired.keys
        for (rel in toDelete) {
            val segs = rel.split('/')
            var dir = root
            for (s in segs.dropLast(1)) {
                dir = dir.findFile(s)?.takeIf { it.isDirectory } ?: break
            }
            dir.findFile(segs.last())?.let {
                if (it.isFile && it.delete()) deleted++
            }
        }

        // Restore/overwrite snapshot files
        for ((rel, src) in desired) {
            val segs = rel.split('/')
            var dir = root
            for (s in segs.dropLast(1)) {
                dir = dir.findFile(s)?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(s)
                    ?: break
            }
            if (dir == null) continue
            val mime = when {
                rel.endsWith(".pdf", true) -> "application/pdf"
                rel.endsWith(".md", true) -> "text/markdown"
                rel.endsWith(".csv", true) -> "text/csv"
                rel.endsWith(".json", true) -> "application/json"
                rel.endsWith(".html", true) -> "text/html"
                else -> "text/plain"
            }
            runCatching {
                val target = dir.findFile(segs.last())?.takeIf { it.isFile }
                    ?: dir.createFile(mime, segs.last())
                    ?: return@runCatching
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                }
                restored++
            }
        }
        snap.deleteRecursively()
        return restored to deleted
    }
}
