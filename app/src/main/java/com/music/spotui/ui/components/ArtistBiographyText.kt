package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.music.spotui.util.DeepLinkHandler
import com.music.spotui.util.SpotifyDeepLink

private fun biographyHtml(raw: String): String {
    var value = raw.replace("\\<", "<").replace("\\>", ">").replace("\\\"", "\"")
    // Protect actual markup while unescaping nested entities, including encoded anchors.
    repeat(2) {
        value = Html.fromHtml(value.replace("<", "&lt;").replace(">", "&gt;"), Html.FROM_HTML_MODE_LEGACY).toString()
    }
    return value
}

@Composable
internal fun ArtistBiographyText(
    raw: String, modifier: Modifier = Modifier, color: Color = Color(0xFFB3B3B3),
    fontSizeSp: Float = 15f, maxLines: Int = Int.MAX_VALUE,
) {
    val external = LocalUriHandler.current
    val textState = androidx.compose.runtime.produceState<SpannableStringBuilder?>(null, raw) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        SpannableStringBuilder(Html.fromHtml(biographyHtml(raw), Html.FROM_HTML_MODE_LEGACY)).also { content ->
            content.getSpans(0, content.length, URLSpan::class.java).forEach { old ->
                val start=content.getSpanStart(old); val end=content.getSpanEnd(old)
                content.removeSpan(old)
                content.setSpan(object: URLSpan(old.url) {
                    override fun onClick(widget: android.view.View) {
                        val link = com.music.spotui.util.artistBiographyLink(url)
                        if (com.music.spotui.util.isSpotifyBiographyLink(link)) {
                            DeepLinkHandler.handleIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } else if (Uri.parse(link).scheme in listOf("https", "http")) {
                            runCatching { external.openUri(link) }
                        }
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    }
    AndroidView(modifier = modifier, factory = { context ->
        TextView(context).apply {
            includeFontPadding=false
            movementMethod=LinkMovementMethod.getInstance()
            setLinkTextColor(android.graphics.Color.rgb(30,215,96))
        }
    }, update = { view ->
        if (view.tag !== textState.value) { view.text=textState.value; view.tag=textState.value }
        view.textSize=fontSizeSp
        view.maxLines=maxLines
        view.ellipsize=android.text.TextUtils.TruncateAt.END
        view.setTextColor(color.toArgb())
    })
}
