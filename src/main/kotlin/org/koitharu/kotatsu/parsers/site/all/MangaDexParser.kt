package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.FlexibleMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*

private const val PAGE_SIZE = 20
private const val CHAPTERS_FIRST_PAGE_SIZE = 120
private const val CHAPTERS_MAX_PAGE_SIZE = 500
private const val CHAPTERS_PARALLELISM = 3
private const val CHAPTERS_MAX_COUNT = 10_000 // strange api behavior, looks like a bug
private const val LOCALE_FALLBACK = "en"
private const val SERVER_DATA = "data"
private const val SERVER_DATA_SAVER = "data-saver"

private const val AUTH_HOST = "auth.mangadex.org"
private const val FORUMS_HOST = "forums.mangadex.org"
private const val OIDC_REALM_URL = "https://$AUTH_HOST/realms/mangadex"
private const val OIDC_AUTH_ENDPOINT = "$OIDC_REALM_URL/protocol/openid-connect/auth"
private const val OIDC_REGISTRATIONS_ENDPOINT = "$OIDC_REALM_URL/protocol/openid-connect/registrations"
private const val OIDC_TOKEN_ENDPOINT = "$OIDC_REALM_URL/protocol/openid-connect/token"
private const val OIDC_LOGOUT_ENDPOINT = "$OIDC_REALM_URL/protocol/openid-connect/logout"
private const val OIDC_REVOKE_ENDPOINT = "$OIDC_REALM_URL/protocol/openid-connect/revoke"
private const val OIDC_CLIENT_ID = "mangadex-frontend-stable"
private const val OIDC_REDIRECT_URI = "https://mangadex.org/auth/callback"
private const val OIDC_SCOPE = "openid email groups profile roles"

private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_ID_TOKEN = "id_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_USERNAME = "username"
private const val KEY_AUTH_CALLBACK_URL = "auth_callback_url"
private const val TOKEN_EXPIRY_BUFFER_MS = 60_000L
private const val MAX_PENDING_PKCE = 8
private val REGEX_UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)

@MangaSourceParser("MANGADEX", "MangaDex")
internal class MangaDexParser(context: MangaLoaderContext) :
	FlexibleMangaParser(context, MangaParserSource.MANGADEX),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("mangadex.org")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	private val preferredServerKey = ConfigKey.PreferredImageServer(
		presetValues = mapOf(
			SERVER_DATA to "Original quality",
			SERVER_DATA_SAVER to "Compressed quality",
		),
		defaultValue = SERVER_DATA,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(preferredServerKey)
	}

	private val authMutex = Mutex()
	private val pkceLock = Any()
	private val pendingPkce = LinkedHashMap<String, String>()
	@Volatile
	private var currentPkce: PkcePair? = null
	private val secureRandom by lazy { SecureRandom() }
	private val noRedirectClient: OkHttpClient by lazy {
		context.httpClient.newBuilder()
			.followRedirects(false)
			.followSslRedirects(false)
			.build()
	}

	override val authUrl: String
		get() = buildOidcAuthorizeUrl(OIDC_AUTH_ENDPOINT)

	override val signUpUrl: String?
		get() = buildOidcAuthorizeUrl(OIDC_REGISTRATIONS_ENDPOINT)

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 10,
		supportsNativeComments = false,
		supportsCommentPosting = false,
		supportsReplies = false,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsCommentEditing = false,
		supportsCommentDeleting = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.CUSTOM_WIDGET,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(SourceCommentSort.OLDEST, SourceCommentSort.NEWEST),
	)

	override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ADDED,
		SortOrder.ADDED_ASC,
		SortOrder.RELEVANCE,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class, Exclude::class),
				isMultiple = true,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = AUTHOR,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = CONTENT_TYPE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = CONTENT_RATING,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = DEMOGRAPHIC,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = ORIGINAL_LANGUAGE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = LANGUAGE,
				criteriaTypes = setOf(Include::class),
				isMultiple = true,
			),
			SearchCapability(
				field = PUBLICATION_YEAR,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
			),
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = coroutineScope {
		val localesDeferred = async { fetchAvailableLocales() }
		val tagsDeferred = async { fetchAvailableTags() }
		MangaListFilterOptions(
			availableTags = tagsDeferred.await(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentRating = EnumSet.allOf(ContentRating::class.java),
			availableDemographics = EnumSet.of(
				Demographic.SHOUNEN,
				Demographic.SHOUJO,
				Demographic.SEINEN,
				Demographic.JOSEI,
				Demographic.NONE,
			),
			availableLocales = localesDeferred.await(),
		)
	}

	private fun SearchableField.toParamName(): String = when (this) {
		TITLE_NAME -> "title"
		TAG -> "includedTags[]"
		AUTHOR -> "authors[]"
		STATE -> "status[]"
		CONTENT_TYPE -> "contentType[]"
		CONTENT_RATING -> "contentRating[]"
		DEMOGRAPHIC -> "publicationDemographic[]"
		ORIGINAL_LANGUAGE -> "originalLanguage[]"
		LANGUAGE -> "availableTranslatedLanguage[]"
		PUBLICATION_YEAR -> "year"
	}

	private fun Any?.toQueryParam(): String = when (this) {
		is String -> urlEncoded()
		is Locale -> if (language == "in") "id" else language
		is MangaTag -> key
		is MangaState -> when (this) {
			MangaState.ONGOING -> "ongoing"
			MangaState.FINISHED -> "completed"
			MangaState.ABANDONED -> "cancelled"
			MangaState.PAUSED -> "hiatus"
			else -> ""
		}

		is ContentRating -> when (this) {
			ContentRating.SAFE -> "safe"
			// quick fix for double value
			ContentRating.SUGGESTIVE -> "suggestive&contentRating[]=erotica"
			ContentRating.ADULT -> "pornographic"
		}

		is Demographic -> when (this) {
			Demographic.SHOUNEN -> "shounen"
			Demographic.SHOUJO -> "shoujo"
			Demographic.SEINEN -> "seinen"
			Demographic.JOSEI -> "josei"
			Demographic.NONE -> "none"
			else -> ""
		}

		is SortOrder -> when (this) {
			SortOrder.UPDATED -> "[latestUploadedChapter]=desc"
			SortOrder.UPDATED_ASC -> "[latestUploadedChapter]=asc"
			SortOrder.RATING -> "[rating]=desc"
			SortOrder.RATING_ASC -> "[rating]=asc"
			SortOrder.ALPHABETICAL -> "[title]=asc"
			SortOrder.ALPHABETICAL_DESC -> "[title]=desc"
			SortOrder.NEWEST -> "[year]=desc"
			SortOrder.NEWEST_ASC -> "[year]=asc"
			SortOrder.POPULARITY -> "[followedCount]=desc"
			SortOrder.POPULARITY_ASC -> "[followedCount]=asc"
			SortOrder.ADDED -> "[createdAt]=desc"
			SortOrder.ADDED_ASC -> "[createdAt]=asc"
			SortOrder.RELEVANCE -> "&order[relevance]=desc"
			else -> "[latestUploadedChapter]=desc"
		}

		else -> this.toString().urlEncoded()
	}

	private fun StringBuilder.appendCriterion(field: SearchableField, value: Any?, paramName: String? = null) {
		val param = paramName ?: field.toParamName()
		if (param.isNotBlank()) {
			append("&$param=")
			append(value.toQueryParam())
		}
	}

	override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		val url = buildString {
			append("https://api.$domain/manga?limit=$PAGE_SIZE&offset=${query.offset}")
				.append("&includes[]=cover_art&includes[]=author&includes[]=artist&includedTagsMode=AND&excludedTagsMode=OR")

			var hasContentRating = false

			query.criteria.forEach { criterion ->
				when (criterion) {
					is Include<*> -> {
						if (criterion.field == CONTENT_RATING) {
							hasContentRating = true
						}
						criterion.values.forEach { appendCriterion(criterion.field, it) }
					}

					is Exclude<*> -> {
						criterion.values.forEach { appendCriterion(criterion.field, it, "excludedTags[]") }
					}

					is Match<*> -> {
						appendCriterion(criterion.field, criterion.value)
					}

					else -> {
						// Not supported
					}
				}
			}

			// If contentRating is not provided, add default values
			if (!hasContentRating) {
				append("&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic")
			}

			append("&order")
			append((query.order ?: defaultSortOrder).toQueryParam())
		}

		val json = webClient.httpGet(url).parseJson().getJSONArray("data")
		return json.mapJSON { jo -> jo.fetchManga(null) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.removePrefix("/")
		return getDetails(mangaId)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val regex = Regex("[0-9a-f\\-]{10,}", RegexOption.IGNORE_CASE)
		val mangaId = link.pathSegments.find { regex.matches(it) } ?: return null
		return getDetails(mangaId)
	}

	private suspend fun getDetails(mangaId: String): Manga = coroutineScope {
		val jsonDeferred = async {
			webClient.httpGet(
				"https://api.$domain/manga/${mangaId}?includes[]=artist&includes[]=author&includes[]=cover_art",
			).parseJson().getJSONObject("data")
		}
		val feedDeferred = async { loadChapters(mangaId) }
		jsonDeferred.await().fetchManga(mapChapters(feedDeferred.await()))
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(
			"https://api.$domain/at-home/server/${chapter.url}?forcePort443=false",
		).parseJson()
		val chapterJson = json.getJSONObject("chapter")
		val server = config[preferredServerKey] ?: SERVER_DATA
		val pages = chapterJson.getJSONArray(
			if (server == SERVER_DATA_SAVER) "dataSaver" else "data",
		)
		val prefix = "${json.getString("baseUrl")}/$server/${chapterJson.getString("hash")}/"
		return List(pages.length()) { i ->
			val url = prefix + pages.getString(i)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null, // TODO prefix + dataSaver.getString(i),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val tags = webClient.httpGet("https://api.${domain}/manga/tag").parseJson()
			.getJSONArray("data")
		return tags.mapJSONToSet { jo ->
			MangaTag(
				title = jo.getJSONObject("attributes").getJSONObject("name")
					.firstStringValue()
					.toTitleCase(Locale.ENGLISH),
				key = jo.getString("id"),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableLocales(): Set<Locale> {
		val head = webClient.httpGet("https://$domain/").parseHtml().head()
		return head.getElementsByAttributeValue("property", "og:locale:alternate")
			.mapNotNullToSet { meta ->
				val raw = meta.attrOrNull("content") ?: return@mapNotNullToSet null
				Locale(raw.substringBefore('_'), raw.substringAfter('_', ""))
			}
	}

	private fun JSONObject.fetchManga(chapters: List<MangaChapter>?): Manga {
		val id = getString("id")
		val attrs = getJSONObject("attributes")
		val relations = getJSONArray("relationships").associateByKey("type")
		val cover = relations["cover_art"]
			?.firstOrNull()
			?.getJSONObject("attributes")
			?.getString("fileName")
			?.let {
				"https://uploads.$domain/covers/$id/$it"
			}
		val authors: Set<String> = (relations["author"] ?: relations["artist"])
			?.mapNotNullToSet {
				it.getJSONObject("attributes")?.getStringOrNull("name")
			}.orEmpty()

		return Manga(
			id = generateUid(id),
			title = requireNotNull(attrs.getJSONObject("title").selectByLocale()) {
				"Title should not be null"
			},
			altTitles = setOfNotNull(attrs.optJSONArray("altTitles")?.flatten()?.selectByLocale()), // TODO
			url = id,
			publicUrl = "https://$domain/title/$id",
			rating = RATING_UNKNOWN,
			contentRating = when (attrs.getStringOrNull("contentRating")) {
				"pornographic" -> ContentRating.ADULT
				"erotica", "suggestive" -> ContentRating.SUGGESTIVE
				"safe" -> ContentRating.SAFE
				else -> null
			},
			coverUrl = cover?.plus(".256.jpg"),
			largeCoverUrl = cover,
			description = attrs.optJSONObject("description")?.selectByLocale(),
			tags = attrs.getJSONArray("tags").mapJSONToSet { tag ->
				MangaTag(
					title = tag.getJSONObject("attributes")
						.getJSONObject("name")
						.firstStringValue()
						.toTitleCase(),
					key = tag.getString("id"),
					source = source,
				)
			},
			state = when (attrs.getStringOrNull("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled" -> MangaState.ABANDONED
				else -> null
			},
			authors = authors,
			chapters = chapters,
			source = source,
		)
	}

	private fun JSONObject.firstStringValue() = entries<String>().first().value

	private fun JSONObject.selectByLocale(): String? {
		val preferredLocales = context.getPreferredLocales()
		for (locale in preferredLocales) {
			getStringOrNull(locale.language)?.let { return it }
			getStringOrNull(locale.toLanguageTag())?.let { return it }
		}
		return getStringOrNull(LOCALE_FALLBACK) ?: entries<String>().firstOrNull()?.value?.nullIfEmpty()
	}

	private fun JSONArray.flatten(): JSONObject {
		val result = JSONObject()
		repeat(length()) { i ->
			val jo = optJSONObject(i)
			if (jo != null) {
				for (key in jo.keys()) {
					result.put(key, jo.get(key))
				}
			}
		}
		return result
	}

	private suspend fun loadChapters(mangaId: String): List<JSONObject> {
		val firstPage = loadChapters(mangaId, offset = 0, limit = CHAPTERS_FIRST_PAGE_SIZE)
		if (firstPage.size >= firstPage.total) {
			return firstPage.data
		}
		val tail = coroutineScope {
			val leftCount = firstPage.total.coerceAtMost(CHAPTERS_MAX_COUNT) - firstPage.size
			val pages = (leftCount / CHAPTERS_MAX_PAGE_SIZE.toFloat()).toIntUp()
			val dispatcher = Dispatchers.Default.limitedParallelism(CHAPTERS_PARALLELISM)
			List(pages) { page ->
				val offset = page * CHAPTERS_MAX_PAGE_SIZE + firstPage.size
				async(dispatcher) {
					loadChapters(mangaId, offset, CHAPTERS_MAX_PAGE_SIZE)
				}
			}.awaitAll()
		}
		val result = ArrayList<JSONObject>(firstPage.total)
		result += firstPage.data
		tail.flatMapTo(result) { it.data }
		return result
	}

	private suspend fun loadChapters(mangaId: String, offset: Int, limit: Int): Chapters {
		val limitedLimit = when {
			offset >= CHAPTERS_MAX_COUNT -> return Chapters(emptyList(), CHAPTERS_MAX_COUNT)
			offset + limit > CHAPTERS_MAX_COUNT -> CHAPTERS_MAX_COUNT - offset
			else -> limit
		}
		val url = buildString {
			append("https://api.")
			append(domain)
			append("/manga/")
			append(mangaId)
			append("/feed")
			append("?limit=")
			append(limitedLimit)
			append("&includes[]=scanlation_group&order[volume]=asc&order[chapter]=asc&offset=")
			append(offset)
			append("&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic")
		}
		val json = webClient.httpGet(url).parseJson()
		if (json.getString("result") == "ok") {
			return Chapters(
				data = json.optJSONArray("data")?.asTypedList<JSONObject>().orEmpty(),
				total = json.getInt("total"),
			)
		} else {
			val error = json.optJSONArray("errors").mapJSON { jo ->
				jo.getString("detail")
			}.joinToString("\n")
			throw ParseException(error, url)
		}
	}

	private fun mapChapters(list: List<JSONObject>): List<MangaChapter> {
		// 2022-01-02T00:27:11+00:00
		val dateFormat = SimpleDateFormat(
			"yyyy-MM-dd'T'HH:mm:ss'+00:00'",
			Locale.ROOT,
		)
		val chaptersBuilder = ChaptersListBuilder(list.size)
		val branchedChapters = HashMap<String?, HashMap<Pair<Int, Float>, MangaChapter>>()
		for (jo in list) {
			val id = jo.getString("id")
			val attrs = jo.getJSONObject("attributes")
			if (!attrs.isNull("externalUrl")) {
				continue
			}
			val number = attrs.getFloatOrDefault("chapter", 0f)
			val volume = attrs.getIntOrDefault("volume", 0)
			val locale = attrs.getStringOrNull("translatedLanguage")?.let { Locale.forLanguageTag(it) }
			val lc = locale?.getDisplayName(locale)?.toTitleCase(locale)
			val relations = jo.getJSONArray("relationships").associateByKey("type")
			val team =
				relations["scanlation_group"]?.firstOrNull()?.optJSONObject("attributes")?.getStringOrNull("name")
			val branch = (list.indices).firstNotNullOf { i ->
				val b = if (i == 0) lc else "$lc ($i)"
				if (branchedChapters[b]?.get(volume to number) == null) b else null
			}
			val chapter = MangaChapter(
				id = generateUid(id),
				title = attrs.getStringOrNull("title"),
				number = number,
				volume = volume,
				url = id,
				scanlator = team,
				uploadDate = dateFormat.parseSafe(attrs.getString("publishAt")),
				branch = branch,
				source = source,
			)
			if (chaptersBuilder.add(chapter)) {
				branchedChapters.getOrPut(branch, ::HashMap)[volume to number] = chapter
			}
		}
		return chaptersBuilder.toList()
	}

	private fun JSONArray.associateByKey(key: String): Map<String, List<JSONObject>> {
		val destination = LinkedHashMap<String, MutableList<JSONObject>>(length())
		repeat(length()) { i ->
			val item = getJSONObject(i)
			val keyValue = item.getString(key)
			destination.computeIfAbsent(keyValue) { mutableListOf() }.add(item)
		}
		return destination
	}

	override suspend fun isAuthorized(): Boolean = runCatchingCancellable {
		ensureAccessToken(allowSilentCookieExchange = true) != null
	}.getOrDefault(false)

	override suspend fun getUsername(): String = withContext(Dispatchers.IO) {
		val username = withBearerToken(requireAuth = true) { token ->
			fetchUsernameFromApi(token)
		} ?: throw AuthRequiredException(source)
		context.setAuthValue(source, KEY_USERNAME, username)
		username
	}

	override suspend fun logout() {
		withContext(Dispatchers.IO) {
			authMutex.withLock {
				val refreshToken = context.getAuthValue(source, KEY_REFRESH_TOKEN)?.nullIfEmpty()
				val idToken = context.getAuthValue(source, KEY_ID_TOKEN)?.nullIfEmpty()
				if (refreshToken != null) {
					runCatchingCancellable {
						postOidcForm(
							url = OIDC_LOGOUT_ENDPOINT,
							params = mapOf(
								"client_id" to OIDC_CLIENT_ID,
								"refresh_token" to refreshToken,
							),
						).close()
					}
					runCatchingCancellable {
						postOidcForm(
							url = OIDC_REVOKE_ENDPOINT,
							params = mapOf(
								"client_id" to OIDC_CLIENT_ID,
								"token" to refreshToken,
								"token_type_hint" to "refresh_token",
							),
						).close()
					}
				}
				if (idToken != null) {
					runCatchingCancellable {
						val logoutUrl = OIDC_LOGOUT_ENDPOINT.toHttpUrl().newBuilder()
							.addQueryParameter("id_token_hint", idToken)
							.addQueryParameter("client_id", OIDC_CLIENT_ID)
							.build()
						val request = Request.Builder()
							.get()
							.url(logoutUrl)
							.tag(MangaSource::class.java, source)
							.build()
						noRedirectClient.newCall(request).await().close()
					}
				}
				clearKeycloakCookies()
				clearPendingPkce()
				context.clearAuthValues(source)
			}
		}
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = coroutineScope {
		val mangaId = extractMangaId(manga)
		val chapterId = chapter?.let(::extractChapterId)
		val mangaStatsDeferred = mangaId?.let { id -> async { fetchStatistics("manga", id) } }
		val chapterStatsDeferred = chapterId?.let { id -> async { fetchStatistics("chapter", id) } }
		val authResultDeferred = async {
			runCatchingCancellable {
				withBearerToken(requireAuth = false) { token ->
					val cachedName = context.getAuthValue(source, KEY_USERNAME)?.nullIfEmpty()
						?: extractUsernameFromJwt(token)
					val rating = if (mangaId != null) {
						runCatchingCancellable {
							fetchUserRatingFromApi(mangaId, token)
						}.recoverCatchingCancellable { error ->
							if (error is AuthRequiredException) throw error
							null
						}.getOrNull()
					} else {
						null
					}
					Triple(rating, true, cachedName)
				}
			}.getOrNull() ?: Triple(null, false, null)
		}

		val mangaStats = mangaStatsDeferred?.await()
		val chapterStats = chapterStatsDeferred?.await()
		val (userRating, canRate, username) = authResultDeferred.await()

		val ratingObj = mangaStats?.optJSONObject("rating")
		val bayesian = ratingObj?.optDouble("bayesian", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
		val average = ratingObj?.optDouble("average", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
		val rawRating = (bayesian ?: average)?.toFloat()
		val ratingCount = ratingObj?.optJSONObject("distribution")?.let { dist ->
			var sum = 0
			for (key in dist.keys()) {
				sum += dist.optInt(key, 0).coerceAtLeast(0)
			}
			sum.takeIf { it > 0 }
		}
		val followCount = mangaStats?.takeIf { it.has("follows") && !it.isNull("follows") }
			?.optLong("follows", -1L)
			?.takeIf { it >= 0L }
		val commentCount = mangaStats?.optJSONObject("comments")
			?.takeIf { !it.isNull("repliesCount") }
			?.optInt("repliesCount", -1)
			?.takeIf { it >= 0 }
		val chapterCommentCount = chapterStats?.optJSONObject("comments")
			?.takeIf { !it.isNull("repliesCount") }
			?.optInt("repliesCount", -1)
			?.takeIf { it >= 0 }

		SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			userRating = userRating,
			ratingCount = ratingCount,
			followCount = followCount,
			commentCount = commentCount,
			chapterCommentCount = chapterCommentCount,
			canCurrentUserRate = canRate,
			canCurrentUserComment = false,
			currentUsername = username,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val mangaId = extractMangaId(manga) ?: throw ParseException("Invalid MangaDex id", manga.publicUrl)
		val clampedScore = score.coerceIn(1, 10)
		withBearerToken(requireAuth = true) { token ->
			val url = "https://api.$domain/rating/$mangaId"
			val body = JSONObject().put("rating", clampedScore)
			val responseJson = webClient.httpPost(url.toHttpUrl(), body, authHeaders(token)).parseJson()
			if (responseJson.optString("result") == "error") {
				val detail = responseJson.optJSONArray("errors")
					?.optJSONObject(0)
					?.getStringOrNull("detail")
					?: "Failed to submit rating"
				throw ParseException(detail, url)
			}
		}
		val refreshed = getMangaCommunityInfo(manga, chapter)
		return if (refreshed.userRating == null) {
			refreshed.copy(userRating = clampedScore, canCurrentUserRate = true)
		} else {
			refreshed
		}
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = SourceCommentPage(
		comments = emptyList(),
		page = page,
		hasNextPage = false,
		widgetContext = resolveWidgetContext(manga, chapter),
		engine = SourceCommentEngine.CUSTOM_WIDGET,
	)

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val mangaId = extractMangaId(manga)
		val chapterId = chapter?.let(::extractChapterId)
		val targetStats = when {
			chapterId != null -> fetchStatistics("chapter", chapterId)
			mangaId != null -> fetchStatistics("manga", mangaId)
			else -> null
		}
		val threadId = targetStats?.optJSONObject("comments")
			?.takeIf { !it.isNull("threadId") }
			?.optLong("threadId", -1L)
			?.takeIf { it > 0L }

		val fallbackUrl = when {
			chapterId != null -> "https://$domain/chapter/$chapterId"
			manga.publicUrl.isNotBlank() -> manga.publicUrl
			mangaId != null -> "https://$domain/title/$mangaId"
			else -> return null
		}

		val pageUrl = if (threadId != null) {
			"https://$FORUMS_HOST/threads/$threadId/"
		} else {
			fallbackUrl
		}

		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSTOM_WIDGET,
			pageUrl = pageUrl,
			widgetUrl = pageUrl,
			identifier = threadId?.toString() ?: chapterId ?: mangaId,
			title = chapter?.title?.nullIfEmpty() ?: manga.title.nullIfEmpty(),
			baseUrl = if (threadId != null) "https://$FORUMS_HOST/" else "https://$domain/",
			allowedHostSuffixes = setOf(FORUMS_HOST, domain, "mangadex.org", AUTH_HOST),
		)
	}

	private suspend fun fetchStatistics(type: String, id: String): JSONObject? {
		return runCatchingCancellable {
			val url = "https://api.$domain/statistics/$type/$id"
			val json = webClient.httpGet(url).parseJson()
			json.optJSONObject("statistics")?.optJSONObject(id)
		}.getOrNull()
	}

	private suspend fun fetchUsernameFromApi(accessToken: String): String {
		val url = "https://api.$domain/user/me"
		val json = webClient.httpGet(url, authHeaders(accessToken)).parseJson()
		return json.optJSONObject("data")
			?.optJSONObject("attributes")
			?.getStringOrNull("username")
			?.trim()
			?.nullIfEmpty()
			?: throw ParseException("Username not found in user/me response", url)
	}

	private suspend fun fetchUserRatingFromApi(mangaId: String, accessToken: String): Int? {
		val url = "https://api.$domain/rating?manga[]=$mangaId"
		val json = webClient.httpGet(url, authHeaders(accessToken)).parseJson()
		val ratingsObj = json.optJSONObject("ratings") ?: return null
		val entry = ratingsObj.optJSONObject(mangaId) ?: return null
		return entry.optInt("rating", 0).takeIf { it in 1..10 }
	}

	private suspend fun <T> withBearerToken(
		requireAuth: Boolean,
		block: suspend (token: String) -> T,
	): T? {
		val token = ensureAccessToken(allowSilentCookieExchange = true)
		if (token == null) {
			if (requireAuth) throw AuthRequiredException(source)
			return null
		}
		return try {
			block(token)
		} catch (e: AuthRequiredException) {
			val refreshed = ensureAccessToken(forceRefresh = true, allowSilentCookieExchange = true)
			if (refreshed == null) {
				clearStoredAuthTokens()
				if (requireAuth) throw e
				return null
			}
			try {
				block(refreshed)
			} catch (retryError: AuthRequiredException) {
				clearStoredAuthTokens()
				if (requireAuth) throw retryError
				null
			}
		}
	}

	private suspend fun ensureAccessToken(
		forceRefresh: Boolean = false,
		allowSilentCookieExchange: Boolean = true,
	): String? = withContext(Dispatchers.IO) {
		authMutex.withLock {
			val now = System.currentTimeMillis()
			val storedAccess = context.getAuthValue(source, KEY_ACCESS_TOKEN)?.nullIfEmpty()
			val expiresAt = context.getAuthValue(source, KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L

			val callbackUrl = context.getAuthValue(source, KEY_AUTH_CALLBACK_URL)?.nullIfEmpty()
			if (callbackUrl != null) {
				context.setAuthValue(source, KEY_AUTH_CALLBACK_URL, null)
				exchangeCallbackUrl(callbackUrl)?.let { return@withLock it }
			}

			if (!forceRefresh && storedAccess != null && expiresAt - TOKEN_EXPIRY_BUFFER_MS > now) {
				return@withLock storedAccess
			}

			val refreshToken = context.getAuthValue(source, KEY_REFRESH_TOKEN)?.nullIfEmpty()
			if (refreshToken != null) {
				when (
					val result = requestToken(
						mapOf(
							"grant_type" to "refresh_token",
							"client_id" to OIDC_CLIENT_ID,
							"refresh_token" to refreshToken,
						),
					)
				) {
					is OidcTokenResult.Success -> {
						persistTokenResponse(result.json)?.let { return@withLock it }
					}

					is OidcTokenResult.InvalidGrant -> {
						clearStoredAuthTokens()
					}

					is OidcTokenResult.Error -> {
						if (!forceRefresh && storedAccess != null && expiresAt > now) {
							return@withLock storedAccess
						}
						return@withLock null
					}
				}
			}

			if (allowSilentCookieExchange && hasKeycloakSessionCookies()) {
				exchangeViaSilentKeycloakSession()?.let { return@withLock it }
			}

			null
		}
	}

	private suspend fun exchangeCallbackUrl(callbackUrl: String): String? {
		val parsed = callbackUrl.toHttpUrlOrNull() ?: return null
		if (parsed.queryParameter("error") != null) return null
		val code = parsed.queryParameter("code")?.nullIfEmpty() ?: return null
		val state = parsed.queryParameter("state")?.nullIfEmpty()
		val verifier = consumePkceVerifier(state) ?: return null
		return exchangeAuthorizationCode(code, verifier)
	}

	private suspend fun exchangeViaSilentKeycloakSession(): String? {
		val pkce = generatePkcePair()
		val silentUrl = OIDC_AUTH_ENDPOINT.toHttpUrl().newBuilder()
			.addQueryParameter("client_id", OIDC_CLIENT_ID)
			.addQueryParameter("redirect_uri", OIDC_REDIRECT_URI)
			.addQueryParameter("response_type", "code")
			.addQueryParameter("scope", OIDC_SCOPE)
			.addQueryParameter("prompt", "none")
			.addQueryParameter("code_challenge", pkce.challenge)
			.addQueryParameter("code_challenge_method", "S256")
			.addQueryParameter("state", pkce.state)
			.build()
		val request = Request.Builder()
			.get()
			.url(silentUrl)
			.tag(MangaSource::class.java, source)
			.build()
		val redirectUrl = runCatchingCancellable {
			noRedirectClient.newCall(request).await().use { response ->
				response.header("Location")?.toHttpUrlOrNull()
			}
		}.getOrNull() ?: return null
		if (redirectUrl.queryParameter("error") != null) {
			return null
		}
		val returnedState = redirectUrl.queryParameter("state")?.nullIfEmpty()
		if (returnedState != null && returnedState != pkce.state) {
			return null
		}
		val code = redirectUrl.queryParameter("code")?.nullIfEmpty() ?: return null
		return exchangeAuthorizationCode(code, pkce.verifier)
	}

	private suspend fun exchangeAuthorizationCode(code: String, verifier: String): String? {
		val result = requestToken(
			mapOf(
				"grant_type" to "authorization_code",
				"client_id" to OIDC_CLIENT_ID,
				"code" to code,
				"redirect_uri" to OIDC_REDIRECT_URI,
				"code_verifier" to verifier,
			),
		)
		return if (result is OidcTokenResult.Success) {
			persistTokenResponse(result.json)
		} else {
			null
		}
	}

	private suspend fun postOidcForm(url: String, params: Map<String, String>): Response =
		noRedirectClient.newCall(
			Request.Builder()
				.url(url)
				.post(
					FormBody.Builder().apply {
						for ((key, value) in params) {
							add(key, value)
						}
					}.build(),
				)
				.tag(MangaSource::class.java, source)
				.build(),
		).await()

	private suspend fun requestToken(params: Map<String, String>): OidcTokenResult {
		return runCatchingCancellable {
			postOidcForm(OIDC_TOKEN_ENDPOINT, params).use { response ->
				val raw = response.body.string()
				if (response.isSuccessful) {
					val json = raw.toJSONObjectOrNull() ?: return@use OidcTokenResult.Error
					OidcTokenResult.Success(json)
				} else if (response.code == 400 || response.code == 401) {
					OidcTokenResult.InvalidGrant
				} else {
					OidcTokenResult.Error
				}
			}
		}.getOrElse { OidcTokenResult.Error }
	}

	private suspend fun persistTokenResponse(json: JSONObject): String? {
		val accessToken = json.getStringOrNull("access_token") ?: return null
		val expiresInSec = json.getLongOrDefault("expires_in", 300L).coerceAtLeast(30L)
		val expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L
		val refreshToken = json.getStringOrNull("refresh_token")
		val idToken = json.getStringOrNull("id_token")
		context.setAuthValue(source, KEY_ACCESS_TOKEN, accessToken)
		context.setAuthValue(source, KEY_EXPIRES_AT, expiresAtMs.toString())
		if (refreshToken != null) {
			context.setAuthValue(source, KEY_REFRESH_TOKEN, refreshToken)
		}
		if (idToken != null) {
			context.setAuthValue(source, KEY_ID_TOKEN, idToken)
		}
		val jwtUsername = extractUsernameFromJwt(idToken) ?: extractUsernameFromJwt(accessToken)
		if (jwtUsername != null) {
			context.setAuthValue(source, KEY_USERNAME, jwtUsername)
		}
		return accessToken
	}

	private suspend fun clearStoredAuthTokens() {
		context.setAuthValue(source, KEY_ACCESS_TOKEN, null)
		context.setAuthValue(source, KEY_REFRESH_TOKEN, null)
		context.setAuthValue(source, KEY_ID_TOKEN, null)
		context.setAuthValue(source, KEY_EXPIRES_AT, null)
		context.setAuthValue(source, KEY_USERNAME, null)
	}

	private fun hasKeycloakSessionCookies(): Boolean {
		val cookies = context.cookieJar.loadForRequest(OIDC_AUTH_ENDPOINT.toHttpUrl())
		return cookies.any {
			(it.name == "KEYCLOAK_IDENTITY" || it.name == "KEYCLOAK_SESSION") &&
				it.value.isNotBlank()
		}
	}

	private fun clearKeycloakCookies() {
		val authEndpointUrl = OIDC_AUTH_ENDPOINT.toHttpUrl()
		val realmUrl = OIDC_REALM_URL.toHttpUrl()
		val rootUrl = "https://$AUTH_HOST/".toHttpUrl()
		val existingNames = context.cookieJar.loadForRequest(authEndpointUrl).mapTo(linkedSetOf()) { it.name }
		val cookieNames = existingNames + setOf(
			"KEYCLOAK_IDENTITY",
			"KEYCLOAK_IDENTITY_LEGACY",
			"KEYCLOAK_SESSION",
			"KEYCLOAK_SESSION_LEGACY",
			"AUTH_SESSION_ID",
			"AUTH_SESSION_ID_LEGACY",
			"KC_RESTART",
		)
		val targets = listOf(
			realmUrl to "/realms/mangadex/",
			realmUrl to "/realms/mangadex",
			rootUrl to "/",
		)
		for ((targetUrl, path) in targets) {
			val expired = ArrayList<Cookie>(cookieNames.size * 2)
			for (name in cookieNames) {
				expired += Cookie.Builder()
					.hostOnlyDomain(AUTH_HOST)
					.path(path)
					.name(name)
					.value("")
					.expiresAt(1000L)
					.secure()
					.build()
				expired += Cookie.Builder()
					.domain(AUTH_HOST)
					.path(path)
					.name(name)
					.value("")
					.expiresAt(1000L)
					.secure()
					.build()
			}
			context.cookieJar.saveFromResponse(targetUrl, expired)
		}
	}

	private fun extractUsernameFromJwt(jwt: String?): String? {
		val payloadSegment = jwt?.split('.')?.getOrNull(1) ?: return null
		return runCatching {
			val padded = payloadSegment.replace('-', '+').replace('_', '/').let { s ->
				val mod = s.length % 4
				if (mod == 0) s else s + "=".repeat(4 - mod)
			}
			val json = JSONObject(String(context.decodeBase64(padded), Charsets.UTF_8))
			json.getStringOrNull("preferred_username")?.trim()?.nullIfEmpty()
		}.getOrNull()
	}

	private fun buildOidcAuthorizeUrl(endpoint: String): String {
		val pkce = getOrCreatePkce()
		return endpoint.toHttpUrl().newBuilder()
			.addQueryParameter("client_id", OIDC_CLIENT_ID)
			.addQueryParameter("redirect_uri", OIDC_REDIRECT_URI)
			.addQueryParameter("response_type", "code")
			.addQueryParameter("scope", OIDC_SCOPE)
			.addQueryParameter("code_challenge", pkce.challenge)
			.addQueryParameter("code_challenge_method", "S256")
			.addQueryParameter("state", pkce.state)
			.build()
			.toString()
	}

	private fun getOrCreatePkce(): PkcePair = synchronized(pkceLock) {
		currentPkce ?: generatePkcePair().also { pair ->
			currentPkce = pair
			pendingPkce[pair.state] = pair.verifier
			while (pendingPkce.size > MAX_PENDING_PKCE) {
				val oldestKey = pendingPkce.keys.firstOrNull() ?: break
				pendingPkce.remove(oldestKey)
			}
		}
	}

	private fun consumePkceVerifier(state: String?): String? = synchronized(pkceLock) {
		val verifier = if (!state.isNullOrBlank()) {
			pendingPkce.remove(state)
		} else {
			currentPkce?.verifier
		}
		if (state == null || currentPkce?.state == state) {
			currentPkce = null
		}
		verifier
	}

	private fun clearPendingPkce() = synchronized(pkceLock) {
		currentPkce = null
		pendingPkce.clear()
	}

	private fun generatePkcePair(): PkcePair {
		val verifierBytes = ByteArray(48)
		secureRandom.nextBytes(verifierBytes)
		val stateBytes = ByteArray(18)
		secureRandom.nextBytes(stateBytes)
		val verifier = base64UrlNoPadding(verifierBytes)
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(verifier.toByteArray(Charsets.US_ASCII))
		val challenge = base64UrlNoPadding(digest)
		val state = base64UrlNoPadding(stateBytes)
		return PkcePair(state = state, verifier = verifier, challenge = challenge)
	}

	private fun base64UrlNoPadding(bytes: ByteArray): String =
		context.encodeBase64(bytes)
			.replace('+', '-')
			.replace('/', '_')
			.trimEnd('=')

	private fun authHeaders(accessToken: String): Headers =
		Headers.headersOf("Authorization", "Bearer $accessToken")

	private fun extractMangaId(manga: Manga): String? =
		REGEX_UUID.find(manga.url)?.value
			?: REGEX_UUID.find(manga.publicUrl)?.value
			?: manga.url.removePrefix("/").substringBefore('?').substringBefore('/').nullIfEmpty()

	private fun extractChapterId(chapter: MangaChapter): String? =
		REGEX_UUID.find(chapter.url)?.value
			?: chapter.url.removePrefix("/").substringBefore('?').substringBefore('/').nullIfEmpty()

	private class PkcePair(
		val state: String,
		val verifier: String,
		val challenge: String,
	)

	private sealed interface OidcTokenResult {
		class Success(val json: JSONObject) : OidcTokenResult
		data object InvalidGrant : OidcTokenResult
		data object Error : OidcTokenResult
	}

	private class Chapters(
		val data: List<JSONObject>,
		val total: Int,
	) {

		val size: Int
			get() = data.size
	}
}
