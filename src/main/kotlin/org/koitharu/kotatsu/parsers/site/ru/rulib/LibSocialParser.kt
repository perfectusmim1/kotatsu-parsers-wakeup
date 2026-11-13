package org.koitharu.kotatsu.parsers.site.ru.rulib

import androidx.collection.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.nestCommentsByParentId
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

internal abstract class LibSocialParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	protected val siteId: Int,
	siteDomains: Array<String>,
) : PagedMangaParser(context, source, pageSize = 60),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	protected val apiHost = "api.cdnlibs.org"

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val authUrl: String
		get() = "https://$domain/ru/front/auth"

	override val signUpUrl: String
		get() = "https://$domain/ru/front/register"

	override suspend fun isAuthorized(): Boolean {
		val token = getAuthData()?.optJSONObject("token")?.getStringOrNull("access_token")
		return !token.isNullOrEmpty()
	}

	override suspend fun getUsername(): String {
		val data = getAuthData() ?: throw AuthRequiredException(source)
		val token = data.optJSONObject("token")?.getStringOrNull("access_token")
		if (token.isNullOrEmpty()) {
			throw AuthRequiredException(source)
		}
		return data.optJSONObject("auth")
			?.getStringOrNull("username")
			?.takeIf { it.isNotBlank() }
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatching {
			val request = Request.Builder()
				.url("https://$apiHost/api/auth/logout")
				.post("{}".toRequestBody(JSON_MEDIA_TYPE))
				.headers(apiAuthHeaders())
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().close()
		}
		context.clearAuthValues(source)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
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
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	final override val configKeyDomain = ConfigKey.Domain(*siteDomains)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
	)

	final override fun intercept(chain: Interceptor.Chain): Response {
		val token = runBlocking { getAuthData() }?.optJSONObject("token")?.getStringOrNull("access_token")
		val requestBuilder = chain.request().newBuilder()
		if (!token.isNullOrEmpty()) {
			requestBuilder.header("Authorization", "Bearer $token")
		}
		requestBuilder.header("Site-Id", siteId.toString())
		return chain.proceed(requestBuilder.build())
	}

	private val statesMap = intObjectMapOf(
		1, MangaState.ONGOING,
		2, MangaState.FINISHED,
		3, MangaState.UPCOMING,
		4, MangaState.PAUSED,
		5, MangaState.ABANDONED,
	)
	private val imageServers = suspendLazy(initializer = ::fetchServers)
	private val splitTranslationsKey = ConfigKey.SplitByTranslations(true)
	private val preferredServerKey = ConfigKey.PreferredImageServer(
		presetValues = mapOf(
			null to null,
			SERVER_MAIN to "Первый",
			SERVER_SECONDARY to "Второй",
			SERVER_COMPRESS to "Сжатия",
			SERVER_DOWNLOAD to "Загрузки",
			SERVER_CROP to "Обрезки",
		),
		defaultValue = null,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("manga")
			.addQueryParameter("site_id[]", siteId.toString())
			.addQueryParameter("fields[]", "rate")
			.addQueryParameter("fields[]", "rate_avg")
			.addQueryParameter("page", page.toString())
		for (state in filter.states) {
			urlBuilder.addQueryParameter("status[]", statesMap.keyOf(state).toString())
		}
		for (tag in filter.tags) {
			urlBuilder.addQueryParameter("${tag.typeKey()}[]", tag.key.drop(1))
		}
		for (tag in filter.tagsExclude) {
			urlBuilder.addQueryParameter("${tag.typeKey()}_exclude[]", tag.key.drop(1))
		}
		if (!filter.query.isNullOrEmpty()) {
			urlBuilder.addQueryParameter("q", filter.query)
		}
		urlBuilder.addQueryParameter(
			"sort_by",
			when (order) {
				SortOrder.UPDATED -> "last_chapter_at"
				SortOrder.POPULARITY -> "views"
				SortOrder.RATING -> "rate_avg"
				SortOrder.NEWEST -> "created_at"
				SortOrder.ALPHABETICAL,
				SortOrder.ALPHABETICAL_DESC,
					-> "rus_name"

				else -> null
			},
		)
		urlBuilder.addQueryParameter(
			"sort_type",
			when (order) {
				SortOrder.UPDATED,
				SortOrder.POPULARITY,
				SortOrder.RATING,
				SortOrder.NEWEST,
				SortOrder.ALPHABETICAL_DESC,
					-> "desc"

				SortOrder.ALPHABETICAL -> "asc"
				else -> null
			},
		)
		val json = webClient.httpGet(urlBuilder.build()).parseJson()
		val data = json.getJSONArray("data")
		return data.mapJSON(::parseManga)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val chaptersDeferred = async { fetchChapters(manga) }
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("manga")
			.addPathSegment(manga.url)
			.addQueryParameter("fields[]", "summary")
			.addQueryParameter("fields[]", "genres")
			.addQueryParameter("fields[]", "tags")
			.addQueryParameter("fields[]", "authors")
			.addQueryParameter("fields[]", "close_view")
			.build()
		val json = webClient.httpGet(url).parseJson().getJSONObject("data")
		val genres = json.getJSONArray("genres").mapJSON { jo ->
			MangaTag(title = jo.getString("name"), key = "g" + jo.getInt("id"), source = source)
		}
		val tags = json.getJSONArray("tags").mapJSON { jo ->
			MangaTag(title = jo.getString("name"), key = "t" + jo.getInt("id"), source = source)
		}
		val authors = json.getJSONArray("authors").mapJSONNotNullToSet {
			it.getStringOrNull("name")
		}
		val chapters = chaptersDeferred.await()
		val isRestricted = json.getIntOrDefault("close_view", 0) > 0
		manga.copy(
			title = json.getStringOrNull("rus_name") ?: manga.title,
			altTitles = setOfNotNull(json.getStringOrNull("name")),
			tags = tagsSetOf(tags, genres),
			state = if (chapters.isEmpty() && isRestricted) {
				MangaState.RESTRICTED
			} else {
				manga.state
			},
			authors = authors,
			contentRating = json.optJSONObject("ageRestriction")?.let {
				val id = it.getIntOrDefault("id", -1)
				if (id >= 4) ContentRating.SUGGESTIVE else sourceContentRating
			} ?: manga.contentRating,
			description = json.getString("summary").nl2br(),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = coroutineScope {
		val pages = async {
			webClient.httpGet(
				concatUrl("https://$apiHost/api/manga/", chapter.url),
			).parseJson().getJSONObject("data")
		}
		val servers = imageServers.get()
		val json = pages.await()
		val primaryServer = getPrimaryImageServer(servers)
		json.getJSONArray("pages").mapJSON { jo ->
			val url = jo.getString("url")
			MangaPage(
				id = generateUid(jo.getLong("id")),
				url = concatUrl(primaryServer, url),
				preview = servers[SERVER_COMPRESS]?.let { concatUrl(it, url) },
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> = coroutineScope {
		val tags = async { fetchTags("tags") }
		val genres = async { fetchTags("genres") }
		tagsSetOf(tags.await(), genres.await())
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val json = webClient.httpGet(
			HttpUrl.Builder()
				.scheme(SCHEME_HTTPS)
				.host(apiHost)
				.addPathSegment("api")
				.addPathSegment("manga")
				.addPathSegment(seed.url)
				.addPathSegment("similar")
				.build(),
		).parseJson().getJSONArray("data")
		return json.mapJSON { jo ->
			parseManga(jo.getJSONObject("media"))
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		return resolver.resolveManga(this, link.pathSegments.lastOrNull() ?: return null)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(splitTranslationsKey)
		keys.add(preferredServerKey)
	}

	private fun parseManga(jo: JSONObject): Manga {
		val cover = jo.getJSONObject("cover")
		val isNsfwSource = jo.getJSONObject("ageRestriction").getIntOrDefault("id", 0) >= 3
		return Manga(
			id = generateUid(jo.getLong("id")),
			title = jo.getString("rus_name").ifEmpty { jo.getString("name") },
			altTitles = setOfNotNull(jo.getString("name")),
			url = jo.getString("slug_url"),
			publicUrl = "https://$domain/ru/manga/" + jo.getString("slug_url"),
			rating = jo.optJSONObject("rating")
				?.getFloatOrDefault("average", RATING_UNKNOWN * 10f)?.div(10f) ?: RATING_UNKNOWN,
			contentRating = sourceContentRating,
			coverUrl = cover.getString("thumbnail"),
			tags = setOf(),
			state = statesMap[jo.optJSONObject("status")?.getIntOrDefault("id", -1) ?: -1],
			authors = emptySet(),
			largeCoverUrl = cover.getString("default"),
			source = source,
		)
	}

	private fun getPrimaryImageServer(servers: ScatterMap<String, String>): String {
		val preferred = config[preferredServerKey]
		if (preferred != null) {
			servers[preferred]?.let { return it }
		}
		return checkNotNull(servers[SERVER_MAIN] ?: servers[SERVER_DOWNLOAD] ?: servers[SERVER_SECONDARY]) {
			"No available images servers"
		}
	}

	private suspend fun fetchChapters(manga: Manga): List<MangaChapter> {
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("manga")
			.addPathSegment(manga.url)
			.addPathSegment("chapters")
			.build()
		val json = webClient.httpGet(url).parseJson().getJSONArray("data")
		val builder = ChaptersListBuilder(json.length())
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
		val useBranching = config[splitTranslationsKey]
		for (i in 0 until json.length()) {
			val jo = json.getJSONObject(i)
			val volume = jo.getIntOrDefault("volume", 0)
			val number = jo.getFloatOrDefault("number", 0f)
			val numberString = number.formatSimple()
			val name = jo.getStringOrNull("name")
			val branches = jo.getJSONArray("branches")
			for (j in 0 until branches.length()) {
				val bjo = branches.getJSONObject(j)
				val isRestricted = bjo.optJSONObject("restricted_view")?.let {
					!it.getBooleanOrDefault("is_open", true)
				} ?: false
				if (isRestricted) {
					continue
				}
				val id = bjo.getLong("id")
				val branchId = bjo.getLongOrDefault("branch_id", 0L)
				val team = bjo.getJSONArray("teams").optJSONObject(0)?.getStringOrNull("name")
				builder += MangaChapter(
					id = generateUid(id),
					title = name,
					number = number,
					volume = volume,
					url = buildString {
						append(manga.url)
						append("/chapter?number=")
						append(numberString)
						append("&volume=")
						append(volume)
						if (branchId != 0L) {
							append("&branch_id=")
							append(branchId)
						}
					},
					scanlator = team,
					uploadDate = dateFormat.parseSafe(bjo.getStringOrNull("created_at")),
					branch = if (useBranching) team else null,
					source = source,
				)
			}
		}
		return builder.toList()
	}

	private suspend fun fetchTags(type: String): List<MangaTag> {
		val data = webClient.httpGet(
			HttpUrl.Builder()
				.scheme(SCHEME_HTTPS)
				.host(apiHost)
				.addPathSegment("api").addPathSegment(type).build(),
		).parseJson().getJSONArray("data")
		val prefix = type.first().toString()
		return data.mapJSONNotNull { jo ->
			val sites = jo.getJSONArray("site_ids").toIntSet()
			if (siteId !in sites) {
				return@mapJSONNotNull null
			}
			MangaTag(
				title = jo.getString("name"),
				key = prefix + jo.getInt("id"),
				source = source,
			)
		}
	}

	private suspend fun fetchServers(): ScatterMap<String, String> {
		val json = webClient.httpGet(
			HttpUrl.Builder()
				.scheme(SCHEME_HTTPS)
				.host(apiHost)
				.addPathSegment("api")
				.addPathSegment("constants")
				.addQueryParameter("fields[]", "imageServers")
				.build(),
		).parseJson().getJSONObject("data").getJSONArray("imageServers")
		val result = MutableScatterMap<String, String>()
		for (i in 0 until json.length()) {
			val jo = json.getJSONObject(i)
			val sites = jo.getJSONArray("site_ids").toIntSet()
			if (siteId !in sites) {
				continue
			}
			result[jo.getString("id")] = jo.getString("url")
		}
		return result
	}

	private fun <V> IntObjectMap<V>.keyOf(value: V): Int {
		forEach { k, v ->
			if (v == value) {
				return k
			}
		}
		throw NoSuchElementException("No key associated with value $value")
	}

	private fun JSONArray.toIntSet(): IntSet {
		val result = MutableIntSet(length())
		for (i in 0 until length()) {
			result.add(getInt(i))
		}
		return result
	}

	private fun MangaTag.typeKey() = when (key.firstOrNull()) {
		'g' -> "genres"
		't' -> "tags"
		else -> throw IllegalArgumentException("Tag $key($title) is of unknown type")
	}

	private fun tagsSetOf(tags: Collection<MangaTag>, genres: Collection<MangaTag>): Set<MangaTag> {
		val result = ArraySet<MangaTag>(tags.size + genres.size)
		val names = HashSet<String>(tags.size + genres.size)
		genres.forEach { x -> if (names.add(x.title)) result.add(x) }
		tags.forEach { x -> if (names.add(x.title)) result.add(x) }
		return result
	}

	private suspend fun getAuthData(): JSONObject? {
		val raw = WebViewHelper(context).getLocalStorageValue(domain, "auth") ?: return null
		return JSONObject(raw.unescapeJson().removeSurrounding('"'))
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val data = fetchMangaStatsJson(manga)
		val ratingObj = data?.optJSONObject("rating")
		val rawRating = ratingObj?.getFloatOrDefault("average", -1f)?.takeIf { it > 0f }
			?: data?.getFloatOrDefault("rate_avg", -1f)?.takeIf { it > 0f }
			?: manga.rating.takeIf { it > 0f }?.times(10f)
		val ratingCount = ratingObj?.getIntOrDefault("votes", -1)?.takeIf { it >= 0 }
		val userRating = ratingObj?.getIntOrDefault("user", -1)?.takeIf { it > 0 }
		val viewCount = data?.optJSONObject("views")?.getLongOrDefault("total", -1L)?.takeIf { it >= 0L }
			?: data?.getLongOrDefault("views", -1L)?.takeIf { it >= 0L }
		val followCount = data?.optJSONObject("bookmarks")?.getLongOrDefault("total", -1L)?.takeIf { it >= 0L }
			?: data?.getLongOrDefault("bookmarks", -1L)?.takeIf { it >= 0L }
		val commentCount = data?.getIntOrDefault("comments_count", -1)?.takeIf { it >= 0 }
		val authorized = runCatching { isAuthorized() }.getOrDefault(false)
		val username = if (authorized) runCatching { getUsername() }.getOrNull() else null

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = if (chapter == null) commentCount else null,
			canCurrentUserRate = authorized,
			canCurrentUserComment = authorized,
			currentUsername = username,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val mangaId = fetchMangaStatsJson(manga)?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
			?: throw AuthRequiredException(source)
		val body = JSONObject().apply {
			put("manga_id", mangaId)
			put("rating", score.coerceIn(1, 10))
		}
		val request = Request.Builder()
			.url("https://$apiHost/api/manga/${manga.url}/rate")
			.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(apiAuthHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		return getMangaCommunityInfo(manga, chapter).copy(userRating = score.coerceIn(1, 10))
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val (type, postId) = resolveCommentTarget(manga, chapter) ?: run {
			val widget = resolveWidgetContext(manga, chapter)
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				widgetContext = widget,
				engine = widget.engine,
			)
		}
		val sortKey = when (sort) {
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.TOP -> "popular"
			SourceCommentSort.NEWEST -> "newest"
		}
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("comments")
			.addQueryParameter("type", type)
			.addQueryParameter("post_id", postId.toString())
			.addQueryParameter("page", page.toString())
			.addQueryParameter("sort_by", sortKey)
			.build()
		val json = runCatching { webClient.httpGet(url).parseJson() }.getOrNull()
		if (json != null && json.has("data")) {
			val arr = json.getJSONArray("data")
			val currentUsername = runCatching { getUsername() }.getOrNull()
			val parsed = arr.mapJSONNotNull { parseLibSocialComment(it, currentUsername, type, postId) }
			val nested = nestCommentsByParentId(parsed)
			val meta = json.optJSONObject("meta")
			val currentPage = meta?.getIntOrDefault("current_page", page) ?: page
			val lastPage = meta?.getIntOrDefault("last_page", currentPage) ?: currentPage
			val hasNext = meta?.let { currentPage < lastPage }
				?: (json.optJSONObject("links")?.getStringOrNull("next") != null)
			val total = meta?.getIntOrDefault("total", -1)?.takeIf { it >= 0 }
			return SourceCommentPage(
				comments = nested,
				page = page,
				hasNextPage = hasNext,
				totalCount = total,
				engine = SourceCommentEngine.NATIVE,
			)
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
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("comments")
			.addPathSegment(comment.id)
			.addPathSegment("replies")
			.addQueryParameter("page", page.toString())
			.build()
		val json = runCatching { webClient.httpGet(url).parseJson() }.getOrNull()
		if (json != null && json.has("data")) {
			val currentUsername = runCatching { getUsername() }.getOrNull()
			val replies = json.getJSONArray("data").mapJSONNotNull {
				parseLibSocialComment(it, currentUsername, parentId = comment.id)
			}
			val meta = json.optJSONObject("meta")
			val currentPage = meta?.getIntOrDefault("current_page", page) ?: page
			val lastPage = meta?.getIntOrDefault("last_page", currentPage) ?: currentPage
			return SourceCommentPage(
				comments = replies,
				page = page,
				hasNextPage = currentPage < lastPage,
				engine = SourceCommentEngine.NATIVE,
			)
		}
		return super.getReplies(manga, comment, chapter, page, cursor)
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
		val (type, postId) = resolveCommentTarget(manga, chapter)
			?: throw UnsupportedOperationException("Unable to resolve target for comment")
		val payload = JSONObject().apply {
			put("type", type)
			put("post_id", postId)
			put("comment", text)
			if (parentComment != null) {
				put("parent_id", parentComment.id.toLongOrNull() ?: parentComment.id)
			}
		}
		val request = Request.Builder()
			.url("https://$apiHost/api/comments")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(apiAuthHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		val raw = response.use { it.body.string() }
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val jo = raw.toJSONObjectOrNull()?.optJSONObject("data") ?: raw.toJSONObjectOrNull()
		val username = runCatching { getUsername() }.getOrNull()
		return jo?.let { parseLibSocialComment(it, username, type, postId, parentComment?.id) }
			?: SourceComment(
				id = System.currentTimeMillis().toString(),
				authorName = username ?: "You",
				body = text,
				createdAtEpochMs = System.currentTimeMillis(),
				isSpoiler = isSpoiler,
				parentId = parentComment?.id,
				isOwnComment = true,
			)
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val payload = JSONObject().apply { put("comment", newText) }
		val request = Request.Builder()
			.url("https://$apiHost/api/comments/${comment.id}")
			.patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(apiAuthHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		return comment.copy(body = newText, bodyHtml = null, isEdited = true)
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val request = Request.Builder()
			.url("https://$apiHost/api/comments/${comment.id}")
			.delete()
			.headers(apiAuthHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
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
		val targetVote = if (isLike) 1 else -1
		val payload = JSONObject().apply {
			put("type", if (isLike) 1 else 0)
		}
		val request = Request.Builder()
			.url("https://$apiHost/api/comments/${comment.id}/vote")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.headers(apiAuthHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val response = context.httpClient.newCall(request).await()
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val nextVote = if (comment.userVote == targetVote) 0 else targetVote
		val likesDelta = (if (nextVote == 1) 1 else 0) - (if (comment.userVote == 1) 1 else 0)
		val dislikesDelta = (if (nextVote == -1) 1 else 0) - (if (comment.userVote == -1) 1 else 0)
		return comment.copy(
			likesCount = (comment.likesCount + likesDelta).coerceAtLeast(0),
			dislikesCount = (comment.dislikesCount + dislikesDelta).coerceAtLeast(0),
			userVote = nextVote,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = manga.publicUrl.ifBlank { "https://$domain/ru/manga/${manga.url}" }
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

	private suspend fun fetchMangaStatsJson(manga: Manga): JSONObject? {
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("manga")
			.addPathSegment(manga.url)
			.addQueryParameter("fields[]", "rate")
			.addQueryParameter("fields[]", "rate_avg")
			.addQueryParameter("fields[]", "views")
			.addQueryParameter("fields[]", "bookmarks")
			.addQueryParameter("fields[]", "comments_count")
			.build()
		return runCatching { webClient.httpGet(url).parseJson().optJSONObject("data") }.getOrNull()
	}

	private suspend fun resolveCommentTarget(manga: Manga, chapter: MangaChapter?): Pair<String, Long>? {
		if (chapter != null) {
			val chapterId = resolveChapterNumericId(manga, chapter)
			if (chapterId != null) {
				return "chapter" to chapterId
			}
		}
		val mangaId = fetchMangaStatsJson(manga)?.getLongOrDefault("id", 0L)?.takeIf { it > 0L } ?: return null
		return "manga" to mangaId
	}

	private suspend fun resolveChapterNumericId(manga: Manga, chapter: MangaChapter): Long? {
		val url = HttpUrl.Builder()
			.scheme(SCHEME_HTTPS)
			.host(apiHost)
			.addPathSegment("api")
			.addPathSegment("manga")
			.addPathSegment(manga.url)
			.addPathSegment("chapters")
			.build()
		val data = runCatching { webClient.httpGet(url).parseJson().optJSONArray("data") }.getOrNull() ?: return null
		for (i in 0 until data.length()) {
			val jo = data.optJSONObject(i) ?: continue
			val vol = jo.getIntOrDefault("volume", 0)
			val num = jo.getFloatOrDefault("number", 0f)
			if (vol == chapter.volume && num == chapter.number) {
				val branches = jo.optJSONArray("branches")
				if (branches != null && branches.length() > 0) {
					val branchId = branches.optJSONObject(0)?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
					if (branchId != null) return branchId
				}
				return jo.getLongOrDefault("id", 0L).takeIf { it > 0L }
			}
		}
		return null
	}

	private fun parseLibSocialComment(
		jo: JSONObject,
		currentUsername: String?,
		type: String? = null,
		postId: Long? = null,
		parentId: String? = null,
	): SourceComment? {
		val id = jo.getStringOrNull("id") ?: return null
		val user = jo.optJSONObject("user")
		val authorName = user?.getStringOrNull("username") ?: "Аноним"
		val avatarUrl = user?.optJSONObject("avatar")?.getStringOrNull("url")
			?: user?.getStringOrNull("avatar")
		val rawBody = jo.getStringOrNull("comment").orEmpty()
		val plainBody = Jsoup.parseBodyFragment(rawBody).text().trim().ifEmpty { rawBody }
		val createdAt = jo.getStringOrNull("created_at")
		val votes = jo.optJSONObject("votes")
		val likes = votes?.getIntOrDefault("up", 0) ?: jo.getIntOrDefault("likes_count", 0)
		val dislikes = votes?.getIntOrDefault("down", 0) ?: jo.getIntOrDefault("dislikes_count", 0)
		val userVote = when (votes?.getIntOrDefault("user", 0) ?: jo.getIntOrDefault("user_vote", 0)) {
			1 -> 1
			-1 -> -1
			else -> 0
		}
		val nestedReplies = jo.optJSONArray("replies")?.mapJSONNotNull { child ->
			parseLibSocialComment(child, currentUsername, type, postId, id)
		}.orEmpty()
		val isOwn = currentUsername != null && authorName.equals(currentUsername, ignoreCase = true)

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			body = plainBody,
			bodyHtml = rawBody.nullIfEmpty(),
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isEdited = jo.getStringOrNull("edited_at") != null,
			isPinned = jo.getBooleanOrDefault("is_pinned", false),
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = userVote,
			repliesCount = maxOf(jo.getIntOrDefault("replies_count", 0), nestedReplies.size),
			parentId = parentId ?: jo.getStringOrNull("parent_id"),
			replies = nestedReplies,
			isOwnComment = isOwn,
			canReply = currentUsername != null,
			canLike = currentUsername != null,
			canDislike = currentUsername != null,
			canEdit = isOwn,
			canDelete = isOwn,
		)
	}

	private suspend fun apiAuthHeaders(): Headers {
		val builder = getRequestHeaders().newBuilder()
			.add("Site-Id", siteId.toString())
			.add("Accept", "application/json")
		val token = getAuthData()?.optJSONObject("token")?.getStringOrNull("access_token")
		if (!token.isNullOrEmpty()) {
			builder.add("Authorization", "Bearer $token")
		}
		return builder.build()
	}

	protected companion object {

		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		const val SERVER_MAIN = "main"
		const val SERVER_SECONDARY = "secondary"
		const val SERVER_COMPRESS = "compress"
		const val SERVER_DOWNLOAD = "download"
		const val SERVER_CROP = "crop"
	}
}
