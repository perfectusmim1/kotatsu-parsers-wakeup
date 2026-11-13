package org.koitharu.kotatsu.parsers.site.keyoapp.en

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.DisqusDetector
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.keyoapp.KeyoappParser
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("KEWNSCANS", "KewnScans", "en")
internal class KewnScans(context: MangaLoaderContext) :
	KeyoappParser(context, MangaParserSource.KEWNSCANS, "kewnscans.org"),
	MangaSourceCommunityProvider {

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 5,
		supportsNativeComments = false,
		supportsCommentPosting = false,
		supportsReplies = false,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.DISQUS,
		supportsWidgetFallback = true,
	)

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		val rawRating = doc?.selectFirst("[itemprop=ratingValue], .rating-value, .score")
			?.let { (it.attrOrNull("content") ?: it.textOrNull())?.let(::parseFloatRating) }
		val scale = if (rawRating != null && rawRating > 5f) 10 else 5
		val viewCount = doc?.selectFirst("[data-views], .views-count, div[alt=Views]")
			?.textOrNull()
			?.let(::parseCompactCount)
		val followCount = doc?.selectFirst("[data-bookmarks], .bookmark-count, .followers-count")
			?.textOrNull()
			?.let(::parseCompactCount)

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = scale,
			viewCount = viewCount,
			followCount = followCount,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val widget = resolveWidgetContext(manga, chapter)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = widget,
			engine = widget.engine,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		if (doc != null) {
			WidgetDetectors.detect(doc, pageUrl)?.let { return it }
		}
		val identifier = (chapter?.url ?: manga.url).takeIf { it.startsWith("/") } ?: "/${manga.url}"
		val shortname = "kewnscans"
		return SourceWidgetContext(
			engine = SourceCommentEngine.DISQUS,
			pageUrl = pageUrl,
			widgetUrl = "https://$shortname.disqus.com/embed.js",
			identifier = identifier,
			shortname = shortname,
			title = doc?.title()?.nullIfEmpty() ?: manga.title,
			inlineHtml = DisqusDetector.buildInlineHtml(
				shortname = shortname,
				pageUrl = pageUrl,
				identifier = identifier,
				title = manga.title,
			),
			baseUrl = pageUrl,
			allowedHostSuffixes = DisqusDetector.ALLOWED_HOST_SUFFIXES,
		)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		val contentType = response.header("Content-Type").orEmpty()
		if (chain.request().url.host == "cdn.meowing.org" && contentType.startsWith("text/plain", ignoreCase = true)) {
			val bytes = response.use { it.body.bytes() }
			val detectedMime = detectImageMimeType(bytes) ?: return response.newBuilder()
				.body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
				.build()
			return response.newBuilder()
				.header("Content-Type", detectedMime)
				.body(bytes.toResponseBody(detectedMime.toMediaTypeOrNull()))
				.build()
		}
		return response
	}

	private fun detectImageMimeType(bytes: ByteArray): String? {
		if (bytes.size >= 4 &&
			bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
			bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
		) {
			return "image/png"
		}
		if (bytes.size >= 3 &&
			bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
		) {
			return "image/jpeg"
		}
		if (bytes.size >= 12 &&
			bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
			bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
			bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
			bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
		) {
			return "image/webp"
		}
		if (bytes.size >= 4 &&
			bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
			bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
		) {
			return "image/gif"
		}
		return null
	}
}
