package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
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
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ASURASCANS", "AsuraComic", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20),
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("asurascans.com")

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = false,
		supportsReplies = true,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(SourceCommentSort.NEWEST, SourceCommentSort.TOP),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RATING,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags,
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/browse".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())

			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("search", filter.query)
			}

			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			}

			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"status",
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"types",
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.RATING -> "rating"
					SortOrder.UPDATED -> ""
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL_DESC -> "desc"
					SortOrder.ALPHABETICAL -> "asc"
					else -> "update"
				},
			)
		}.build()
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("#series-grid .series-card").mapNotNull { card ->
			val link = card.selectFirst("a[href*=/comics/]") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href").stripSlugToken()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = card.selectFirst("img")?.src(),
				title = card.selectFirst("h3")?.text()?.trim().orEmpty(),
				altTitles = emptySet(),
				rating = card.selectFirst("div.absolute.top-2.right-2 span")?.text()?.toFloatOrNull() ?: RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = when (card.select("div.p-3 span").lastOrNull()?.text()?.trim()?.lowercase(Locale.ENGLISH)) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"dropped" -> MangaState.ABANDONED
					"coming soon" -> MangaState.UPCOMING
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private val availableTags by lazy(LazyThreadSafetyMode.NONE) {
		ASURA_GENRES.mapTo(LinkedHashSet(ASURA_GENRES.size)) { title ->
			MangaTag(
				key = title.toAsuraGenreKey(),
				title = title,
				source = source,
			)
		}
	}

	private val tagMap by lazy(LazyThreadSafetyMode.NONE) {
		availableTags.associateByTo(LinkedHashMap(availableTags.size)) {
			it.title.lowercase(Locale.ENGLISH)
		}
	}

	private val regexDate = """(\d+)(st|nd|rd|th)""".toRegex()
	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val selectTag = doc.select("div[class^=space] > div.flex > button.text-white")
		val tags = selectTag.mapNotNullToSet { element ->
			val title = element.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
			tagMap[title.lowercase(Locale.ENGLISH)] ?: MangaTag(
				key = title.toAsuraGenreKey(),
				title = title,
				source = source,
			)
		}
		val author = doc.selectFirst("div.grid > div:has(h3:eq(0):containsOwn(Author)) > h3:eq(1)")?.text().orEmpty()
		val cutoffTime = System.currentTimeMillis() - CHAPTER_HIDE_WINDOW_MS
		return manga.copy(
			title = doc.selectFirst("article h1")
				?.text()
				?.trim()
				?.takeIf { it.isNotEmpty() }
				?: manga.title,
			altTitles = doc.selectFirst("#alt-titles")
				?.text()
				.orEmpty()
				.split('•', '\n')
				.mapNotNullToSet { it.trim().nullIfEmpty() },
			description = doc.selectFirst("#description-text")
				?.html()
				?.trim()
				?.takeIf { it.isNotEmpty() }
				?: doc.selectFirst("span.font-medium.text-sm")?.text().orEmpty(),
			tags = tags,
			authors = setOf(author),
			chapters = doc.select("a.group[href*=/chapter/]").mapChapters(reversed = true) { i, a ->
				val urlRelative = a.attrAsRelativeUrl("href").stripSlugToken()
				val titleElement = a.selectFirst("span.font-medium") ?: a.selectFirst("span")
				val chapterLabel = titleElement?.text()?.trim()?.takeIf { it.isNotEmpty() }
				val chapterTitle = a.selectFirst("span.text-sm.text-white\\/50")
					?.text()
					?.trim()
					?.takeIf { it.isNotEmpty() }
				val fullTitle = when {
					chapterLabel != null && chapterTitle != null -> "$chapterLabel - $chapterTitle"
					chapterLabel != null -> chapterLabel
					else -> chapterTitle
				}
				val chapterNumber = chapterNumberRegex.find(chapterLabel.orEmpty())
					?.groupValues
					?.getOrNull(1)
					?.toFloatOrNull()
					?: i + 1f
				val dateText = a.selectFirst("span.text-sm.text-white\\/40")
					?.text()
					?.trim()
				MangaChapter(
					id = generateUid(urlRelative),
					title = fullTitle,
					number = chapterNumber,
					volume = 0,
					url = urlRelative,
					scanlator = null,
					uploadDate = parseChapterDate(chapterDateFormat, dateText),
					branch = null,
					source = source,
				)
			}.filter { chapter ->
				chapter.uploadDate == 0L || chapter.uploadDate <= cutoffTime
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		doc.selectFirst("astro-island[component-url*='ChapterReader']")?.attr("props")?.let { props ->
			pageUrlRegex.findAll(props.replace("&quot;", "\""))
				.map { it.groupValues[1] }
				.distinct()
				.toList()
				.takeIf { it.isNotEmpty() }
				?.let { urls ->
					return urls.map { url ->
						MangaPage(
							id = generateUid(url),
							url = url,
							preview = null,
							source = source,
						)
					}
				}
		}

		val scripts = doc.selectOrThrow("script")
		val sb = StringBuilder()
		for (script in scripts) {
			val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { sb.append(it) }
			}
		}
		val lines = sb.toString().split('\n')
		val pages = TreeMap<Int, String>()
		for (line in lines) {
			val obj = line.substringAfter(':').toJSONObjectOrNull() ?: continue
			if (obj.has("order") && obj.has("url")) {
				pages[obj.getInt("order")] = obj.getString("url")
			}
		}
		return pages.values.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun String.toAsuraGenreKey(): String {
		return trim()
			.lowercase(Locale.ENGLISH)
			.replace(asuraGenreKeyRegex, "-")
			.trim('-')
	}

	private fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
		val value = date?.trim().orEmpty()
		if (value.isEmpty()) return 0L
		val lower = value.lowercase(Locale.ENGLISH)
		return when {
			lower == "last week" -> Calendar.getInstance().apply {
				add(Calendar.WEEK_OF_YEAR, -1)
			}.timeInMillis

			lower == "yesterday" -> Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
			}.timeInMillis

			lower.endsWith(" ago") -> parseRelativeDate(lower)
			else -> synchronized(dateFormat) {
				dateFormat.parseSafe(value.replace(regexDate, "$1"))
			}
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("second", "seconds").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("minute", "minutes", "min", "mins").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("hour", "hours").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("day", "days").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("week", "weeks").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("month", "months").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("year", "years").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	/**
	 * Asura appends the same site-wide token to every series slug
	 * (`/comics/<slug>-00dcbf97`, identical across all series) and rotates it
	 * whenever the site is redeployed — which is exactly when new chapters go
	 * up. Any id derived from a url containing it therefore changes for every
	 * chapter of every series at once, so chapters already downloaded come back
	 * as new, undownloaded ones.
	 *
	 * The token is not needed to reach anything: the server answers a slug with
	 * a missing or stale token with a 302 to the current url, so it is dropped
	 * from the stored url and, with it, from the generated ids.
	 */
	private fun String.stripSlugToken(): String = replace(slugTokenRegex, "")

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val seriesObj = resolveSeriesApiObject(manga)
		val seriesId = seriesObj?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
			?: extractSeriesIdFromHtml(manga)

		var rawRating = seriesObj?.getFloatOrDefault("rating", -1f)?.takeIf { it > 0f }
		var followCount = seriesObj?.getLongOrDefault("bookmark_count", -1L)?.takeIf { it >= 0L }
		var viewCount: Long? = null

		if (rawRating == null || followCount == null) {
			val doc = runCatching { webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml() }.getOrNull()
			if (doc != null) {
				if (rawRating == null) {
					rawRating = doc.selectFirst(".num, [itemprop=ratingValue]")?.textOrNull()?.let(::parseFloatRating)
				}
				if (followCount == null) {
					followCount = doc.selectFirst(".bmc, [data-bookmark-count]")?.textOrNull()?.let(::parseCompactCount)
				}
				viewCount = doc.selectFirst(".views, [data-views]")?.textOrNull()?.let(::parseCompactCount)
			}
		}

		val totalComments = if (seriesId != null) {
			runCatching {
				val apiHost = getDomain("api")
				webClient.httpGet("https://$apiHost/api/series/$seriesId/comments?sort=newest&page=1")
					.parseJson()
					.optJSONObject("meta")
					?.getIntOrDefault("total", -1)
					?.takeIf { it >= 0 }
			}.getOrNull()
		} else {
			null
		}

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = if (chapter == null) totalComments else null,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val apiHost = getDomain("api")
		val sortParam = if (sort == SourceCommentSort.TOP) "top" else "newest"
		val seriesObj = resolveSeriesApiObject(manga)
		val seriesId = seriesObj?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
			?: extractSeriesIdFromHtml(manga)

		val chapterId = if (chapter != null && seriesObj != null) {
			resolveChapterId(seriesObj, chapter)
		} else {
			null
		}

		val endpoint = when {
			chapter != null && chapterId != null ->
				"https://$apiHost/api/chapters/$chapterId/comments?sort=$sortParam&page=$page"
			chapter == null && seriesId != null ->
				"https://$apiHost/api/series/$seriesId/comments?sort=$sortParam&page=$page"
			chapter != null && seriesId != null ->
				"https://$apiHost/api/series/$seriesId/comments?sort=$sortParam&page=$page"
			else -> null
		}

		if (endpoint != null) {
			val responseJson = runCatching { webClient.httpGet(endpoint).parseJson() }.getOrNull()
			if (responseJson != null && responseJson.has("data")) {
				val comments = responseJson.optJSONArray("data")?.mapJSONNotNull(::parseAsuraComment).orEmpty()
				val meta = responseJson.optJSONObject("meta")
				val hasMore = meta?.getBooleanOrDefault("has_more", false) == true
				val total = meta?.getIntOrDefault("total", -1)?.takeIf { it >= 0 }
				return SourceCommentPage(
					comments = comments,
					page = page,
					hasNextPage = hasMore,
					totalCount = total,
					engine = SourceCommentEngine.NATIVE,
				)
			}
		}

		val widget = resolveWidgetContext(manga, chapter)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = widget,
			engine = widget.engine,
		)
	}

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage {
		val apiHost = getDomain("api")
		val replies = runCatching {
			val raw = webClient.httpGet("https://$apiHost/api/comments/${comment.id}/replies").parseRaw().trim()
			val arr = raw.toJSONArrayOrNull()
				?: raw.toJSONObjectOrNull()?.optJSONArray("data")
				?: JSONArray()
			arr.mapJSONNotNull { parseAsuraComment(it, parentId = comment.id) }
		}.getOrElse { emptyList() }
		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = false,
			engine = SourceCommentEngine.NATIVE,
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
		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSTOM_WIDGET,
			pageUrl = pageUrl,
			widgetUrl = pageUrl,
			title = doc?.title()?.nullIfEmpty() ?: manga.title,
			baseUrl = pageUrl,
		)
	}

	private suspend fun resolveSeriesApiObject(manga: Manga): JSONObject? {
		val apiHost = getDomain("api")
		val rawSlug = manga.url.trimEnd('/').substringAfterLast('/').stripSlugToken()
		val queries = listOfNotNull(
			manga.title.trim().takeIf { it.isNotEmpty() },
			rawSlug.replace('-', ' ').trim().takeIf { it.isNotEmpty() },
		).distinct()

		for (q in queries) {
			val arr = runCatching {
				webClient.httpGet("https://$apiHost/api/series?search=${q.urlEncoded()}")
					.parseJson()
					.optJSONArray("data")
			}.getOrNull() ?: continue
			for (i in 0 until arr.length()) {
				val jo = arr.optJSONObject(i) ?: continue
				val pub = jo.getStringOrNull("public_url")?.stripSlugToken()
				val slug = jo.getStringOrNull("slug")
				if (pub == manga.url.stripSlugToken() || slug == rawSlug ||
					jo.getStringOrNull("title").equals(manga.title, ignoreCase = true)
				) {
					return jo
				}
			}
			if (arr.length() > 0) {
				return arr.optJSONObject(0)
			}
		}
		return null
	}

	private suspend fun extractSeriesIdFromHtml(manga: Manga): Long? {
		val doc = runCatching { webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml() }.getOrNull()
			?: return null
		return doc.selectFirst("[data-series-id]")?.attrOrNull("data-series-id")?.toLongOrNull()
	}

	private fun resolveChapterId(seriesObj: JSONObject, chapter: MangaChapter): Long? {
		val arr = seriesObj.optJSONArray("latest_chapters") ?: return null
		val chapSlug = chapter.url.trimEnd('/').substringAfterLast('/')
		for (i in 0 until arr.length()) {
			val cjo = arr.optJSONObject(i) ?: continue
			val slug = cjo.getStringOrNull("slug")
			val num = cjo.getFloatOrDefault("number", -1f)
			if ((slug != null && slug == chapSlug) || (num >= 0f && num == chapter.number)) {
				return cjo.getLongOrDefault("id", 0L).takeIf { it > 0L }
			}
		}
		return null
	}

	private fun parseAsuraComment(jo: JSONObject, parentId: String? = null): SourceComment? {
		val id = jo.getStringOrNull("id") ?: return null
		val user = jo.optJSONObject("user")
		val authorName = user?.getStringOrNull("username") ?: "Anonymous"
		val avatarUrl = user?.getStringOrNull("profile_picture_url")
		val role = user?.getStringOrNull("role")?.takeUnless { it.equals("user", ignoreCase = true) }
		val createdAt = jo.getStringOrNull("created_at")
		val userVote = when (jo.getStringOrNull("user_vote")?.lowercase(Locale.ROOT)) {
			"up", "upvote", "like", "1" -> 1
			"down", "downvote", "dislike", "-1" -> -1
			else -> 0
		}
		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = role,
			body = jo.getStringOrNull("content").orEmpty(),
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isEdited = jo.getBooleanOrDefault("is_edited", false),
			isPinned = jo.getBooleanOrDefault("is_pinned", false),
			likesCount = jo.getIntOrDefault("upvotes", 0),
			dislikesCount = jo.getIntOrDefault("downvotes", 0),
			userVote = userVote,
			repliesCount = jo.getIntOrDefault("reply_count", 0),
			parentId = parentId,
			canReply = false,
		)
	}

	private companion object {
		private const val CHAPTER_HIDE_WINDOW_MS = 6L * 60L * 60L * 1000L
		private val slugTokenRegex = Regex("""-[0-9a-f]{8}(?=/|$)""")
		private val chapterNumberRegex = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		private val pageUrlRegex = Regex(""""url":\s*\[0,\s*"([^"]+)"""")
		private val ASURA_GENRES = listOf(
			"Action",
			"Adventure",
			"Comedy",
			"Crazy MC",
			"Demon",
			"Dungeons",
			"Fantasy",
			"Game",
			"Genius MC",
			"Isekai",
			"Magic",
			"Murim",
			"Mystery",
			"Necromancer",
			"Overpowered",
			"Regression",
			"Reincarnation",
			"Revenge",
			"Romance",
			"School Life",
			"Sci-fi",
			"Shoujo",
			"Shounen",
			"System",
			"Tower",
			"Tragedy",
			"Villain",
			"Violence",
		)
		private val asuraGenreKeyRegex = Regex("[^a-z0-9]+")
	}
}
