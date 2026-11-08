package com.gustav.minicalendar

import android.text.style.URLSpan
import androidx.core.text.HtmlCompat

object EventText {
    private val htmlTag = Regex("</?(?:p|div|br|a|span|html|body|b|strong|i|em|ul|ol|li|table|tr|td|h[1-6])(?:\\s[^>]*|/?)>", RegexOption.IGNORE_CASE)
    private val entity = Regex("&(?:amp|lt|gt|quot|apos|nbsp|#\\d+|#x[0-9a-f]+);", RegexOption.IGNORE_CASE)
    private val syncField = Regex("^SYNC_(?:KEY|HASH):")

    fun description(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val plain = if (htmlTag.containsMatchIn(normalized) || entity.containsMatchIn(normalized)) {
            val markup = if (htmlTag.containsMatchIn(normalized)) normalized else
                normalized.replace("<", "&lt;").replace(">", "&gt;")
            val parsed = HtmlCompat.fromHtml(markup.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_COMPACT)
            val result = StringBuilder(parsed.toString())
            // Keep meeting URLs when the HTML anchor uses a label such as "Join".
            parsed.getSpans(0, parsed.length, URLSpan::class.java)
                .sortedByDescending { parsed.getSpanEnd(it) }
                .forEach { span ->
                    val label = parsed.subSequence(parsed.getSpanStart(span), parsed.getSpanEnd(span)).toString()
                    if (label != span.url) result.insert(parsed.getSpanEnd(span), " (${span.url})")
                }
            result.toString()
        } else normalized

        var inSyncField = false
        return plain.lineSequence().filter { line ->
            when {
                syncField.containsMatchIn(line.trimStart()) -> {
                    inSyncField = true
                    false
                }
                inSyncField && (line.startsWith(" ") || line.startsWith("\t")) -> false
                else -> {
                    inSyncField = false
                    true
                }
            }
        }.joinToString("\n").replace('\u00a0', ' ')
            .replace(Regex("\n[ \\t]*\n(?:[ \\t]*\n)+"), "\n\n").trim()
    }
}
