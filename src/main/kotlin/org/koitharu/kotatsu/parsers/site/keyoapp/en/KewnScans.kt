package org.koitharu.kotatsu.parsers.site.keyoapp.en

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
}
