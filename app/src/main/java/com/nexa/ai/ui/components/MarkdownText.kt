package com.nexa.ai.ui.components

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
) {
    val context = LocalContext.current
    val argb = color.toArgb()
    val markwon = remember(context, argb) {
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = android.graphics.Color.TRANSPARENT
                textSize = 15.5f
                setLineSpacing(0f, 1.25f)
            }
        },
        update = { tv ->
            tv.setTextColor(argb)
            markwon.setMarkdown(tv, markdown)
        },
        modifier = modifier
    )
}
