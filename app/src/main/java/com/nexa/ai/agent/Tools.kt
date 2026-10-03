package com.nexa.ai.agent

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.nexa.ai.data.files.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolCall(val id: String, val name: String, val arguments: String) {
    fun summary(): String = runCatching {
        val args = Json.parseToJsonElement(arguments).jsonObject
        when (name) {
            "read_file", "delete_path", "create_folder" -> args["path"]?.jsonPrimitive?.content ?: ""
            "list_files" -> args["path"]?.jsonPrimitive?.content?.ifBlank { "/" } ?: "/"
            "web_search" -> "“${args["query"]?.jsonPrimitive?.content ?: ""}”"
            "fetch_page" -> args["url"]?.jsonPrimitive?.content ?: ""
            "write_memory" -> "MEMORY.md"
            else -> args["path"]?.jsonPrimitive?.content ?: ""
        }
    }.getOrDefault("")
}
data class ToolResult(val callId: String, val name: String, val output: String)

object Tools {

    const val MAX_STEPS = 8

    fun jsonSchema(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "list_files")
                put("description", "List files and folders in the workspace. Use path \"\" for root.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "Folder path relative to workspace root, e.g. \"notes\" or \"\" for root") })
                        put("recursive", buildJsonObject { put("type", "boolean"); put("description", "List nested folders too") })
                    })
                    put("required", buildJsonArray { })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "read_file")
                put("description", "Read a text file (.txt, .md, .csv, .json, .html) from the workspace.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "File path relative to workspace root, e.g. \"notes/today.md\"") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "write_file")
                put("description", "Create or overwrite a text file (.txt, .md, .csv, .json, .html). Parent folders are created automatically.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "File path relative to workspace root") })
                        put("content", buildJsonObject { put("type", "string"); put("description", "Full file content") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")); add(kotlinx.serialization.json.JsonPrimitive("content")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "append_file")
                put("description", "Append text to the end of an existing file (creates it if missing).")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put("content", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")); add(kotlinx.serialization.json.JsonPrimitive("content")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "create_pdf")
                put("description", "Generate a formatted PDF document in the workspace from plain text content (headings optional, plain text body).")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "PDF file path, e.g. \"reports/summary.pdf\"") })
                        put("content", buildJsonObject { put("type", "string"); put("description", "Text content of the PDF") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")); add(kotlinx.serialization.json.JsonPrimitive("content")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "create_folder")
                put("description", "Create a folder (and parents) in the workspace.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
                })
            })
        })
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "delete_path")
                put("description", "Delete a file or folder (recursive) in the workspace. Use carefully.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
                })
            })
        })
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun execute(context: Context, workspaceUri: String, call: ToolCall): ToolResult {
        val root = DocumentFile.fromTreeUri(context, android.net.Uri.parse(workspaceUri))
            ?: return ToolResult(call.id, call.name, "ERROR: workspace folder is no longer accessible")
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrElse {
            return ToolResult(call.id, call.name, "ERROR: bad arguments JSON")
        }
        fun str(key: String) = args[key]?.jsonPrimitive?.content ?: ""

        val result = when (call.name) {
            "list_files" -> Workspace.list(root, str("path"), args["recursive"]?.jsonPrimitive?.content == "true")
            "read_file" -> Workspace.read(context, root, str("path"))
            "write_file" -> Workspace.write(context, root, str("path"), str("content"), append = false)
            "append_file" -> Workspace.write(context, root, str("path"), str("content"), append = true)
            "create_pdf" -> Workspace.pdf(context, root, str("path"), str("content"))
            "create_folder" -> Workspace.mkdir(root, str("path"))
            "delete_path" -> Workspace.delete(root, str("path"))
            "web_search" -> runWebSearch(str("query"), (args["max_results"]?.jsonPrimitive?.content)?.toIntOrNull() ?: 5)
            "fetch_page" -> runFetchPage(str("url"))
            "write_memory" -> Workspace.write(context, root, "MEMORY.md", str("content"), append = false)
            else -> Workspace.Result.Err("Unknown tool: ${call.name}")
        }
        val output = when (result) {
            is Workspace.Result.Ok -> result.message
            is Workspace.Result.Err -> "ERROR: ${result.message}"
        }
        return ToolResult(call.id, call.name, output)
    }

    private fun runWebSearch(query: String, max: Int): Workspace.Result {
        if (query.isBlank()) return Workspace.Result.Err("empty query")
        val results = kotlinx.coroutines.runBlocking { WebSearch.search(query, max.coerceIn(1, 10)) }
        if (results.isEmpty()) return Workspace.Result.Err("no results (network may be blocked)")
        val text = results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n\n")
        return Workspace.Result.Ok("Web results for \"$query\":\n\n$text")
    }

    private fun runFetchPage(url: String): Workspace.Result {
        if (!url.startsWith("http")) return Workspace.Result.Err("invalid url")
        val text = kotlinx.coroutines.runBlocking { WebSearch.fetchPageText(url) }
        return if (text.startsWith("ERROR:")) Workspace.Result.Err(text.removePrefix("ERROR: "))
        else Workspace.Result.Ok(text)
    }

    fun parseToolCalls(message: JsonObject): List<ToolCall> {
        val choices = message["choices"]?.jsonObject ?: return emptyList()
        val first = choices["choices"]?.let { (it as? JsonArray)?.firstOrNull() } ?: return emptyList()
        val msg = (first as? JsonObject)?.get("message") as? JsonObject ?: return emptyList()
        val calls = msg["tool_calls"] as? JsonArray ?: return emptyList()
        return calls.mapNotNull { element ->
            val tc = element as? JsonObject ?: return@mapNotNull null
            val fn = tc["function"] as? JsonObject ?: return@mapNotNull null
            ToolCall(
                id = tc["id"]?.jsonPrimitive?.content ?: "",
                name = fn["name"]?.jsonPrimitive?.content ?: "",
                arguments = fn["arguments"]?.jsonPrimitive?.content ?: "{}"
            )
        }
    }
}
