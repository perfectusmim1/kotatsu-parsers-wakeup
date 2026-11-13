package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

private const val PAGE_SIZE = 20
private const val TOO_MANY_REQUESTS = 429

@MangaSourceParser("REMANGA", "Реманга", "ru")
internal class RemangaParser(
	context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.REMANGA, PAGE_SIZE),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider,
	Interceptor {

	private val baseHeaders: Headers
		get() = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.build()

	override fun getRequestHeaders() = getApiHeaders()

	override val configKeyDomain = ConfigKey.Domain("remanga.org", "реманга.орг")

	override val authUrl: String
		get() = "https://${domain}"

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
			SourceCommentSort.TOP,
		),
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies(domain).any {
			it.name == "user"
		}
	}

	private val regexLastUrlPath = Regex("/[^/]+/?$")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isYearRangeSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.allOf(MangaState::class.java),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE),
	)

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		if (response.code == TOO_MANY_REQUESTS) {
			response.closeQuietly()
			Thread.sleep(1000)
			return chain.proceed(chain.request().newBuilder().build())
		}
		return response
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		copyCookies()
		val domain = domain
		val urlBuilder = urlBuilder(subdomain = "api")
			.addPathSegment("api")
			.addPathSegment("v2")
			.addPathSegment("search")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("count", PAGE_SIZE.toString())
			.addQueryParameter("ordering", getSortKey(order))
		if (!filter.query.isNullOrEmpty()) {
			urlBuilder.addQueryParameter("query", filter.query)
		} else {
			urlBuilder.addPathSegment("catalog")
		}
		for (tag in filter.tags) {
			urlBuilder.addQueryParameter("genres", tag.key)
		}
		for (tag in filter.tagsExclude) {
			urlBuilder.addQueryParameter("exclude_genres", tag.key)
		}
		if (filter.yearFrom != YEAR_UNKNOWN) {
			urlBuilder.addQueryParameter("issue_year_gte", filter.yearFrom.toString())
		}
		if (filter.yearTo != YEAR_UNKNOWN) {
			urlBuilder.addQueryParameter("issue_year_lte", filter.yearFrom.toString())
		}
		for (age in filter.contentRating) {
			when (age) {
				ContentRating.SAFE -> urlBuilder.addQueryParameter("age_limit", "0")
				ContentRating.SUGGESTIVE -> {
					urlBuilder.addQueryParameter("age_limit", "1")
					urlBuilder.addQueryParameter("age_limit", "2")
				}

				else -> Unit
			}
		}
		for (state in filter.states) {
			urlBuilder.addQueryParameter(
				"status",
				when (state) {
					MangaState.ONGOING -> "2"
					MangaState.FINISHED -> "1"
					MangaState.ABANDONED -> "4"
					MangaState.PAUSED -> "3"
					MangaState.UPCOMING -> "5"
					MangaState.RESTRICTED -> "6"
				},
			)
		}
		val content = webClient.httpGet(urlBuilder.build()).parseJson()
			.getJSONArray("results")
		return content.mapJSON { jo ->
			parseManga(jo)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.find(regexLastUrlPath)?.removePrefix("/")
			?: throw ParseException("Cannot obtain slug from ${manga.url}", manga.publicUrl)
		return getDetails(slug, manga.publicUrl)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val slug = link.pathSegments.getOrNull(1) ?: return super.resolveLink(resolver, link)
		return getDetails(slug, link.toString())
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		copyCookies()
		val slug = seed.url.find(regexLastUrlPath)?.removePrefix("/")
			?: throw ParseException("Cannot obtain slug from ${seed.url}", seed.publicUrl)
		val json = webClient.httpGet(
			// https://api.remanga.org/api/v2/titles/the_beginning_after_the_end/relations/
			urlBuilder(subdomain = "api")
				.addPathSegment("api")
				.addPathSegment("v2")
				.addPathSegment("titles")
				.addPathSegment(slug)
				.addPathSegment("relations")
				.build(),
		).parseJson()
			.optJSONArray("titles") ?: return emptyList()
		return json.mapJSON { jo -> parseManga(jo) }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val content = webClient.httpGet(chapter.url.toAbsoluteUrl(getDomain("api")))
			.parseJson()
		val pages = content.optJSONArray("pages")
		if (pages == null) {
			val pubDate = content.getStringOrNull("pub_date")?.let {
				SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parseSafe(it)
			}
			if (pubDate != null && pubDate > System.currentTimeMillis()) {
				val at = SimpleDateFormat.getDateInstance(DateFormat.LONG).format(Date(pubDate))
				throw ContentUnavailableException("Глава станет доступной $at")
			} else {
				throw ContentUnavailableException("Глава недоступна")
			}
		}
		val result = ArrayList<MangaPage>(pages.length())
		for (i in 0 until pages.length()) {
			when (val item = pages.get(i)) {
				is JSONObject -> result += parsePage(item)
				is JSONArray -> item.mapJSONTo(result) { parsePage(it) }
				else -> throw ParseException("Unknown json item $item", chapter.url)
			}
		}
		return result
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val content = webClient.httpGet("https://api.$domain/api/forms/titles/?get=genres")
			.parseJson().getJSONObject("content").getJSONArray("genres")
		return content.mapJSONToSet { jo ->
			MangaTag(
				title = jo.getString("name").toTitleCase(),
				key = jo.getInt("id").toString(),
				source = source,
			)
		}
	}

	override suspend fun getUsername(): String {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val jo = webClient.httpGet(
			url = "https://api.${domain}/api/users/current/",
		).parseJson()
		return jo.optJSONObject("content")?.getStringOrNull("username")
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		context.cookieJar.insertCookies(domain, "user=; Max-Age=0; Path=/")
		context.cookieJar.insertCookies("api.$domain", "user=; Max-Age=0; Path=/")
		context.clearAuthValues(source)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private suspend fun getDetails(slug: String, publicUrl: String): Manga {
		copyCookies()
		val jo = webClient.httpGet(
			url = "https://api.$domain/api/v2/titles/$slug/",
		).parseJson()
		jo.optJSONObject("detail")?.getStringOrNull("message")?.let { msg ->
			throw ParseException(msg, publicUrl)
		}
		val url = "/manga/${jo.getString("dir")}"
		val cover = jo.getJSONObject("cover")
		val branches = jo.getJSONArray("branches").mapJSONToSet {
			it.getLong("id") to it.optJSONObject("publishers")?.getStringOrNull("name")
		}
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = "https://$domain$url/main",
			title = jo.getString("main_name"),
			altTitles = jo.getStringOrNull("another_name")?.split(" / ")
				?.toSet().orEmpty() + setOfNotNull(jo.getStringOrNull("secondary_name")),
			rating = jo.getFloatOrDefault("avg_rating", -10f) / 10f,
			coverUrl = cover.getStringOrNull("mid")?.toAbsoluteUrl("api.$domain"),
			largeCoverUrl = cover.getStringOrNull("high")?.toAbsoluteUrl("api.$domain"),
			authors = emptySet(),
			contentRating = when (jo.optJSONObject("age_limit")?.getIntOrDefault("id", -1)) {
				0 -> ContentRating.SAFE
				1, 2 -> ContentRating.SUGGESTIVE
				else -> null
			},
			state = when (jo.optJSONObject("status")?.getIntOrDefault("id", -1) ?: -1) {
				1 -> MangaState.FINISHED
				2 -> MangaState.ONGOING
				3 -> MangaState.PAUSED
				4 -> MangaState.ABANDONED
				5 -> MangaState.UPCOMING
				6 -> MangaState.RESTRICTED
				else -> null
			},
			tags = jo.optJSONArray("genres")?.mapJSONToSet { g ->
				MangaTag(
					title = g.getString("name").toTitleCase(sourceLocale),
					key = g.getInt("id").toString(),
					source = source,
				)
			}.orEmpty(),
			description = jo.getStringOrNull("description"),
			chapters = branches.flatMap { (branchId, branchName) ->
				grabChapters(branchId).mapChapters(reversed = true) { _, cjo ->
					if (
						cjo.getBooleanOrDefault("is_paid", false) &&
						!cjo.getBooleanOrDefault("is_bought", false)
					) {
						return@mapChapters null
					}
					val id = cjo.getLong("id")
					val name = cjo.getStringOrNull("name")?.toTitleCase(Locale.ROOT)
					val publishers = cjo.optJSONArray("publishers")
					MangaChapter(
						id = generateUid(id),
						url = "/api/v2/titles/chapters/$id/",
						number = cjo.getFloatOrDefault("chapter", 0f),
						volume = cjo.getIntOrDefault("tome", 0),
						title = name,
						uploadDate = dateFormat.parseSafe(cjo.getStringOrNull("upload_date")),
						scanlator = publishers?.optJSONObject(0)?.getStringOrNull("name"),
						source = source,
						branch = branchName,
					)
				}
			},
			source = source,
		)
	}

	private fun parseManga(jo: JSONObject): Manga {
		val url = "/manga/${jo.getString("dir")}"
		val cover = jo.getJSONObject("cover")
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = "https://$domain$url/main",
			title = jo.getString("main_name"),
			altTitles = setOfNotNull(jo.getStringOrNull("secondary_name")),
			rating = jo.getFloatOrDefault("avg_rating", -10f) / 10f,
			coverUrl = cover.getStringOrNull("mid")?.toAbsoluteUrl("api.$domain"),
			largeCoverUrl = cover.getStringOrNull("high")?.toAbsoluteUrl("api.$domain"),
			authors = emptySet(),
			contentRating = null,
			state = when (jo.optJSONObject("status")?.getIntOrDefault("id", -1) ?: -1) {
				1 -> MangaState.FINISHED
				2 -> MangaState.ONGOING
				3 -> MangaState.PAUSED
				4 -> MangaState.ABANDONED
				5 -> MangaState.UPCOMING
				6 -> MangaState.RESTRICTED
				else -> null
			},
			tags = jo.optJSONArray("genres")?.mapJSONToSet { g ->
				MangaTag(
					title = g.getString("name").toTitleCase(sourceLocale),
					key = g.getInt("id").toString(),
					source = source,
				)
			}.orEmpty(),
			source = source,
		)
	}

	private fun getApiHeaders(): Headers {
		val userCookie = context.cookieJar.getCookies(domain).find {
			it.name == "user"
		} ?: return baseHeaders
		val jo = JSONObject(userCookie.value.urlDecode())
		val accessToken = jo.getStringOrNull("access_token") ?: return baseHeaders
		return baseHeaders.newBuilder().add("authorization", "bearer $accessToken").build()
	}

	private fun copyCookies() {
		val domain = domain
		context.cookieJar.copyCookies(domain, "api.$domain")
	}

	private fun getSortKey(order: SortOrder?) = when (order) {
		SortOrder.UPDATED -> "-chapter_date"
		SortOrder.POPULARITY -> "-rating"
		SortOrder.RATING -> "-votes"
		SortOrder.NEWEST -> "-id"
		else -> "-chapter_date"
	}

	private fun parsePage(jo: JSONObject) = MangaPage(
		// id = generateUid(jo.getLong("id")), 19.01.2024 page id is gone
		id = generateUid(jo.getString("link")),
		url = jo.getString("link"),
		preview = null,
		source = source,
	)

	private suspend fun grabChapters(branchId: Long): List<JSONObject> {
		val result = ArrayList<JSONObject>(100)
		var page = 1
		while (true) {
			val content = webClient.httpGet(
				url = "https://api.$domain/api/v2/titles/chapters/?branch_id=$branchId&page=$page&count=500",
			).parseJson().getJSONArray("results")
			val len = content.length()
			if (len == 0) {
				break
			}
			result.ensureCapacity(result.size + len)
			for (i in 0 until len) {
				result.add(content.getJSONObject(i))
			}
			page++
		}
		return result
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		copyCookies()
		val jo = fetchTitleJson(manga)
		val rawRating = jo?.getFloatOrDefault("avg_rating", -1f)?.takeIf { it > 0f }
			?: manga.rating.takeIf { it > 0f }?.times(10f)
		val ratingCount = jo?.getIntOrDefault("count_rating", -1)?.takeIf { it >= 0 }
		val viewCount = jo?.getLongOrDefault("total_views", -1L)?.takeIf { it >= 0L }
		val followCount = jo?.getLongOrDefault("count_bookmarks", -1L)?.takeIf { it >= 0L }
		val commentCount = jo?.getIntOrDefault("count_comments", -1)?.takeIf { it >= 0 }
		val authorized = runCatching { isAuthorized() }.getOrDefault(false)
		val username = if (authorized) runCatching { getUsername() }.getOrNull() else null

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = if (chapter == null) commentCount else null,
			canCurrentUserComment = authorized,
			currentUsername = username,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		copyCookies()
		val ordering = when (sort) {
			SourceCommentSort.TOP -> "-score"
			SourceCommentSort.OLDEST -> "id"
			SourceCommentSort.NEWEST -> "-id"
		}
		val chapterId = chapter?.url?.let {
			Regex("""/chapters/(\d+)/?""").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull()
		}
		val titleId = if (chapterId == null) {
			fetchTitleJson(manga)?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
		} else {
			null
		}
		val url = when {
			chapterId != null -> urlBuilder(subdomain = "api")
				.addPathSegment("api")
				.addPathSegment("v2")
				.addPathSegment("activity")
				.addPathSegment("comments")
				.addPathSegment("")
				.addQueryParameter("chapter_id", chapterId.toString())
				.addQueryParameter("page", page.toString())
				.addQueryParameter("count", PAGE_SIZE.toString())
				.addQueryParameter("ordering", ordering)
				.build()
			titleId != null -> urlBuilder(subdomain = "api")
				.addPathSegment("api")
				.addPathSegment("v2")
				.addPathSegment("activity")
				.addPathSegment("comments")
				.addPathSegment("")
				.addQueryParameter("title_id", titleId.toString())
				.addQueryParameter("page", page.toString())
				.addQueryParameter("count", PAGE_SIZE.toString())
				.addQueryParameter("ordering", ordering)
				.build()
			else -> null
		}

		if (url != null) {
			val arr = runCatching { fetchCommentsArray(url) }.getOrNull()
			if (arr != null) {
				val currentUsername = runCatching { getUsername() }.getOrNull()
				val comments = arr.mapJSONNotNull { parseRemangaComment(it, currentUsername) }
				return SourceCommentPage(
					comments = comments,
					page = page,
					hasNextPage = comments.size >= PAGE_SIZE,
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
		copyCookies()
		val url = urlBuilder(subdomain = "api")
			.addPathSegment("api")
			.addPathSegment("v2")
			.addPathSegment("activity")
			.addPathSegment("comments")
			.addPathSegment("")
			.addQueryParameter("reply_to", comment.id)
			.addQueryParameter("page", page.toString())
			.addQueryParameter("count", PAGE_SIZE.toString())
			.build()
		val arr = runCatching { fetchCommentsArray(url) }.getOrNull()
			?: return super.getReplies(manga, comment, chapter, page, cursor)
		val currentUsername = runCatching { getUsername() }.getOrNull()
		val replies = arr.mapJSONNotNull { parseRemangaComment(it, currentUsername, parentId = comment.id) }
		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = replies.size >= PAGE_SIZE,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		copyCookies()
		val chapterId = chapter?.url?.let {
			Regex("""/chapters/(\d+)/?""").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull()
		}
		val titleId = fetchTitleJson(manga)?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
		val payload = JSONObject().apply {
			if (chapterId != null) {
				put("chapter", chapterId)
			} else if (titleId != null) {
				put("title", titleId)
			}
			put("text", text)
			put("is_spoiler", isSpoiler)
			if (parentComment != null) {
				put("reply_to", parentComment.id.toLongOrNull() ?: parentComment.id)
			}
		}
		val request = Request.Builder()
			.url("https://api.$domain/api/v2/activity/comments/")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(getApiHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		val raw = response.use { it.body.string() }
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val username = runCatching { getUsername() }.getOrNull()
		val jo = raw.toJSONObjectOrNull()
		return jo?.let { parseRemangaComment(it, username, parentId = parentComment?.id) }
			?: SourceComment(
				id = System.currentTimeMillis().toString(),
				authorName = username ?: "Вы",
				body = text,
				createdAtEpochMs = System.currentTimeMillis(),
				isSpoiler = isSpoiler,
				parentId = parentComment?.id,
				isOwnComment = true,
			)
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		copyCookies()
		val targetVote = if (isLike) 1 else -1
		val payload = JSONObject().apply { put("type", targetVote) }
		val request = Request.Builder()
			.url("https://api.$domain/api/v2/activity/comments/${comment.id}/vote/")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(getApiHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val nextVote = if (comment.userVote == targetVote) 0 else targetVote
		return comment.copy(
			likesCount = (comment.likesCount + (nextVote - comment.userVote)).coerceAtLeast(0),
			userVote = nextVote,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = manga.publicUrl.ifBlank { "https://$domain${manga.url}/main" }
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

	private suspend fun fetchTitleJson(manga: Manga): JSONObject? {
		val slug = manga.url.find(regexLastUrlPath)?.removePrefix("/")?.nullIfEmpty() ?: return null
		return runCatching {
			webClient.httpGet("https://api.$domain/api/v2/titles/$slug/").parseJson()
		}.getOrNull()
	}

	private suspend fun fetchCommentsArray(url: HttpUrl): JSONArray? {
		val raw = webClient.httpGet(url).parseRaw().trim()
		return raw.toJSONArrayOrNull()
			?: raw.toJSONObjectOrNull()?.let { jo ->
				jo.optJSONArray("results") ?: jo.optJSONArray("content") ?: jo.optJSONArray("data")
			}
	}

	private fun parseRemangaComment(
		jo: JSONObject,
		currentUsername: String?,
		parentId: String? = null,
	): SourceComment? {
		val id = jo.getStringOrNull("id") ?: return null
		val user = jo.optJSONObject("user")
		val authorName = user?.getStringOrNull("username") ?: "Пользователь"
		val avatarObj = user?.optJSONObject("avatar")
		val avatarPath = avatarObj?.getStringOrNull("mid") ?: avatarObj?.getStringOrNull("low")
		val avatarUrl = avatarPath?.toAbsoluteUrl("api.$domain")
		val rank = jo.getStringOrNull("rank")?.takeUnless { it.equals("Без ранга", ignoreCase = true) }
		val rawText = jo.getStringOrNull("text").orEmpty()
		val unescapedHtml = Parser.unescapeEntities(rawText, false)
		val plainText = Jsoup.parseBodyFragment(unescapedHtml).text().trim().ifEmpty { rawText }
		val score = jo.getIntOrDefault("score", 0)
		val ratedRaw = jo.opt("rated")
		val userVote = when (ratedRaw) {
			true, 1, "1" -> 1
			false, -1, "-1" -> -1
			else -> 0
		}
		val rawDate = jo.getLongOrDefault("date", -1L)
		val epochMs = when (rawDate) {
			in 1_000_000_000L..4_000_000_000L -> rawDate * 1000L
			in 1_000_000_000_000L..4_000_000_000_000L -> rawDate
			else -> null
		}
		val isOwn = currentUsername != null && authorName.equals(currentUsername, ignoreCase = true)

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = rank,
			body = plainText,
			bodyHtml = unescapedHtml.nullIfEmpty(),
			createdAtEpochMs = epochMs,
			isSpoiler = jo.getBooleanOrDefault("is_spoiler", false),
			isPinned = jo.getBooleanOrDefault("is_pinned", false),
			likesCount = score.coerceAtLeast(0),
			dislikesCount = (-score).coerceAtLeast(0),
			userVote = userVote,
			repliesCount = jo.getIntOrDefault("count_replies", 0),
			parentId = parentId,
			isOwnComment = isOwn,
			canReply = currentUsername != null && parentId == null,
			canLike = currentUsername != null,
			canDislike = currentUsername != null,
		)
	}

	private companion object {
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
