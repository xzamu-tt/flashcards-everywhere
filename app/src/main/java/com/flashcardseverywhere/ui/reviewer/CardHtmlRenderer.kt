@file:Suppress("SpellCheckingInspection")
/*
 * Copyright 2026 FlashcardsEverywhere
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flashcardseverywhere.ui.reviewer

/**
 * Shared HTML renderer that emulates AnkiDroid's card rendering pipeline.
 * Used by all surfaces: in-app reviewer, overlay, dream, etc.
 */
object CardHtmlRenderer {

    /**
     * Build a complete HTML document for displaying a card.
     *
     * @param html The raw card HTML (from ContentProvider Card.QUESTION or Card.ANSWER)
     * @param cardOrd 0-based card ordinal (converted to 1-based for CSS class)
     * @param nightMode Whether to apply night mode classes
     * @param background CSS background color (e.g., "#303030" or "transparent")
     * @param foreground CSS text color (e.g., "#ffffff")
     * @param fontSize Base font size in px (default 20)
     * @param centerVertically Whether to vertically center content
     */
    fun render(
        html: String,
        cardOrd: Int = 0,
        nightMode: Boolean = true,
        background: String = "transparent",
        foreground: String = "#ffffff",
        fontSize: Int = 20,
        centerVertically: Boolean = false,
    ): String {
        val processed = processContent(html)
        val bodyClasses = buildBodyClasses(cardOrd, nightMode, centerVertically)
        val mathjaxScripts = if (containsMathJax(html)) MATHJAX_SCRIPTS else ""

        return """
<!doctype html>
<html class="mobile android js">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
<style>
/* ── Base flashcard styles (emulates AnkiDroid flashcard.css) ── */
html, body {
    margin: 0;
    padding: 16px;
    background: $background;
    color: $foreground;
    font-size: ${fontSize}px;
    line-height: 1.5;
    font-family: -apple-system, "Helvetica Neue", Arial, sans-serif;
    word-wrap: break-word;
    overflow-wrap: break-word;
}

/* Night mode overrides — match AnkiDroid's class convention */
body.night_mode {
    color: #fff;
    background-color: ${if (background == "transparent") "transparent" else "#303030"};
}
body.ankidroid_dark_mode {
    background-color: ${if (background == "transparent") "transparent" else "#303030"};
}
.nightMode .latex { filter: invert(100%); }

/* Card content wrapper */
#content { width: 100%; }
#qa { width: 100%; }

/* Responsive images */
img { max-width: 100%; height: auto; display: block; margin: 8px auto; }

/* Vertical centering (optional) */
${if (centerVertically) """
body { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
#content { text-align: center; }
""" else ""}

/* Sound play button styling (stripped by default) */
.replay-button { display: none; }

/* Type answer input */
#typeans { width: 100%; border: 1px solid #ccc; padding: 8px; border-radius: 4px;
           font-size: inherit; font-family: monospace; }

/* Cloze styling */
.cloze { font-weight: bold; color: #0073e6; }
body.night_mode .cloze { color: #5cc8ff; }

/* MathJax hide-until-rendered */
.mathjax-needs-to-render { visibility: hidden; }

/* Table styling */
table { border-collapse: collapse; margin: 8px auto; }
td, th { padding: 4px 8px; border: 1px solid #555; }
</style>
$mathjaxScripts
</head>
<body class="$bodyClasses">
<div id="content"><div id="qa">
$processed
</div></div>
</body>
</html>""".trimIndent()
    }

    /**
     * Process raw card HTML:
     * - Strip [sound:...] tags
     * - Fix font-weight:600 → 700 (Android WebView bold rendering bug)
     * - IRI-encode media filenames (spaces → %20)
     */
    private fun processContent(html: String): String {
        var result = html
        // Strip [sound:filename] tags — we can't play audio in the overlay/widget
        result = SOUND_PATTERN.replace(result, "")
        // Fix Android WebView bold rendering bug
        result = result.replace("font-weight:600", "font-weight:700")
        result = result.replace("font-weight: 600", "font-weight: 700")
        // Encode spaces in media filenames within src attributes
        result = MEDIA_SRC_PATTERN.replace(result) { match ->
            val prefix = match.groupValues[1] // src="
            val filename = match.groupValues[2]
            val encoded = filename.replace(" ", "%20")
            "$prefix$encoded\""
        }
        return result
    }

    private fun buildBodyClasses(cardOrd: Int, nightMode: Boolean, centerVertically: Boolean): String {
        val sb = StringBuilder("card card${cardOrd + 1}")
        if (nightMode) {
            sb.append(" night_mode nightMode ankidroid_dark_mode")
        }
        if (centerVertically) {
            sb.append(" vertically_centered")
        }
        return sb.toString()
    }

    /** True if the content likely contains MathJax delimiters. */
    fun containsMathJax(html: String): Boolean =
        html.contains("\\(") || html.contains("\\)") ||
        html.contains("\\[") || html.contains("\\]") ||
        html.contains("$$")

    /** Strip HTML to plain text (for notifications, bubbles, etc.) */
    fun stripHtml(html: String): String =
        androidx.core.text.HtmlCompat
            .fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()
            .let { SOUND_PATTERN.replace(it, "") }
            .trim()

    private val SOUND_PATTERN = Regex("""\[sound:[^\]]*]""")
    private val MEDIA_SRC_PATTERN = Regex("""(src=["'])([^"']+)["']""")

    private const val MATHJAX_SCRIPTS = """
<script>
window.MathJax = {
  tex: { inlineMath: [['\\(', '\\)']], displayMath: [['\\[', '\\]']] },
  options: { skipHtmlTags: ['script', 'noscript', 'style', 'textarea', 'pre'] },
  startup: {
    ready: function() {
      MathJax.startup.defaultReady();
      MathJax.startup.promise.then(function() {
        document.body.classList.remove('mathjax-needs-to-render');
      });
    }
  }
};
</script>
<script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js" async></script>
"""
}
