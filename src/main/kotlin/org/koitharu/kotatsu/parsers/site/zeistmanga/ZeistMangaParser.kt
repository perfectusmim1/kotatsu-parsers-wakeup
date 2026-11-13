package org.koitharu.kotatsu.parsers.site.zeistmanga

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.ErrorMessages
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
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
import org.koitharu.kotatsu.parsers.site.community.CusdisDetector
import org.koitharu.kotatsu.parsers.site.community.DisqusDetector
import org.koitharu.kotatsu.parsers.site.community.GraphCommentDetector
import org.koitharu.kotatsu.parsers.site.community.HyvorTalkDetector
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.nestCommentsByParentId
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.text.SimpleDateFormat
import java.util.*

internal abstract class ZeistMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 12,
) : PagedMangaParser(context, source, pageSize), MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain(domain)

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
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
	)

	@JvmField
	protected val ongoing: Set<String> = hashSetOf(
		"ongoing",
		"en curso",
		"ativo",
		"lançando",
		"مستمر",
		"devam ediyor",
		"güncel",
		"en emisión",
	)

	@JvmField
	protected val finished: Set<String> = hashSetOf(
		"completed",
		"completo",
		"tamamlandı",
		"finalizado",
	)

	@JvmField
	protected val abandoned: Set<String> = hashSetOf(
		"cancelled",
		"dropped",
		"dropado",
		"abandonado",
		"cancelado",
		"suspendido",
	)

	@JvmField
	protected val paused: Set<String> = hashSetOf(
		"hiatus",
	)

	protected open val sateOngoing: String = "Ongoing"
	protected open val sateFinished: String = "Completed"
	protected open val sateAbandoned: String = "Cancelled"

	protected open val maxMangaResults: Int = 20

	protected open val mangaCategory: String = "Series"

	protected open val datePattern = "yyyy-MM-dd"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val startIndex = maxMangaResults * (page - 1) + 1

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			when {

				!filter.query.isNullOrEmpty() -> {
					append(mangaCategory)
					append("?alt=json&orderby=published&max-results=")
					append((maxMangaResults + 1).toString())
					append("&start-index=")
					append(startIndex.toString())
					append("&q=label:")
					append(mangaCategory)
					append("+")
					append(filter.query.urlEncoded())
				}

				else -> {


					if (filter.tags.isNotEmpty() && filter.states.isNotEmpty()) {
						throw IllegalArgumentException(ErrorMessages.FILTER_BOTH_STATES_GENRES_NOT_SUPPORTED)
					}

					if (filter.tags.isNotEmpty()) {
						append(filter.tags.oneOrThrowIfMany()?.key.orEmpty())
					} else if (filter.states.isNotEmpty()) {
						append(
							filter.states.oneOrThrowIfMany().let {
								when (it) {
									MangaState.ONGOING -> sateOngoing
									MangaState.FINISHED -> sateFinished
									MangaState.ABANDONED -> sateAbandoned
									else -> mangaCategory
								}
							},
						)
					} else {
						append(mangaCategory)
					}

					append("?alt=json&orderby=published&max-results=")
					append((maxMangaResults + 1).toString())
					append("&start-index=")
					append(startIndex.toString())
				}
			}
		}

		val json = webClient.httpGet(url).parseJson().getJSONObject("feed")

		return if (json.toString().contains("\"entry\":")) {
			parseMangaList(json.getJSONArray("entry"))
		} else {
			emptyList()
		}
	}

	protected open fun parseMangaList(json: JSONArray): List<Manga> {
		return json.mapJSON { j ->
			val name = j.getJSONObject("title").getString("\$t")
			val href =
				j.getJSONArray("link").asTypedList<JSONObject>().first { it.getString("rel") == "alternate" }
					.getString("href")
			val urlImg = if (j.toString().contains("media\$thumbnail")) {
				j.getJSONObject("media\$thumbnail").getStringOrNull("url")
					?.replace("""/s.+?-c/""".toRegex(), "/w600/")
					?.replace("""=s(?!.*=s).+?-c$""".toRegex(), "=w600")
					?.replace("""/s.+?-c-rw/""".toRegex(), "/w600/")
					?.replace("""=s(?!.*=s).+?-c-rw$""".toRegex(), "=w600")
			} else {
				Jsoup.parse(j.getJSONObject("content").getString("\$t")).selectFirst("img")?.attr("src")
			}
			Manga(
				id = generateUid(href),
				url = href.toRelativeUrl(domain),
				publicUrl = href,
				coverUrl = urlImg.orEmpty(),
				title = name,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.selectFirstOrThrow("div.filter").select("ul li").mapToSet {
			MangaTag(
				key = it.selectFirstOrThrow("input").attr("value"),
				title = it.selectFirstOrThrow("label").text().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	protected open val selectTags = "article div.mt-15 a, .info-genre a, dl:contains(Genre) dd a"
	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val state =
			doc.selectFirst("div.y6x11p:contains(Status) .dt")
				?: doc.selectFirst("div.y6x11p:contains(Estado) .dt")
				?: doc.selectFirst("ul.infonime li:contains(Status) span")
				?: doc.selectFirst("ul.infonime li:contains(Estado) span")
				?: doc.selectFirst("span.status-novel")
				?: doc.selectFirst("span[data-status]")
		val mangaState = state?.text()?.lowercase().let {
			when (it) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}
		val author = doc.selectFirst("div.y6x11p:contains(الكاتب) .dt")
			?: doc.selectFirst("div.y6x11p:contains(Author) .dt")
			?: doc.selectFirst("dl:contains(Author) dd")
			?: doc.selectFirst("div.y6x11p:contains(Autor) .dt")
			?: doc.selectFirst("div.y6x11p:contains(Yazar) .dt")
			?: doc.selectFirst("ul.infonime li:contains(Author) span")


		val desc = doc.getElementById("synopsis") ?: doc.getElementById("Sinopse") ?: doc.getElementById("sinopas")
		?: doc.selectFirst(".sinopsis") ?: doc.selectFirst(".sinopas")
		val chaptersDeferred = async { loadChapters(manga.url, doc) }
		manga.copy(
			authors = author?.text()?.let { setOf(it) } ?: emptySet(),
			tags = doc.select(selectTags).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("label/").substringBefore("?"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc?.text().orEmpty(),
			state = mangaState,
			chapters = chaptersDeferred.await(),
		)
	}

	protected open suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {

		val feed = if (doc.getElementById("myUL") != null) {
			doc.requireElementById("myUL").selectFirstOrThrow("script").attr("src")
				.substringAfterLast("/-/").substringBefore("?").urlDecode()

		} else if (doc.selectFirst("#latest > script") != null) {
			val chapterRegex = """label\s*=\s*'([^']+)'""".toRegex()
			val scriptSelector = "#latest > script"
			val script = doc.selectFirstOrThrow(scriptSelector)
			chapterRegex
				.find(script.html())
				?.groupValues?.get(1)
				?: doc.parseFailed("Failed to find chapter feed")

		} else if (doc.selectFirst("#clwd > script") != null) {
			val chapterRegex = """clwd\.run\('([^']+)'""".toRegex()
			val scriptSelector = "#clwd > script"
			val script = doc.selectFirstOrThrow(scriptSelector)
			chapterRegex
				.find(script.html())
				?.groupValues?.get(1)
				?: doc.parseFailed("Failed to find chapter feed")

		} else if (doc.selectFirst("#chapterlist") != null) {
			doc.selectFirstOrThrow("#chapterlist").attr("data-post-title")
		} else {
			doc.selectFirstOrThrow("script:containsData(var label_chapter)").data()
				.substringAfter("label_chapter = \"").substringBefore("\"")
		}

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			append(feed)
			append("?alt=json&orderby=published&max-results=9999")
		}
		val json =
			webClient.httpGet(url).parseJson().getJSONObject("feed").getJSONArray("entry").asTypedList<JSONObject>()
				.reversed()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return json.mapIndexedNotNull { i, j ->
			val name = j.getJSONObject("title").getString("\$t")
			val href =
				j.getJSONArray("link").asTypedList<JSONObject>().first { it.getString("rel") == "alternate" }
					.getString("href")
			val dateText = j.getJSONObject("published").getString("\$t").substringBefore("T")
			val slug = mangaUrl.substringAfterLast('/')
			val slugChapter = href.substringAfterLast('/')
			if (slug == slugChapter) {
				return@mapIndexedNotNull null
			}
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = dateFormat.parseSafe(dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	protected open val selectPage =
		"div.check-box img, article#reader .separator img, article.container .separator img, #readarea img, #reader img, #readerarea img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return if (doc.selectFirst("script:containsData(chapterImage =)") != null) {
			doc.selectFirstOrThrow("script:containsData(chapterImage =)").data()
				.substringAfter("[").substringBefore("]")
				.replace(" ", "").replace("\"", "")
				.split(",").map { url ->
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}

		} else if (doc.selectFirst("script:containsData(const content = )") != null) {
			doc.selectFirstOrThrow("script:containsData(const content = )").data()
				.substringAfter("`").substringBefore("`;").split("src=\"").drop(1)

				.map { img ->
					val url = img.substringBefore("\"")
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}

		} else {
			doc.select(selectPage).map { img ->
				val url = img.requireSrc()
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = false,
		supportsReplies = true,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsCommentEditing = false,
		supportsCommentDeleting = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = false,
		requiresAuthForComment = false,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
		),
	)

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolveCommunityPageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return parseCommunityInfoFromDoc(doc, pageUrl)
	}

	internal suspend fun parseCommunityInfoFromDoc(
		doc: Document,
		pageUrl: String = doc.location(),
	): SourceMangaCommunityInfo {
		val ratingEl = doc.selectFirst(".rating [itemprop=ratingValue]")
			?: doc.selectFirst("[itemprop=ratingValue]")
		val rawRating = ratingEl?.let { el ->
			(el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating)
		}
			?: doc.selectFirst(".rating-prc .num, .rating .num, .numscore")
				?.let { el ->
					(el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating)
				}
			?: doc.selectFirst(".cp1_numscore")
				?.textOrNull()
				?.takeUnless { '$' in it }
				?.let(::parseFloatRating)

		val bestScale = doc.selectFirst("[itemprop=bestRating]")?.let { el ->
			(el.attrOrNull("content") ?: el.textOrNull())?.toIntOrNull()
		}?.takeIf { it in 1..10 } ?: 10

		val ratingCount = doc.selectFirst("meta[itemprop=ratingCount], [itemprop=ratingCount]")?.let { el ->
			(el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() }
		}
			?: doc.selectFirst(".rating .votes, .rating-prc .votes")
				?.textOrNull()?.let { parseCompactCount(it)?.toInt() }

		val followCount = doc.selectFirst(".bmc, .follow-count")
			?.textOrNull()?.let(::parseCompactCount)

		val postId = extractBloggerPostId(doc)
		val widget = detectWidgetContextFromDoc(doc, pageUrl)
		var commentCount = doc.selectFirst(".comments-count, .comment-count, a[href*=#comment-form]")
			?.textOrNull()?.let { parseCompactCount(it)?.toInt() }

		if (commentCount == null && widget == null && postId != null) {
			commentCount = fetchBloggerCommentCount(postId)
		}

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / bestScale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = bestScale,
			ratingCount = ratingCount,
			followCount = followCount,
			commentCount = commentCount,
			canCurrentUserRate = false,
			canCurrentUserComment = widget != null,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val pageUrl = resolveCommunityPageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val widget = detectWidgetContextFromDoc(doc, pageUrl)
		if (widget != null) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				widgetContext = widget,
				engine = widget.engine,
			)
		}

		val postId = extractBloggerPostId(doc)
			?: return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NONE,
			)

		return fetchBloggerComments(postId, page, sort)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = resolveCommunityPageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return detectWidgetContextFromDoc(doc, pageUrl)
	}

	internal fun extractBloggerPostId(doc: Document): String? {
		for (link in doc.select("link[rel=alternate][href*=/comments/]")) {
			val href = link.attrOrNull("href") ?: continue
			REGEX_FEED_POST_ID.find(href)?.groupValues?.getOrNull(1)?.let { return it }
		}
		doc.selectFirst("[data-post-id]")?.attrOrNull("data-post-id")
			?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
			?.let { return it }
		doc.selectFirst(".post-outer[data-id], input[name=postID]")?.let { el ->
			(el.attrOrNull("data-id") ?: el.attrOrNull("value"))
				?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
				?.let { return it }
		}
		doc.selectFirst("meta[itemprop=postId], meta[name=post-id]")?.attrOrNull("content")
			?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
			?.let { return it }
		doc.selectFirst("[id^=post-body-]")?.id()?.removePrefix("post-body-")
			?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
			?.let { return it }
		doc.selectFirst(".post a[name], .post-outer a[name], article a[name]")?.attrOrNull("name")
			?.takeIf { it.all(Char::isDigit) && it.length >= 6 }
			?.let { return it }
		doc.selectFirst("iframe[src*=blogger.com/comment]")?.attrOrNull("src")?.toHttpUrlOrNull()?.let { url ->
			(url.queryParameter("po") ?: url.queryParameter("postID"))
				?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
				?.let { return it }
		}
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		return REGEX_JS_POST_ID.find(scriptsText)?.groupValues?.getOrNull(1)
	}

	private suspend fun fetchBloggerCommentCount(postId: String): Int? {
		val url = "https://$domain/feeds/$postId/comments/default?alt=json&max-results=0"
		return runCatchingCancellable {
			val json = webClient.httpGet(url).parseJson()
			json.optJSONObject("feed")
				?.optJSONObject("openSearch\$totalResults")
				?.getStringOrNull("\$t")
				?.toIntOrNull()
		}.getOrNull()
	}

	private suspend fun fetchBloggerComments(
		postId: String,
		page: Int,
		sort: SourceCommentSort,
	): SourceCommentPage {
		val safePage = page.coerceAtLeast(1)
		val startIndex = (safePage - 1) * COMMENT_PAGE_SIZE + 1
		val url = "https://$domain/feeds/$postId/comments/default?alt=json&orderby=published&max-results=$COMMENT_PAGE_SIZE&start-index=$startIndex"

		val json = runCatchingCancellable {
			webClient.httpGet(url).parseJson()
		}.getOrNull()
			?: return SourceCommentPage(
				comments = emptyList(),
				page = safePage,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)

		return parseBloggerCommentsJson(json, postId, safePage, startIndex, sort)
	}

	internal fun parseBloggerCommentsJson(
		json: JSONObject,
		postId: String,
		page: Int = 1,
		startIndex: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
	): SourceCommentPage {
		val feed = json.optJSONObject("feed")
			?: return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)

		val totalCount = feed.optJSONObject("openSearch\$totalResults")
			?.getStringOrNull("\$t")
			?.toIntOrNull()
		val entries = feed.optJSONArray("entry")
			?: return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				totalCount = totalCount,
				engine = SourceCommentEngine.NATIVE,
			)

		val flat = ArrayList<SourceComment>(entries.length())
		for (i in 0 until entries.length()) {
			val entry = entries.optJSONObject(i) ?: continue
			parseBloggerCommentEntry(entry, postId)?.let(flat::add)
		}

		val nested = nestCommentsByParentId(flat)
		val sorted = if (sort == SourceCommentSort.OLDEST) {
			sortCommentsOldestFirst(nested)
		} else {
			nested
		}

		val hasNext = if (totalCount != null) {
			(startIndex - 1 + entries.length()) < totalCount
		} else {
			entries.length() >= COMMENT_PAGE_SIZE
		}

		return SourceCommentPage(
			comments = sorted,
			page = page,
			hasNextPage = hasNext,
			totalCount = totalCount,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	private fun parseBloggerCommentEntry(entry: JSONObject, postId: String): SourceComment? {
		val rawId = entry.optJSONObject("id")?.getStringOrNull("\$t")
			?.substringAfterLast("post-")
			?.substringAfterLast('/')
			?.nullIfEmpty()
			?: return null

		val published = entry.optJSONObject("published")?.getStringOrNull("\$t")
			?: entry.optJSONObject("updated")?.getStringOrNull("\$t")

		val contentHtml = entry.optJSONObject("content")?.getStringOrNull("\$t")?.nullIfEmpty()
		val titleText = entry.optJSONObject("title")?.getStringOrNull("\$t")?.trim()?.nullIfEmpty()
		val bodyText = contentHtml?.let { Jsoup.parseBodyFragment(it).text().trim().nullIfEmpty() }
			?: titleText
			?: ""
		if (bodyText.isEmpty() && contentHtml == null) return null

		val authorObj = entry.optJSONArray("author")?.optJSONObject(0)
		val authorName = authorObj?.optJSONObject("name")?.getStringOrNull("\$t")?.nullIfEmpty() ?: "Guest"
		val avatarUrl = authorObj?.optJSONObject("gd\$image")?.getStringOrNull("src")
			?.nullIfEmpty()
			?.takeUnless { "b16-rounded.gif" in it || "blank.gif" in it }

		var parentId: String? = null
		val extProps = entry.optJSONArray("gd\$extendedProperty")
		if (extProps != null) {
			for (j in 0 until extProps.length()) {
				val prop = extProps.optJSONObject(j) ?: continue
				val name = prop.getStringOrNull("name")
				if (name == "blogger.inReplyTo" || name == "inReplyTo") {
					parentId = prop.getStringOrNull("value")
						?.substringAfterLast("post-")
						?.nullIfEmpty()
						?.takeIf { it != postId && it != rawId }
					if (parentId != null) break
				}
			}
		}
		if (parentId == null) {
			parentId = entry.optJSONObject("thr\$in-reply-to")
				?.getStringOrNull("ref")
				?.substringAfterLast("post-")
				?.nullIfEmpty()
				?.takeIf { it != postId && it != rawId }
		}

		return SourceComment(
			id = rawId,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			body = bodyText,
			bodyHtml = contentHtml,
			createdAtEpochMs = parseIsoTimestamp(published),
			createdAtText = published,
			parentId = parentId,
			canReply = false,
		)
	}

	private fun sortCommentsOldestFirst(comments: List<SourceComment>): List<SourceComment> {
		return comments
			.sortedBy { it.createdAtEpochMs ?: 0L }
			.map { c ->
				if (c.replies.isEmpty()) c else c.copy(replies = sortCommentsOldestFirst(c.replies))
			}
	}

	private fun detectWidgetContextFromDoc(doc: Document, pageUrl: String): SourceWidgetContext? {
		detectGraphComment(doc, pageUrl)?.let { return it }
		detectDisqus(doc, pageUrl)?.let { return it }
		HyvorTalkDetector.detect(doc, pageUrl)?.let { return it }
		CusdisDetector.detect(doc, pageUrl)?.let { return it }
		return WidgetDetectors.detect(doc, pageUrl)
	}

	private fun detectGraphComment(doc: Document, pageUrl: String): SourceWidgetContext? {
		val detected = GraphCommentDetector.detect(doc, pageUrl) ?: return null
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		val camelId = REGEX_GC_CAMEL_ID.find(scriptsText)?.groupValues?.getOrNull(1)?.nullIfEmpty()
		if (camelId != null && detected.shortname != camelId) {
			val identifier = detected.identifier ?: camelId
			return detected.copy(
				shortname = camelId,
				widgetUrl = "https://graphcomment.com/js/integration.js?graphcomment_id=$camelId",
				inlineHtml = GraphCommentDetector.buildInlineHtml(
					websiteId = camelId,
					identifier = identifier,
					pageUrl = pageUrl,
				),
			)
		}
		return detected
	}

	private fun detectDisqus(doc: Document, pageUrl: String): SourceWidgetContext? {
		DisqusDetector.detect(doc, pageUrl)?.let { return it }
		val scriptsAndOnclick = buildString {
			for (s in doc.select("script")) {
				appendLine(s.data())
			}
			for (el in doc.select("[onclick*=disqus]")) {
				appendLine(el.attr("onclick"))
			}
		}
		val shortname = REGEX_LOAD_DISQUS.find(scriptsAndOnclick)?.groupValues?.getOrNull(1)?.nullIfEmpty()
			?: return null
		val resolvedPageUrl = doc.selectFirst("link[rel=canonical]")?.attrOrNull("href") ?: pageUrl
		val title = doc.title().nullIfEmpty()
		return SourceWidgetContext(
			engine = SourceCommentEngine.DISQUS,
			pageUrl = resolvedPageUrl,
			widgetUrl = "https://$shortname.disqus.com/embed.js",
			shortname = shortname,
			title = title,
			inlineHtml = DisqusDetector.buildInlineHtml(
				shortname = shortname,
				pageUrl = resolvedPageUrl,
				title = title,
			),
			baseUrl = resolvedPageUrl,
			allowedHostSuffixes = DisqusDetector.ALLOWED_HOST_SUFFIXES,
		)
	}

	private fun resolveCommunityPageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}

	private companion object {
		const val COMMENT_PAGE_SIZE = 25
		val REGEX_FEED_POST_ID = Regex("""/feeds/(\d+)/comments/""")
		val REGEX_JS_POST_ID = Regex("""['"]postId['"]\s*:\s*['"](\d{6,})['"]""")
		val REGEX_GC_CAMEL_ID = Regex("""graphcommentId\s*[:=]\s*['"]([a-zA-Z0-9_-]+)['"]""")
		val REGEX_LOAD_DISQUS = Regex("""load_disqus\s*\(\s*['"]([a-zA-Z0-9-]+)['"]\s*\)""")
	}
}
