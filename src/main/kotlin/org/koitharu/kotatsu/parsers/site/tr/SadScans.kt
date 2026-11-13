package org.koitharu.kotatsu.parsers.site.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.WordPressCommentsHelper
import org.koitharu.kotatsu.parsers.site.community.WpDiscuzCommunityHelper
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SADSCANS", "SadScans", "tr")
internal class SadScans(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.SADSCANS),
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("sadscans.com")

	private val wpComments by lazy {
		WordPressCommentsHelper(
			context = context,
			webClient = webClient,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
		)
	}

	private val wpDiscuz by lazy {
		WpDiscuzCommunityHelper(
			context = context,
			webClient = webClient,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
		)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.CUSTOM_WIDGET,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = false,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
			SourceCommentSort.TOP,
		),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/series")
			filter.query?.let {
				append("?search=")
				append(filter.query.urlEncoded())
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".series-list").map { div ->
			val href = "/" + div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = div.selectFirstOrThrow("h2").text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = div.selectFirstOrThrow("img").src()?.toAbsoluteUrl(domain).orEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("dd MMM, yy", Locale.ENGLISH)
		val author = doc.selectLast(".author span")?.textOrNull()
		return manga.copy(
			state = when (doc.select(".status span").last()?.text()) {
				"Devam ediyor" -> MangaState.ONGOING
				"Tamamlandı" -> MangaState.FINISHED
				else -> null
			},
			tags = emptySet(),
			authors = setOfNotNull(author),
			description = doc.selectFirstOrThrow(".summary").html(),
			chapters = doc.select(".chap-section .chap")
				.mapChapters(reversed = true) { i, div ->
					val a = div.selectFirstOrThrow("a")
					val url = "/" + a.attrAsRelativeUrl("href")
					MangaChapter(
						id = generateUid(url),
						title = a.textOrNull(),
						number = i + 1f,
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = dateFormat.parseSafe(div.select(".detail span").last()?.text()),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(".swiper-slide img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		val rawRating = doc.selectFirst("[itemprop=ratingValue], .rating-value, .score")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating) }
		val scale = if (rawRating != null && rawRating > 5f) 10 else 5
		val ratingCount = doc.selectFirst("[itemprop=ratingCount], .rating-count")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }
		val commentCount = doc.selectFirst("#wpdcom .wpd-thread-info[data-comments-count], .comments-count, .comment-count")
			?.let { el -> (el.attrOrNull("data-comments-count") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = scale,
			ratingCount = ratingCount,
			commentCount = if (chapter == null) commentCount else null,
			chapterCommentCount = if (chapter != null) commentCount else null,
			canCurrentUserComment = true,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.getComments(doc, pageUrl, page, sort, cursor)
		}
		WidgetDetectors.detect(doc, pageUrl)?.let { widget ->
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				widgetContext = widget,
				engine = widget.engine,
			)
		}
		if (doc.selectFirst("#comments, #commentform, .commentlist, ol.comment-list, input[name=comment_post_ID]") != null ||
			wpComments.extractPostId(doc) != null
		) {
			return wpComments.getComments(doc, pageUrl, page = page, sort = sort)
		}

		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = SourceWidgetContext(
				engine = SourceCommentEngine.CUSTOM_WIDGET,
				pageUrl = pageUrl,
				widgetUrl = pageUrl,
				title = doc.title().nullIfEmpty(),
				baseUrl = pageUrl,
			),
			engine = SourceCommentEngine.CUSTOM_WIDGET,
		)
	}

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.postComment(doc, pageUrl, text, parentComment)
		}
		if (wpComments.extractPostId(doc) != null || doc.selectFirst("form#commentform") != null) {
			return wpComments.postComment(doc, pageUrl, text = text, parentComment = parentComment)
		}
		throw UnsupportedOperationException("Posting comments natively is not supported for this source")
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.editComment(doc, pageUrl, comment, newText)
		}
		throw UnsupportedOperationException("Editing comments is not supported for this source")
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			wpDiscuz.deleteComment(doc, pageUrl, comment)
			return
		}
		throw UnsupportedOperationException("Deleting comments is not supported for this source")
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.toggleCommentLike(doc, pageUrl, comment, isLike)
		}
		throw UnsupportedOperationException("Comment voting is not supported for this source")
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		if (doc != null) {
			WidgetDetectors.detect(doc, pageUrl)?.let { return it }
		}
		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSTOM_WIDGET,
			pageUrl = pageUrl,
			widgetUrl = pageUrl,
			title = doc?.title()?.nullIfEmpty(),
			baseUrl = pageUrl,
		)
	}

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}
}
