package org.koitharu.kotatsu.parsers.site.tr

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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

@MangaSourceParser("MANGAAY", "MangaAy", "tr")
internal class MangaAy(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAAY, 45),
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("manga-ay.com")

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
		supportsStats = false,
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
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		when {
			!filter.query.isNullOrEmpty() -> {
				if (page > 1) {
					return emptyList()
				}
				return parseMangaListQueryOrTags(
					webClient.httpPost(
						"https://$domain/arama",
						mapOf("title" to filter.query.urlEncoded(), "genres" to ""),
					).parseHtml(),
				)
			}

			else -> {

				if (filter.tags.isNotEmpty()) {
					filter.tags.oneOrThrowIfMany()?.let {
						if (page > 1) {
							return emptyList()
						}
						return parseMangaListQueryOrTags(
							webClient.httpPost(
								"https://$domain/arama",
								mapOf("title" to "", "genres" to it.key),
							).parseHtml(),
						)
					}
				} else {
					val url = buildString {
						append("https://")
						append(domain)
						append("/seriler")
						if (page > 1) {
							append('/')
							append(page)
						}
					}
					return parseMangaList(webClient.httpGet(url).parseHtml())
				}

			}
		}

		return emptyList()
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.requireElementById("ecommerce-products").select(".card").map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = a.attrAsAbsoluteUrl("href"),
				title = div.selectLast(".item-name")?.text().orEmpty(),
				coverUrl = div.selectFirst("img")?.src(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				description = null,
				state = null,
				authors = emptySet(),
				contentRating = sourceContentRating,
				source = source,
			)
		}
	}

	private fun parseMangaListQueryOrTags(doc: Document): List<Manga> {
		return doc.select(".table tr").map { tr ->
			val a = tr.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = a.attrAsAbsoluteUrl("href"),
				title = a.text(),
				coverUrl = null,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				description = null,
				state = null,
				authors = emptySet(),
				contentRating = sourceContentRating,
				source = source,
			)
		}
	}

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val tagElements = webClient.httpGet("https://$domain/arama").parseHtml()
			.requireElementById("genres").select("option")
		for (option in tagElements) {
			if (option.text().isEmpty()) continue
			tagMap[option.text()] = MangaTag(
				key = option.attr("value"),
				title = option.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
		tagCache = tagMap
		return@withLock tagMap
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT)
		val tagMap = getOrCreateTagMap()
		val tags = doc.select("P.card-text .bg-success").mapNotNullToSet { tagMap[it.text()] }
		return manga.copy(
			description = doc.selectFirst("p.card-text")?.html()?.substringAfterLast("<br>"),
			coverUrl = doc.selectFirst("div.align-items-center div.align-items-center img")?.src()
				?: manga.coverUrl,
			tags = tags,
			chapters = doc.requireElementById("sonyuklemeler").select("tbody tr")
				.mapChapters(reversed = true) { i, tr ->
					val a = tr.selectFirstOrThrow("a")
					val href = a.attrAsRelativeUrl("href")
					MangaChapter(
						id = generateUid(href),
						title = a.text(),
						number = i + 1f,
						volume = 0,
						url = href,
						scanlator = null,
						uploadDate = dateFormat.parseSafe(tr.selectFirstOrThrow("time").attr("datetime")),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select("div.mt-2 img").map { img ->
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
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
			?: return SourceMangaCommunityInfo()

		val rawRating = doc.selectFirst("[itemprop=ratingValue], .rating-value, .score")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating) }
		val scale = if (rawRating != null && rawRating > 5f) 10 else 5
		val ratingCount = doc.selectFirst("[itemprop=ratingCount], .rating-count")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }
		val commentCount = doc.selectFirst("#wpdcom .wpd-thread-info[data-comments-count], .comments-count")
			?.let { el -> (el.attrOrNull("data-comments-count") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = scale,
			ratingCount = ratingCount,
			commentCount = if (chapter == null) commentCount else null,
			chapterCommentCount = if (chapter != null) commentCount else null,
			canCurrentUserComment = wpDiscuz.isWpDiscuzPresent(doc) ||
				WidgetDetectors.detect(doc, pageUrl) != null ||
				wpComments.extractPostId(doc) != null,
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
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		if (doc != null) {
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
		}

		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = SourceWidgetContext(
				engine = SourceCommentEngine.CUSTOM_WIDGET,
				pageUrl = pageUrl,
				widgetUrl = pageUrl,
				title = doc?.title()?.nullIfEmpty(),
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
