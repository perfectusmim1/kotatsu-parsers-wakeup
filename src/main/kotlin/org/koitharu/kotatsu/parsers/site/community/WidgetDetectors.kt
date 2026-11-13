package org.koitharu.kotatsu.parsers.site.community

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

internal object DisqusDetector {

	private val REGEX_EMBED_SRC = Regex(
		"""(?:https?:)?//([a-zA-Z0-9-]+)\.disqus\.com/(?:embed|count)\.js""",
		RegexOption.IGNORE_CASE,
	)
	private val REGEX_SHORTNAME_VAR = Regex(
		"""(?:var|let|const)?\s*disqus_shortname\s*=\s*['"]([a-zA-Z0-9-]+)['"]""",
	)
	private val REGEX_SHORTNAME_JSON = Regex(
		"""["']?disqus_?shortname["']?\s*:\s*["']([a-zA-Z0-9-]+)["']""",
		RegexOption.IGNORE_CASE,
	)
	private val REGEX_IDENTIFIER = Regex(
		"""(?:this\.page\.identifier|(?:var|let|const)?\s*disqus_identifier)\s*=\s*['"]([^'"]+)['"]""",
	)
	private val REGEX_PAGE_URL = Regex(
		"""(?:this\.page\.url|(?:var|let|const)?\s*disqus_url)\s*=\s*['"]([^'"]+)['"]""",
	)
	private val REGEX_PAGE_TITLE = Regex(
		"""(?:this\.page\.title|(?:var|let|const)?\s*disqus_title)\s*=\s*['"]([^'"]+)['"]""",
	)

	val ALLOWED_HOST_SUFFIXES: Set<String> = setOf(
		"disqus.com",
		"disquscdn.com",
		"accounts.google.com",
		"facebook.com",
		"twitter.com",
		"x.com",
	)

	fun detect(doc: Document, pageUrl: String): SourceWidgetContext? {
		val scriptsText = collectScriptsText(doc)
		val scriptSrcs = doc.select("script[src]").mapNotNull { it.attrOrNull("src") }
		val iframeSrc = doc.selectFirst("iframe[src*=\"disqus.com\"]")?.attrOrNull("src")

		val shortname = doc.selectFirst("#disqus_thread[data-shortname], [data-disqus-shortname]")
			?.let { it.attrOrNull("data-shortname") ?: it.attrOrNull("data-disqus-shortname") }
			?: scriptSrcs.firstNotNullOfOrNull { REGEX_EMBED_SRC.find(it)?.groupValues?.getOrNull(1) }
			?: iframeSrc?.toHttpUrlOrNull()?.queryParameter("f")?.nullIfEmpty()
			?: REGEX_SHORTNAME_VAR.find(scriptsText)?.groupValues?.getOrNull(1)
			?: REGEX_SHORTNAME_JSON.find(scriptsText)?.groupValues?.getOrNull(1)
			?: REGEX_EMBED_SRC.find(scriptsText)?.groupValues?.getOrNull(1)
			?: return null

		val normalizedShortname = shortname.trim().nullIfEmpty() ?: return null
		val resolvedPageUrl = REGEX_PAGE_URL.find(scriptsText)?.groupValues?.getOrNull(1)?.nullIfEmpty()
			?: doc.selectFirst("#disqus_thread[data-url], [data-disqus-url]")?.let {
				it.attrOrNull("data-url") ?: it.attrOrNull("data-disqus-url")
			}
			?: doc.selectFirst("link[rel=canonical]")?.attrOrNull("href")
			?: pageUrl
		val identifier = doc.selectFirst("#disqus_thread[data-identifier], [data-disqus-identifier]")?.let {
			it.attrOrNull("data-identifier") ?: it.attrOrNull("data-disqus-identifier")
		} ?: REGEX_IDENTIFIER.find(scriptsText)?.groupValues?.getOrNull(1)?.nullIfEmpty()
		val title = REGEX_PAGE_TITLE.find(scriptsText)?.groupValues?.getOrNull(1)?.nullIfEmpty()
			?: doc.title().nullIfEmpty()

		val widgetUrl = "https://$normalizedShortname.disqus.com/embed.js"
		val inlineHtml = buildInlineHtml(
			shortname = normalizedShortname,
			pageUrl = resolvedPageUrl,
			identifier = identifier,
			title = title,
		)

		return SourceWidgetContext(
			engine = SourceCommentEngine.DISQUS,
			pageUrl = resolvedPageUrl,
			widgetUrl = widgetUrl,
			identifier = identifier,
			shortname = normalizedShortname,
			title = title,
			inlineHtml = inlineHtml,
			baseUrl = resolvedPageUrl,
			allowedHostSuffixes = ALLOWED_HOST_SUFFIXES,
		)
	}

	fun buildInlineHtml(
		shortname: String,
		pageUrl: String,
		identifier: String? = null,
		title: String? = null,
	): String {
		val urlJs = escapeJsString(pageUrl)
		val idLine = identifier?.let { "this.page.identifier = '${escapeJsString(it)}';" } ?: ""
		val titleLine = title?.let { "this.page.title = '${escapeJsString(it)}';" } ?: ""
		val embedSrc = "https://$shortname.disqus.com/embed.js"
		return """
			<!DOCTYPE html>
			<html>
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0">
			<meta name="color-scheme" content="light dark">
			<style>
			html, body { margin: 0; padding: 8px; background: transparent; font-family: sans-serif; }
			#disqus_thread { min-height: 240px; }
			</style>
			</head>
			<body>
			<div id="disqus_thread"></div>
			<script>
			var disqus_config = function () {
				this.page.url = '$urlJs';
				$idLine
				$titleLine
			};
			(function() {
				var d = document, s = d.createElement('script');
				s.src = '${escapeJsString(embedSrc)}';
				s.setAttribute('data-timestamp', +new Date());
				(d.head || d.body).appendChild(s);
			})();
			</script>
			</body>
			</html>
		""".trimIndent()
	}
}

internal object GraphCommentDetector {

	private val REGEX_GC_WEBSITE_ID = Regex(
		"""(?:graphcommentId|graphcomment_id|website_id|uid)\s*[:=]\s*['"]([a-zA-Z0-9_-]+)['"]""",
	)
	private val REGEX_GC_SRC_ID = Regex(
		"""graphcomment\.com/.*[?&](?:graphcomment_id|website_id|uid)=([a-zA-Z0-9_-]+)""",
		RegexOption.IGNORE_CASE,
	)

	val ALLOWED_HOST_SUFFIXES: Set<String> = setOf(
		"graphcomment.com",
		"accounts.google.com",
		"facebook.com",
	)

	fun detect(doc: Document, pageUrl: String): SourceWidgetContext? {
		val scriptsText = collectScriptsText(doc)
		val gcContainer = doc.selectFirst("#graphcomment, [data-graphcomment-id], [data-gc-uid]")
		val scriptSrc = doc.select("script[src*=\"graphcomment.com\"]").firstOrNull()?.attrOrNull("src")
		val iframeSrc = doc.selectFirst("iframe[src*=\"graphcomment.com\"]")?.attrOrNull("src")

		val hasGcMarker = gcContainer != null ||
			scriptSrc != null ||
			iframeSrc != null ||
			scriptsText.contains("gc_params") ||
			scriptsText.contains("graphcommentId") ||
			scriptsText.contains("graphcomment.com")
		if (!hasGcMarker) return null

		val websiteId = gcContainer?.let {
			it.attrOrNull("data-graphcomment-id")
				?: it.attrOrNull("data-website-id")
				?: it.attrOrNull("data-uid")
				?: it.attrOrNull("data-gc-uid")
		}
			?: scriptSrc?.let { REGEX_GC_SRC_ID.find(it)?.groupValues?.getOrNull(1) }
			?: iframeSrc?.toHttpUrlOrNull()?.let { url ->
				url.queryParameter("website_id")
					?: url.queryParameter("graphcomment_id")
					?: url.queryParameter("uid")
			}
			?: extractGcParam(scriptsText, "graphcommentId")
			?: extractGcParam(scriptsText, "graphcomment_id")
			?: extractGcParam(scriptsText, "website_id")
			?: extractGcParam(scriptsText, "uid")
			?: REGEX_GC_WEBSITE_ID.find(scriptsText)?.groupValues?.getOrNull(1)

		val identifier = gcContainer?.attrOrNull("data-identifier")
			?: iframeSrc?.toHttpUrlOrNull()?.queryParameter("uid")
			?: extractGcParam(scriptsText, "uid")
			?: websiteId

		val widgetUrl = iframeSrc
			?: scriptSrc
			?: websiteId?.let { "https://graphcomment.com/js/integration.js?graphcomment_id=$it" }
			?: "https://graphcomment.com/js/integration.js"

		val inlineHtml = websiteId?.let { id ->
			buildInlineHtml(websiteId = id, identifier = identifier, pageUrl = pageUrl)
		}

		return SourceWidgetContext(
			engine = SourceCommentEngine.GRAPHCOMMENT,
			pageUrl = pageUrl,
			widgetUrl = widgetUrl,
			identifier = identifier,
			shortname = websiteId,
			title = doc.title().nullIfEmpty(),
			inlineHtml = inlineHtml,
			baseUrl = pageUrl,
			allowedHostSuffixes = ALLOWED_HOST_SUFFIXES,
		)
	}

	private fun extractGcParam(scriptsText: String, key: String): String? {
		val gcIdx = scriptsText.indexOf("gc_params")
		val searchText = if (gcIdx >= 0) scriptsText.substring(gcIdx) else scriptsText
		val regex = Regex("""['"]?${Regex.escape(key)}['"]?\s*[:=]\s*['"]([^'"]+)['"]""")
		return regex.find(searchText)?.groupValues?.getOrNull(1)?.nullIfEmpty()
	}

	fun buildInlineHtml(
		websiteId: String,
		identifier: String? = null,
		pageUrl: String,
	): String {
		val idJs = escapeJsString(websiteId)
		val uidLine = if (!identifier.isNullOrBlank() && identifier != websiteId) {
			"uid: '${escapeJsString(identifier)}',"
		} else {
			""
		}
		return """
			<!DOCTYPE html>
			<html>
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0">
			<meta name="color-scheme" content="light dark">
			<style>
			html, body { margin: 0; padding: 8px; background: transparent; font-family: sans-serif; }
			#graphcomment { min-height: 240px; }
			</style>
			</head>
			<body>
			<div id="graphcomment"></div>
			<script>
			var __semio__params = {
				graphcommentId: '$idJs',
				behaviour: { $uidLine }
			};
			window.gc_params = {
				graphcomment_id: '$idJs',
				fixed_header_height: 0
			};
			(function() {
				var gc = document.createElement('script');
				gc.type = 'text/javascript';
				gc.async = true;
				gc.src = 'https://graphcomment.com/js/integration.js?' + Date.now();
				(document.head || document.body).appendChild(gc);
			})();
			</script>
			</body>
			</html>
		""".trimIndent()
	}
}

internal object HyvorTalkDetector {

	private val REGEX_WEBSITE_ID = Regex("""HYVOR_TALK_WEBSITE\s*=\s*['"]?(\d+)['"]?""")
	private val REGEX_PAGE_ID = Regex("""HYVOR_TALK_CONFIG\s*=\s*\{[^}]*?id\s*:\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)

	val ALLOWED_HOST_SUFFIXES: Set<String> = setOf(
		"hyvor.com",
		"talk.hyvor.com",
		"accounts.google.com",
	)

	fun detect(doc: Document, pageUrl: String): SourceWidgetContext? {
		val el = doc.selectFirst("hyvor-talk-comments, hyvor-talk-view, #hyvor-talk-view")
		val scriptsText = collectScriptsText(doc)
		val scriptSrc = doc.selectFirst("script[src*=\"talk.hyvor.com\"]")?.attrOrNull("src")
		val iframeSrc = doc.selectFirst("iframe[src*=\"talk.hyvor.com\"]")?.attrOrNull("src")

		if (el == null && scriptSrc == null && iframeSrc == null && !scriptsText.contains("HYVOR_TALK")) {
			return null
		}

		val websiteId = el?.attrOrNull("website-id")
			?: el?.attrOrNull("data-website-id")
			?: REGEX_WEBSITE_ID.find(scriptsText)?.groupValues?.getOrNull(1)
			?: iframeSrc?.toHttpUrlOrNull()?.queryParameter("website_id")
		val pageId = el?.attrOrNull("page-id")
			?: el?.attrOrNull("data-page-id")
			?: REGEX_PAGE_ID.find(scriptsText)?.groupValues?.getOrNull(1)
			?: pageUrl

		val widgetUrl = iframeSrc ?: scriptSrc ?: "https://talk.hyvor.com/embed/embed.js"
		val inlineHtml = websiteId?.let { id ->
			buildInlineHtml(websiteId = id, pageId = pageId, pageUrl = pageUrl)
		}

		return SourceWidgetContext(
			engine = SourceCommentEngine.HYVOR,
			pageUrl = pageUrl,
			widgetUrl = widgetUrl,
			identifier = pageId,
			shortname = websiteId,
			title = doc.title().nullIfEmpty(),
			inlineHtml = inlineHtml,
			baseUrl = pageUrl,
			allowedHostSuffixes = ALLOWED_HOST_SUFFIXES,
		)
	}

	fun buildInlineHtml(websiteId: String, pageId: String, pageUrl: String): String {
		return """
			<!DOCTYPE html>
			<html>
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0">
			<meta name="color-scheme" content="light dark">
			<style>html, body { margin: 0; padding: 8px; background: transparent; }</style>
			</head>
			<body>
			<script async src="https://talk.hyvor.com/embed/embed.js" type="module"></script>
			<hyvor-talk-comments website-id="${escapeHtmlAttr(websiteId)}" page-id="${escapeHtmlAttr(pageId)}" page-url="${escapeHtmlAttr(pageUrl)}"></hyvor-talk-comments>
			</body>
			</html>
		""".trimIndent()
	}
}

internal object CusdisDetector {

	val ALLOWED_HOST_SUFFIXES: Set<String> = setOf("cusdis.com")

	fun detect(doc: Document, pageUrl: String): SourceWidgetContext? {
		val el = doc.selectFirst("#cusdis_thread, [data-cusdis-app-id], [data-app-id][data-page-id]")
		val scriptSrc = doc.selectFirst("script[src*=\"cusdis.com\"]")?.attrOrNull("src")
		val iframeSrc = doc.selectFirst("iframe[src*=\"cusdis.com\"]")?.attrOrNull("src")
		if (el == null && scriptSrc == null && iframeSrc == null) return null

		val appId = el?.attrOrNull("data-app-id")
			?: el?.attrOrNull("data-cusdis-app-id")
			?: iframeSrc?.toHttpUrlOrNull()?.queryParameter("appId")
		val pageId = el?.attrOrNull("data-page-id")
			?: iframeSrc?.toHttpUrlOrNull()?.queryParameter("pageId")
			?: pageUrl
		val resolvedPageUrl = el?.attrOrNull("data-page-url") ?: pageUrl
		val title = el?.attrOrNull("data-page-title") ?: doc.title().nullIfEmpty()
		val host = el?.attrOrNull("data-host")?.removeSuffix("/") ?: "https://cusdis.com"

		val widgetUrl = iframeSrc ?: scriptSrc ?: "$host/js/cusdis.es.js"
		val inlineHtml = appId?.let { id ->
			"""
			<!DOCTYPE html>
			<html>
			<head>
			<meta charset="utf-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0">
			<meta name="color-scheme" content="light dark">
			<style>html, body { margin: 0; padding: 8px; background: transparent; }</style>
			</head>
			<body>
			<div id="cusdis_thread"
				data-host="${escapeHtmlAttr(host)}"
				data-app-id="${escapeHtmlAttr(id)}"
				data-page-id="${escapeHtmlAttr(pageId)}"
				data-page-url="${escapeHtmlAttr(resolvedPageUrl)}"
				data-page-title="${escapeHtmlAttr(title.orEmpty())}"></div>
			<script async defer src="${escapeHtmlAttr(host)}/js/cusdis.es.js"></script>
			</body>
			</html>
			""".trimIndent()
		}

		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSDIS,
			pageUrl = resolvedPageUrl,
			widgetUrl = widgetUrl,
			identifier = pageId,
			shortname = appId,
			title = title,
			inlineHtml = inlineHtml,
			baseUrl = resolvedPageUrl,
			allowedHostSuffixes = ALLOWED_HOST_SUFFIXES,
		)
	}
}

internal object WidgetDetectors {

	fun detect(doc: Document, pageUrl: String): SourceWidgetContext? {
		return GraphCommentDetector.detect(doc, pageUrl)
			?: DisqusDetector.detect(doc, pageUrl)
			?: HyvorTalkDetector.detect(doc, pageUrl)
			?: CusdisDetector.detect(doc, pageUrl)
	}
}

internal fun collectScriptsText(doc: Document): String = buildString {
	for (script in doc.select("script")) {
		val inline = script.data()
		if (inline.isNotBlank()) {
			appendLine(inline)
		}
		val src = script.attrOrNull("src") ?: continue
		if (src.startsWith("data:", ignoreCase = true)) {
			val base64Marker = ";base64,"
			val idx = src.indexOf(base64Marker, ignoreCase = true)
			if (idx >= 0) {
				val encoded = src.substring(idx + base64Marker.length).trim()
				val decoded = encoded.decodeBase64()?.utf8()
				if (!decoded.isNullOrBlank()) {
					appendLine(decoded)
				}
			}
		}
	}
}

internal fun escapeJsString(value: String): String = buildString(value.length + 8) {
	for (ch in value) {
		when (ch) {
			'\\' -> append("\\\\")
			'\'' -> append("\\'")
			'"' -> append("\\\"")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			'<' -> append("\\u003C")
			'>' -> append("\\u003E")
			else -> append(ch)
		}
	}
}

internal fun escapeHtmlAttr(value: String): String = buildString(value.length + 8) {
	for (ch in value) {
		when (ch) {
			'&' -> append("&amp;")
			'"' -> append("&quot;")
			'\'' -> append("&#39;")
			'<' -> append("&lt;")
			'>' -> append("&gt;")
			else -> append(ch)
		}
	}
}

internal fun extractBalancedJsonObjectAfter(source: String, marker: String): String? {
	val markerIdx = source.indexOf(marker)
	if (markerIdx < 0) return null
	val start = source.indexOf('{', markerIdx + marker.length)
	if (start < 0) return null
	var depth = 0
	var inString = false
	var stringQuote = '\u0000'
	var escaped = false
	for (i in start until source.length) {
		val ch = source[i]
		if (escaped) {
			escaped = false
			continue
		}
		if (inString) {
			if (ch == '\\') {
				escaped = true
			} else if (ch == stringQuote) {
				inString = false
			}
			continue
		}
		when (ch) {
			'\'', '"' -> {
				inString = true
				stringQuote = ch
			}
			'{' -> depth++
			'}' -> {
				depth--
				if (depth == 0) {
					return source.substring(start, i + 1)
				}
			}
		}
	}
	return null
}

private val REGEX_COMPACT_COUNT = Regex("""(\d+(?:[.,]\d+)?)\s*([kmb])(?:\b|$)""", RegexOption.IGNORE_CASE)
private val REGEX_GROUPED_INT = Regex("""\d{1,3}(?:[.,\s]\d{3})+""")
private val REGEX_PLAIN_DIGITS = Regex("""\d+""")
private val REGEX_DECIMAL_FLOAT = Regex("""\d+(?:[.,]\d+)?""")

internal fun parseCompactCount(raw: String?): Long? {
	val text = raw?.trim()?.nullIfEmpty() ?: return null
	val compactMatch = REGEX_COMPACT_COUNT.find(text)
	if (compactMatch != null) {
		val num = compactMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
		val multiplier = when (compactMatch.groupValues[2].lowercase(Locale.ROOT)) {
			"k" -> 1_000.0
			"m" -> 1_000_000.0
			"b" -> 1_000_000_000.0
			else -> 1.0
		}
		return (num * multiplier).roundToLong()
	}
	val binMatch = Regex("""(\d+(?:[.,]\d+)?)\s*bin\b""", RegexOption.IGNORE_CASE).find(text)
	if (binMatch != null) {
		val num = binMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
		return (num * 1_000.0).roundToLong()
	}
	val groupedMatch = REGEX_GROUPED_INT.find(text)
	if (groupedMatch != null) {
		return groupedMatch.value.filter { it.isDigit() }.toLongOrNull()
	}
	return REGEX_PLAIN_DIGITS.find(text)?.value?.toLongOrNull()
}

internal fun parseFloatRating(raw: String?): Float? {
	val text = raw?.trim()?.nullIfEmpty() ?: return null
	return REGEX_DECIMAL_FLOAT.find(text)?.value?.replace(',', '.')?.toFloatOrNull()
}

internal fun parseIsoTimestamp(raw: String?): Long? {
	val text = raw?.trim()?.nullIfEmpty() ?: return null
	if (text.all { it.isDigit() }) {
		val num = text.toLongOrNull() ?: return null
		return if (text.length <= 10) num * 1000L else num
	}
	val normalized = text
		.replace(' ', 'T')
		.let { s ->
			// Strip fractional seconds if > 3 digits or simplify to millisecond precision
			Regex("""\.(\d{3})\d+""").replace(s) { ".${it.groupValues[1]}" }
		}
		.let { s ->
			// Convert +03:00 -> +0300 for SimpleDateFormat Z pattern compatibility across API levels
			Regex("""([+-]\d{2}):(\d{2})$""").replace(s) { "${it.groupValues[1]}${it.groupValues[2]}" }
		}
	val patterns = arrayOf(
		"yyyy-MM-dd'T'HH:mm:ss.SSSX",
		"yyyy-MM-dd'T'HH:mm:ssX",
		"yyyy-MM-dd'T'HH:mm:ss.SSSZ",
		"yyyy-MM-dd'T'HH:mm:ssZ",
		"yyyy-MM-dd'T'HH:mm:ss.SSS",
		"yyyy-MM-dd'T'HH:mm:ss",
		"yyyy-MM-dd",
	)
	for (pattern in patterns) {
		val sdf = SimpleDateFormat(pattern, Locale.US).apply {
			isLenient = false
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val parsed = runCatching { sdf.parse(normalized)?.time }.getOrNull()
		if (parsed != null) return parsed
	}
	return null
}

internal fun nestCommentsByParentId(flatComments: List<SourceComment>): List<SourceComment> {
	if (flatComments.isEmpty()) return emptyList()
	val byId = LinkedHashMap<String, SourceComment>(flatComments.size)
	for (c in flatComments) {
		byId[c.id] = c
	}
	val childrenByParent = LinkedHashMap<String, MutableList<SourceComment>>()
	val topLevelIds = ArrayList<String>()
	for (c in flatComments) {
		val pid = c.parentId?.takeIf { it.isNotBlank() && it != "0" && it != c.id && it in byId }
		if (pid != null) {
			childrenByParent.getOrPut(pid) { ArrayList() }.add(c)
		} else {
			topLevelIds.add(c.id)
		}
	}
	fun attachReplies(comment: SourceComment, visited: MutableSet<String>): SourceComment {
		if (!visited.add(comment.id)) return comment
		val direct = childrenByParent[comment.id].orEmpty().map { attachReplies(it, visited) }
		val mergedReplies = if (comment.replies.isEmpty()) {
			direct
		} else if (direct.isEmpty()) {
			comment.replies
		} else {
			val existingIds = comment.replies.mapTo(HashSet()) { it.id }
			comment.replies + direct.filter { it.id !in existingIds }
		}
		return comment.copy(
			replies = mergedReplies,
			repliesCount = maxOf(comment.repliesCount, mergedReplies.size),
		)
	}
	val visited = HashSet<String>(flatComments.size)
	return topLevelIds.mapNotNull { id -> byId[id]?.let { attachReplies(it, visited) } }
}
