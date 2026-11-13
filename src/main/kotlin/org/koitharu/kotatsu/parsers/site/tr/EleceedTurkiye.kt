package org.koitharu.kotatsu.parsers.site.tr

import org.jsoup.nodes.Document
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
import org.koitharu.kotatsu.parsers.site.community.MerlinCommentsHelper
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.WordPressCommentsHelper
import org.koitharu.kotatsu.parsers.site.community.WpDiscuzCommunityHelper
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ELECEEDTURKIYE", "Eleceed Türkiye", "tr")
internal class EleceedTurkiye(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.ELECEEDTURKIYE),
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain(
		"www.nabicix.com",
		"eleceedturkiye.com",
		"www.eleceedturkiye.com",
	)

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

	private val merlinComments by lazy {
		MerlinCommentsHelper(
			context = context,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
			localeProvider = { sourceLocale },
		)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.WORDPRESS,
		supportsWidgetFallback = true,
		requiresAuthForRating = false,
		requiresAuthForComment = false,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
			SourceCommentSort.TOP,
		),
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val seriesUrl = "https://$domain/manga/eleceed/"
		val raw = webClient.httpGet(seriesUrl).parseHtml()
		val coverUrl = raw.selectFirst("div.thumb img")?.src()?.nullIfEmpty()
		return listOf(
			Manga(
				id = generateUid(mangaId),
				url = seriesUrl,
				publicUrl = seriesUrl,
				coverUrl = coverUrl,
				title = "Eleceed",
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOf("Son Jae Ho", "ZHENA"),
				state = MangaState.ONGOING,
				source = source,
				contentRating = ContentRating.ADULT,
			),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml()
		return manga.copy(
			description = doc.selectFirst("div.entry-content")?.html(),
			tags = setOf(
				MangaTag("Aksiyon", "", source),
				MangaTag("Drama", "", source),
				MangaTag("Komedi", "", source),
				MangaTag("Süper Güçler", "", source),
			),
			chapters = doc.select("div.eph-num").mapChapters(reversed = true) { _, div ->
				val href = div.selectFirstOrThrow("a").attr("href")
				val title = div.selectFirst("span.chapternum")?.text() ?: "Not found"
				MangaChapter(
					id = generateUid(href),
					title = title,
					number = title?.let { Regex("\\d+").find(it)?.value?.toFloatOrNull() } ?: 0f,
					url = href,
					uploadDate = dateFormat.parseSafe(div.selectFirst("span.chapterdate")?.text()),
					scanlator = null,
					branch = null,
					source = source,
					volume = 0,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()
		val scriptData = doc.select("script")
			.firstOrNull { it.data().contains("ts_reader.run(") }
			?.data()
			?.substringAfter("ts_reader.run(")
			?.substringBeforeLast(");")
			?: error("Script data not found")

		val json = JSONObject(scriptData)
		val sources = json.getJSONArray("sources")
		if (sources.length() == 0) error("No sources found")

		val firstSource = sources.getJSONObject(0)
		val images = firstSource.getJSONArray("images")

		return List(images.length()) { index ->
			val imageUrl = images.getString(index)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl.toAbsoluteUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	private val mangaId = "eleceed"
	private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("tr"))

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		val rawRating = doc.selectFirst(".numscore, .rt .rating .num, [itemprop=ratingValue]")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating) }
		val scale = if (rawRating != null && rawRating > 5f) 10 else if (doc.selectFirst(".numscore") != null) 10 else 5
		val ratingCount = doc.selectFirst("[itemprop=ratingCount], .rating .votes")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }
		val followCount = doc.selectFirst(".bmc")?.textOrNull()?.let(::parseCompactCount)
		val commentCount = doc.selectFirst("#mcp-total-count, #wpdcom .wpd-thread-info[data-comments-count], .comments-title")
			?.let { el -> (el.attrOrNull("data-comments-count") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }

		val hasComments = merlinComments.isMerlinPresent(doc, pageUrl) ||
			wpDiscuz.isWpDiscuzPresent(doc) ||
			WidgetDetectors.detect(doc, pageUrl) != null ||
			doc.selectFirst("#comments, #commentform, .commentlist, ol.comment-list, input[name=comment_post_ID]") != null ||
			wpComments.extractPostId(doc) != null

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = scale,
			ratingCount = ratingCount,
			followCount = followCount,
			commentCount = if (chapter == null) commentCount else null,
			chapterCommentCount = if (chapter != null) commentCount else null,
			canCurrentUserComment = hasComments,
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

		if (merlinComments.isMerlinPresent(doc, pageUrl)) {
			return merlinComments.getComments(doc, pageUrl, page, sort)
		}
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

		val fallback = buildCustomWidget(doc, pageUrl)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = fallback,
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

		if (merlinComments.isMerlinPresent(doc, pageUrl)) {
			return merlinComments.postComment(doc, pageUrl, text, parentComment, isSpoiler)
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.postComment(doc, pageUrl, text, parentComment)
		}
		return wpComments.postComment(doc, pageUrl, text = text, parentComment = parentComment)
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		if (merlinComments.isMerlinPresent(doc, pageUrl)) {
			return merlinComments.editComment(doc, pageUrl, comment, newText)
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.editComment(doc, pageUrl, comment, newText)
		}
		throw UnsupportedOperationException("Editing comments is not supported for this engine")
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		if (merlinComments.isMerlinPresent(doc, pageUrl)) {
			merlinComments.deleteComment(doc, pageUrl, comment)
			return
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			wpDiscuz.deleteComment(doc, pageUrl, comment)
			return
		}
		throw UnsupportedOperationException("Deleting comments is not supported for this engine")
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		if (merlinComments.isMerlinPresent(doc, pageUrl)) {
			return merlinComments.toggleCommentLike(doc, pageUrl, comment, isLike)
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.toggleCommentLike(doc, pageUrl, comment, isLike)
		}
		throw UnsupportedOperationException("Comment voting is not supported for this engine")
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return WidgetDetectors.detect(doc, pageUrl) ?: buildCustomWidget(doc, pageUrl)
	}

	private fun buildCustomWidget(doc: Document?, pageUrl: String): SourceWidgetContext = SourceWidgetContext(
		engine = SourceCommentEngine.CUSTOM_WIDGET,
		pageUrl = pageUrl,
		widgetUrl = pageUrl,
		title = doc?.title()?.nullIfEmpty(),
		baseUrl = pageUrl,
	)

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}
}
