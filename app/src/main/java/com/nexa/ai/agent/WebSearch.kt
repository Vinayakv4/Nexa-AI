package com.nexa.ai.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object WebSearch {

    data class Result(val title: String, val url: String, val snippet: String)

    suspend fun search(query: String, maxResults: Int = 6): List<Result> =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
                    .data("q", query)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .timeout(12_000)
                    .post()
                val results = mutableListOf<Result>()
                val links = doc.select("div.result, div.web-result")
                for (el in links) {
                    if (results.size >= maxResults) break
                    val anchor = el.selectFirst("a.result__a") ?: continue
                    val title = anchor.text().trim()
                    var url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    // DDG wraps URLs: //duckduckgo.com/l/?uddg=<encoded>&rut=...
                    if (url.contains("duckduckgo.com/l/")) {
                        Regex("uddg=([^&]+)").find(url)?.let { m ->
                            url = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                        }
                    }
                    if (url.startsWith("//")) url = "https:$url"
                    val snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                    if (title.isNotBlank() && url.startsWith("http")) {
                        results += Result(title, url, snippet)
                    }
                }
                results
            }.getOrElse { emptyList() }
        }

    suspend fun fetchPageText(url: String, maxChars: Int = 6_000): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .timeout(12_000)
                    .followRedirects(true)
                    .get()
                doc.select("script, style, nav, footer, header, aside").remove()
                val text = doc.body()?.text().orEmpty()
                if (text.length > maxChars) text.take(maxChars) + "…(truncated)" else text
            }.getOrElse { "ERROR: could not fetch $url (${it.message})" }
        }
}
