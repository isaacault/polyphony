package app.polyphony.provider.subsonic.internal

private val TAG = Regex("<[^>]+>")

/** Last.fm biographies arrive as HTML snippets; keep the text only. */
internal fun String.stripHtml(): String =
    replace("<br>", "\n", ignoreCase = true)
        .replace("<br/>", "\n", ignoreCase = true)
        .replace(TAG, "")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .trim()
