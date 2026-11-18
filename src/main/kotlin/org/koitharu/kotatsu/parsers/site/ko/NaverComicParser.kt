package org.koitharu.kotatsu.parsers.site.ko

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.community.NaverCommunityHelper
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

internal abstract class NaverComicParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pageSize: Int,
	private val serviceTicketId: String,
	private val pagePrefix: String,
	protected val pathPrefix: String,
) : PagedMangaParser(context, source, pageSize = pageSize, searchPageSize = 10),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("m.comic.naver.com", "comic.naver.com")

	override val userAgentKey: ConfigKey.UserAgent = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	protected val apiDomain: String
		get() = domain.removePrefix("www.").removePrefix("m.")

	protected val mobileDomain: String
		get() = when {
			domain.startsWith("m.") -> domain
			domain.startsWith("www.") -> "m.${domain.removePrefix("www.")}"
			else -> "m.$domain"
		}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	protected val availableTags: Set<MangaTag> by lazy {
		GENRE_ENTRIES.mapTo(linkedSetOf()) { (key, title) ->
			MangaTag(title = title, key = key, source = source)
		}
	}

	protected val tagsByKey: Map<String, MangaTag> by lazy {
		availableTags.associateBy { it.key }
	}

	private val communityHelper by lazy {
		NaverCommunityHelper(
			context = context,
			webClient = webClient,
			source = source,
			serviceTicketId = serviceTicketId,
			pagePrefix = pagePrefix,
			pathSegmentProvider = { pathPrefix },
			mobileDomainProvider = { mobileDomain },
			apiDomainProvider = { apiDomain },
			mobileHeadersProvider = ::mobileHeaders,
			apiHeadersProvider = ::apiHeaders,
		)
	}

	override val authUrl: String
		get() = communityHelper.authUrl

	override val capabilities: SourceCommunityCapabilities
		get() = communityHelper.capabilities

	override suspend fun isAuthorized(): Boolean = communityHelper.isAuthorized()

	override suspend fun getUsername(): String = communityHelper.getUsername()

	override suspend fun logout() = communityHelper.logout()

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = communityHelper.getMangaCommunityInfo(manga, chapter)

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = communityHelper.getComments(manga, chapter, page, sort, cursor)

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage = communityHelper.getReplies(manga, comment, chapter, page, cursor)

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment = communityHelper.postComment(manga, text, chapter, parentComment, isSpoiler)

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) = communityHelper.deleteComment(manga, comment, chapter)

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment = communityHelper.toggleCommentLike(manga, comment, isLike, chapter)

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? = communityHelper.resolveWidgetContext(manga, chapter)

	protected fun apiHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_DESKTOP)
		.add("Referer", "https://$apiDomain/")
		.add("Origin", "https://$apiDomain")
		.add("Accept", "application/json")
		.build()

	protected fun mobileHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.CHROME_MOBILE)
		.add("Referer", "https://$mobileDomain/")
		.build()

	override fun getRequestHeaders(): Headers = mobileHeaders()

	override suspend fun getFavicons(): Favicons = Favicons(
		favicons = listOf(
			Favicon("https://shared-comic.pstatic.net/favicon/favicon_192x192.ico", 192, null),
			Favicon("https://$mobileDomain/favicon.ico", 16, null),
		),
		referer = "https://$mobileDomain/",
	)

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val host = link.host.removePrefix("www.").lowercase(Locale.ROOT)
		val knownHosts = setOf(
			"comic.naver.com",
			"m.comic.naver.com",
			apiDomain.lowercase(Locale.ROOT),
			mobileDomain.lowercase(Locale.ROOT),
		)
		if (host !in knownHosts) {
			return null
		}
		val firstSeg = link.pathSegments.firstOrNull() ?: return null
		if (!firstSeg.equals(pathPrefix, ignoreCase = false)) {
			return null
		}
		val titleId = link.queryParameter("titleId")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
		val relativeUrl = "/$pathPrefix/list?titleId=$titleId"
		val stub = Manga(
			id = generateUid(relativeUrl),
			title = "",
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "https://$mobileDomain$relativeUrl",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return getDetails(stub)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			return fetchSearchPage(query, page)
		}

		val selectedTag = filter.tags.oneOrThrowIfMany()
		if (selectedTag != null) {
			if (selectedTag.key in tagsByKey) {
				return fetchGenreListPage(selectedTag, page, order, filter.states)
			}
			if (selectedTag.key.startsWith(CUSTOM_TAG_KEY_PREFIX)) {
				val customId = selectedTag.key.removePrefix(CUSTOM_TAG_KEY_PREFIX)
				return fetchCustomTagPage(customId, page, selectedTag)
			}
			return fetchSearchPage(selectedTag.title, page)
		}

		return fetchDefaultListPage(page, order, filter.states)
	}

	protected abstract suspend fun fetchGenreListPage(
		tag: MangaTag,
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga>

	protected abstract suspend fun fetchDefaultListPage(
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga>

	protected suspend fun fetchSearchPage(query: String, page: Int): List<Manga> {
		val url = "https://$apiDomain/api/search/$pathPrefix?keyword=${query.urlEncoded()}&page=$page"
		val json = fetchApiJson(url)
		if (isPastTotalPages(json, page)) {
			return emptyList()
		}
		val searchList = json.optJSONArray("searchList") ?: return emptyList()
		return parseMangaList(searchList, extraTag = null)
	}

	private suspend fun fetchCustomTagPage(customId: String, page: Int, selectedTag: MangaTag): List<Manga> {
		val url = "https://$apiDomain/api/curation/list?type=CUSTOM_TAG&id=${customId.urlEncoded()}&page=$page"
		val json = fetchApiJson(url)
		if (isPastTotalPages(json, page)) {
			return emptyList()
		}
		val list = json.optJSONArray("curationViewList") ?: return emptyList()
		return parseMangaList(list, extraTag = selectedTag)
	}

	protected suspend fun fetchApiJson(url: String): JSONObject {
		return webClient.httpGet(url, apiHeaders()).parseJson()
	}

	protected fun isPastTotalPages(json: JSONObject, page: Int): Boolean {
		val totalPages = json.optJSONObject("pageInfo")?.getIntOrDefault("totalPages", 0) ?: 0
		return totalPages > 0 && page > totalPages
	}

	protected fun parseMangaList(array: JSONArray, extraTag: MangaTag?): List<Manga> {
		val result = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			parseMangaItem(jo, extraTag)?.let(result::add)
		}
		return result
	}

	protected fun parseMangaItem(jo: JSONObject, extraTag: MangaTag?): Manga? {
		val titleId = jo.getLongOrDefault("titleId", 0L)
		if (titleId <= 0L) return null
		val title = jo.getStringOrNull("titleName")?.trim() ?: return null
		val relativeUrl = "/$pathPrefix/list?titleId=$titleId"
		val coverUrl = jo.getStringOrNull("thumbnailUrl")?.toAbsoluteUrl(apiDomain)
		val authors = extractAuthors(jo)
		val description = jo.getStringOrNull("synopsis")?.trim()
		val state = parseMangaState(jo)
		val isAdult = jo.getBooleanOrDefault("adult", false) || jo.getBooleanOrDefault("nineteen", false)
		val rawScore = jo.getDoubleOrDefault("starScore", jo.getDoubleOrDefault("averageStarScore", 0.0))
		val rating = if (rawScore > 0.0) {
			(rawScore / 10.0).toFloat().coerceIn(0f, 1f)
		} else {
			RATING_UNKNOWN
		}
		val tags = buildListItemTags(jo, extraTag)
		return Manga(
			id = generateUid(relativeUrl),
			title = title,
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "https://$mobileDomain$relativeUrl",
			rating = rating,
			contentRating = if (isAdult) ContentRating.ADULT else null,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			description = description,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val titleId = extractTitleId(manga.url)
		val infoDeferred = async {
			fetchApiJson("https://$apiDomain/api/article/list/info?titleId=$titleId")
		}
		val page1Deferred = async {
			fetchApiJson("https://$apiDomain/api/article/list?titleId=$titleId&page=1&sort=ASC")
		}

		val infoJson = infoDeferred.await()
		val page1Json = page1Deferred.await()

		val totalPages = page1Json.optJSONObject("pageInfo")?.getIntOrDefault("totalPages", 1)?.coerceAtLeast(1) ?: 1
		val articlePages = if (totalPages > 1) {
			val semaphore = Semaphore(CHAPTER_PAGE_CONCURRENCY)
			val remaining = (2..totalPages).map { p ->
				async {
					semaphore.withPermit {
						fetchApiJson("https://$apiDomain/api/article/list?titleId=$titleId&page=$p&sort=ASC")
					}
				}
			}.awaitAll()
			listOf(page1Json) + remaining
		} else {
			listOf(page1Json)
		}

		val seenNos = HashSet<Int>()
		val chapters = ArrayList<MangaChapter>()
		var scoreSum = 0.0
		var scoreCount = 0

		for (pageJson in articlePages) {
			val articleList = pageJson.optJSONArray("articleList") ?: continue
			for (i in 0 until articleList.length()) {
				val item = articleList.optJSONObject(i) ?: continue
				val no = item.getIntOrDefault("no", 0)
				if (no <= 0 || !seenNos.add(no)) continue

				val starScore = item.getDoubleOrDefault("starScore", 0.0)
				if (starScore > 0.0) {
					scoreSum += starScore
					scoreCount++
				}

				val rawSubtitle = item.getStringOrNull("subtitle")?.trim()
				val isLocked = item.getBooleanOrDefault("charge", false) ||
					item.getBooleanOrDefault("thumbnailLock", false)
				val chapterTitle = when {
					isLocked && !rawSubtitle.isNullOrEmpty() -> "\uD83D\uDD12 $rawSubtitle"
					isLocked -> "\uD83D\uDD12 #$no"
					else -> rawSubtitle
				}
				val chapterUrl = "/$pathPrefix/detail?titleId=$titleId&no=$no"
				val uploadDate = parseArticleDate(item.getStringOrNull("serviceDateDescription"))

				chapters.add(
					MangaChapter(
						id = generateUid(chapterUrl),
						title = chapterTitle,
						number = no.toFloat(),
						volume = 0,
						url = chapterUrl,
						scanlator = null,
						uploadDate = uploadDate,
						branch = null,
						source = source,
					),
				)
			}
		}
		chapters.sortBy { it.number }

		val title = infoJson.getStringOrNull("titleName")?.trim() ?: manga.title
		val posterCover = infoJson.getStringOrNull("posterThumbnailUrl")?.toAbsoluteUrl(apiDomain)
		val thumbCover = infoJson.getStringOrNull("thumbnailUrl")?.toAbsoluteUrl(apiDomain)
		val sharedCover = infoJson.getStringOrNull("sharedThumbnailUrl")?.toAbsoluteUrl(apiDomain)
		val coverUrl = posterCover ?: thumbCover ?: sharedCover ?: manga.coverUrl
		val largeCoverUrl = posterCover ?: sharedCover ?: manga.largeCoverUrl
		val authors = extractAuthors(infoJson).ifEmpty { manga.authors }
		val synopsis = infoJson.getStringOrNull("synopsis")?.trim() ?: manga.description.orEmpty()
		val state = parseMangaState(infoJson)

		val ageType = infoJson.optJSONObject("age")?.getStringOrNull("type")
		val contentRating = when {
			ageType.equals("RATE_18", ignoreCase = true) ||
				infoJson.getBooleanOrDefault("adult", false) ||
				infoJson.getBooleanOrDefault("nineteen", false) -> ContentRating.ADULT

			ageType.equals("RATE_15", ignoreCase = true) -> ContentRating.SUGGESTIVE
			else -> ContentRating.SAFE
		}

		val tags = extractDetailsTags(infoJson, manga.tags)
		val rating = when {
			manga.rating != RATING_UNKNOWN -> manga.rating
			scoreCount > 0 -> ((scoreSum / scoreCount) / 10.0).toFloat().coerceIn(0f, 1f)
			else -> RATING_UNKNOWN
		}

		manga.copy(
			title = title,
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			authors = authors,
			description = synopsis,
			state = state,
			contentRating = contentRating,
			tags = tags,
			rating = rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(mobileDomain)
		val response = webClient.httpGet(fullUrl, mobileHeaders())
		val finalUrl = response.request.url.toString()
		if (finalUrl.contains("nidlogin", ignoreCase = true) ||
			finalUrl.contains("nid.naver.com", ignoreCase = true)
		) {
			response.close()
			throw AuthRequiredException(source)
		}

		val doc = response.parseHtml()
		val elements = doc.select("#toonLayer img.toon_image")
			.ifEmpty { doc.select("#toonLayer img[id^=toon_]") }

		val pages = ArrayList<MangaPage>(elements.size)
		for (element in elements) {
			val imgUrl = element.attrAsAbsoluteUrlOrNull("data-src")
				?: element.attrAsAbsoluteUrlOrNull("src")
				?: continue
			if (isPlaceholderPageUrl(imgUrl)) continue
			pages.add(
				MangaPage(
					id = generateUid("${chapter.url}#${pages.size}"),
					url = imgUrl,
					preview = null,
					source = source,
				),
			)
		}

		if (pages.isEmpty()) {
			val html = doc.html()
			val text = doc.text()
			val needsAuth = doc.selectFirst(".btn_login, .adult_wrap, .viewer_lock, .notice_adult") != null ||
				AUTH_HTML_INDICATORS.any { indicator ->
					html.contains(indicator, ignoreCase = true) || text.contains(indicator, ignoreCase = true)
				}
			if (needsAuth) {
				throw AuthRequiredException(source)
			}
			doc.parseFailed("No pages found")
		}

		return pages
	}

	private fun isPlaceholderPageUrl(url: String): Boolean {
		return url.contains("bg_transparency", ignoreCase = true) ||
			url.contains("blank.", ignoreCase = true) ||
			url.contains("/agerate/", ignoreCase = true)
	}

	protected fun extractAuthors(jo: JSONObject): Set<String> {
		val names = LinkedHashSet<String>()
		jo.optJSONArray("communityArtists")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optJSONObject(i)?.getStringOrNull("name")?.trim()?.nullIfEmpty()?.let(names::add)
			}
		}
		if (names.isNotEmpty()) {
			return names
		}
		for (arrayKey in ARTIST_ARRAY_KEYS) {
			val arr = jo.optJSONArray(arrayKey) ?: continue
			for (i in 0 until arr.length()) {
				arr.optJSONObject(i)?.getStringOrNull("name")?.trim()?.nullIfEmpty()?.let(names::add)
			}
		}
		if (names.isNotEmpty()) {
			return names
		}
		val fallback = jo.getStringOrNull("displayAuthor")?.trim()
			?: jo.getStringOrNull("author")?.trim()
		return if (!fallback.isNullOrEmpty()) setOf(fallback) else emptySet()
	}

	protected fun parseMangaState(jo: JSONObject): MangaState {
		val isFinished = jo.getBooleanOrDefault("finished", jo.getBooleanOrDefault("finish", false))
		val isRest = jo.getBooleanOrDefault("rest", false)
		return when {
			isFinished -> MangaState.FINISHED
			isRest -> MangaState.PAUSED
			else -> MangaState.ONGOING
		}
	}

	private fun buildListItemTags(jo: JSONObject, extraTag: MangaTag?): Set<MangaTag> {
		val byKey = LinkedHashMap<String, MangaTag>()
		if (extraTag != null) {
			byKey[extraTag.key] = extraTag
		}
		jo.optJSONArray("genreList")?.let { arr ->
			for (i in 0 until arr.length()) {
				val type = arr.optJSONObject(i)?.getStringOrNull("type")?.trim() ?: continue
				tagsByKey[type]?.let { byKey[it.key] = it }
			}
		}
		jo.optJSONArray("tagList")?.let { arr ->
			for (i in 0 until arr.length()) {
				val tagObj = arr.optJSONObject(i) ?: continue
				val curationType = tagObj.getStringOrNull("curationType") ?: continue
				if (curationType.startsWith("GENRE_")) {
					val genreKey = curationType.removePrefix("GENRE_")
					tagsByKey[genreKey]?.let { byKey[it.key] = it }
				}
			}
		}
		return byKey.values.toSet()
	}

	private fun extractDetailsTags(infoJson: JSONObject, existingTags: Set<MangaTag>): Set<MangaTag> {
		val byKey = LinkedHashMap<String, MangaTag>()
		val seenTitles = HashSet<String>()
		val genreKoreanNames = HashSet<String>()

		fun addTag(tag: MangaTag) {
			val normTitle = tag.title.trim().lowercase(Locale.ROOT)
			if (normTitle.isEmpty() || !seenTitles.add(normTitle)) return
			byKey.putIfAbsent(tag.key, tag)
		}

		infoJson.optJSONArray("curationTagList")?.let { arr ->
			for (i in 0 until arr.length()) {
				val tagObj = arr.optJSONObject(i) ?: continue
				val curationType = tagObj.getStringOrNull("curationType")
				val tagName = tagObj.getStringOrNull("tagName")?.trim()
				if (curationType != null && curationType.startsWith("GENRE_")) {
					val genreKey = curationType.removePrefix("GENRE_")
					tagsByKey[genreKey]?.let { canonical ->
						addTag(canonical)
						if (!tagName.isNullOrEmpty()) {
							genreKoreanNames.add(tagName.lowercase(Locale.ROOT))
						}
					}
					continue
				}
				if (curationType == "CUSTOM_TAG" && !tagName.isNullOrEmpty()) {
					val tagId = tagObj.getLongOrDefault("id", 0L)
					val key = if (tagId > 0L) "$CUSTOM_TAG_KEY_PREFIX$tagId" else "TAG_$tagName"
					addTag(MangaTag(title = tagName.toTitleCase(sourceLocale), key = key, source = source))
				}
			}
		}

		infoJson.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				val obj = arr.optJSONObject(i) ?: continue
				val type = obj.getStringOrNull("type")?.trim() ?: continue
				tagsByKey[type]?.let { canonical ->
					addTag(canonical)
					obj.getStringOrNull("description")?.trim()?.lowercase(Locale.ROOT)?.let(genreKoreanNames::add)
				}
			}
		}

		infoJson.optJSONArray("challengeTagList")?.let { arr ->
			for (i in 0 until arr.length()) {
				val raw = arr.optString(i).trim()
				if (raw.isEmpty()) continue
				if (raw.lowercase(Locale.ROOT) in genreKoreanNames) continue
				addTag(
					MangaTag(
						title = raw.toTitleCase(sourceLocale),
						key = "$CHALLENGE_TAG_KEY_PREFIX$raw",
						source = source,
					),
				)
			}
		}

		for (tag in existingTags) {
			addTag(tag)
		}

		return byKey.values.toSet()
	}

	private fun extractTitleId(url: String): Long {
		val parsed = url.toAbsoluteUrl(mobileDomain)
			.toHttpUrlOrNull()
			?.queryParameter("titleId")
			?.toLongOrNull()
			?.takeIf { it > 0L }
		if (parsed != null) return parsed
		return REGEX_TITLE_ID.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
			?: url.toLongOrNull()?.takeIf { it > 0L }
			?: throw ParseException("Missing titleId in URL: $url", url)
	}

	private fun parseArticleDate(dateStr: String?): Long {
		val trimmed = dateStr?.trim()?.nullIfEmpty() ?: return 0L
		val pattern = when {
			REGEX_DATE_SHORT.matches(trimmed) -> "yy.MM.dd"
			REGEX_DATE_LONG.matches(trimmed) -> "yyyy.MM.dd"
			else -> return 0L
		}
		return runCatching {
			SimpleDateFormat(pattern, Locale.KOREA).apply {
				timeZone = SEOUL_TIMEZONE
			}.parse(trimmed)?.time ?: 0L
		}.getOrDefault(0L)
	}

	protected companion object {
		const val CHAPTER_PAGE_CONCURRENCY = 8
		const val CUSTOM_TAG_KEY_PREFIX = "CUSTOM_TAG:"
		const val CHALLENGE_TAG_KEY_PREFIX = "CHALLENGE_TAG:"
		val SEOUL_TIMEZONE: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
		val REGEX_TITLE_ID = Regex("""[?&]titleId=(\d+)""")
		val REGEX_DATE_SHORT = Regex("""\d{2}\.\d{2}\.\d{2}""")
		val REGEX_DATE_LONG = Regex("""\d{4}\.\d{2}\.\d{2}""")
		val ARTIST_ARRAY_KEYS = arrayOf("writers", "painters", "novelOriginAuthors", "artists")
		val AUTH_HTML_INDICATORS = arrayOf(
			"nidlogin",
			"nid.naver.com",
			"성인인증",
			"성인 인증",
			"로그인이 필요",
			"쿠키를 사용",
			"미리보기",
			"이용권",
		)
		val GENRE_ENTRIES = listOf(
			"PURE" to "Romance",
			"FANTASY" to "Fantasy",
			"ACTION" to "Action",
			"DAILY" to "Slice of Life",
			"THRILL" to "Thriller",
			"COMIC" to "Comedy",
			"HISTORICAL" to "Historical",
			"DRAMA" to "Drama",
			"SENSIBILITY" to "Sentimental",
			"SPORTS" to "Sports",
		)
	}
}

@MangaSourceParser("NAVERWEBTOON", "Naver Webtoon", "ko")
internal class NaverWebtoonParser(context: MangaLoaderContext) : NaverComicParser(
	context = context,
	source = MangaParserSource.NAVERWEBTOON,
	pageSize = 45,
	serviceTicketId = "comic_webtoon",
	pagePrefix = "webtoon",
	pathPrefix = "webtoon",
) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTags,
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun fetchGenreListPage(
		tag: MangaTag,
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga> {
		val apiOrder = mapWebtoonOrder(order)
		val wantOngoing = MangaState.ONGOING in states
		val wantFinished = MangaState.FINISHED in states
		val expectedFinished: Boolean? = when {
			wantOngoing && !wantFinished -> false
			wantFinished && !wantOngoing -> true
			else -> null
		}

		if (expectedFinished == null) {
			val url = "https://$apiDomain/api/webtoon/titlelist/genre?genre=${tag.key.urlEncoded()}&order=$apiOrder&page=$page"
			val json = fetchApiJson(url)
			if (isPastTotalPages(json, page)) {
				return emptyList()
			}
			val titleList = json.optJSONArray("titleList") ?: return emptyList()
			return parseMangaList(titleList, extraTag = tag)
		}

		val page1Url = "https://$apiDomain/api/webtoon/titlelist/genre?genre=${tag.key.urlEncoded()}&order=$apiOrder&page=1"
		val page1Json = fetchApiJson(page1Url)
		val totalPages = page1Json.optJSONObject("pageInfo")?.getIntOrDefault("totalPages", 1)?.coerceAtLeast(1) ?: 1
		val pages = if (totalPages > 1) {
			coroutineScope {
				val semaphore = Semaphore(CHAPTER_PAGE_CONCURRENCY)
				val rest = (2..totalPages).map { p ->
					async {
						semaphore.withPermit {
							fetchApiJson(
								"https://$apiDomain/api/webtoon/titlelist/genre?genre=${tag.key.urlEncoded()}&order=$apiOrder&page=$p",
							)
						}
					}
				}.awaitAll()
				listOf(page1Json) + rest
			}
		} else {
			listOf(page1Json)
		}

		val filtered = ArrayList<Manga>()
		for (pageJson in pages) {
			val titleList = pageJson.optJSONArray("titleList") ?: continue
			for (i in 0 until titleList.length()) {
				val jo = titleList.optJSONObject(i) ?: continue
				val isFinished = jo.getBooleanOrDefault("finished", jo.getBooleanOrDefault("finish", false))
				if (isFinished == expectedFinished) {
					parseMangaItem(jo, extraTag = tag)?.let(filtered::add)
				}
			}
		}

		val start = (page - 1) * pageSize
		if (start >= filtered.size) {
			return emptyList()
		}
		val end = minOf(page * pageSize, filtered.size)
		return filtered.subList(start, end)
	}

	override suspend fun fetchDefaultListPage(
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga> {
		val apiOrder = mapWebtoonOrder(order)
		val wantOngoing = MangaState.ONGOING in states
		val wantFinished = MangaState.FINISHED in states
		val onlyFinished = wantFinished && !wantOngoing
		val onlyOngoing = wantOngoing && !wantFinished

		if (onlyFinished) {
			val url = "https://$apiDomain/api/webtoon/titlelist/finished?page=$page&pageSize=$pageSize&order=$apiOrder"
			val json = fetchApiJson(url)
			if (isPastTotalPages(json, page)) {
				return emptyList()
			}
			val titleList = json.optJSONArray("titleList") ?: return emptyList()
			return parseMangaList(titleList, extraTag = null)
		}

		val weekdayUrl = "https://$apiDomain/api/webtoon/titlelist/weekday?order=$apiOrder"
		val weekdayJson = fetchApiJson(weekdayUrl)
		val deduplicated = LinkedHashMap<Long, JSONObject>()
		val titleListMap = weekdayJson.optJSONObject("titleListMap")
		if (titleListMap != null) {
			val dayKeys = titleListMap.keys()
			while (dayKeys.hasNext()) {
				val dayKey = dayKeys.next()
				val dayArray = titleListMap.optJSONArray(dayKey) ?: continue
				for (i in 0 until dayArray.length()) {
					val jo = dayArray.optJSONObject(i) ?: continue
					val titleId = jo.getLongOrDefault("titleId", 0L)
					if (titleId > 0L) {
						deduplicated.putIfAbsent(titleId, jo)
					}
				}
			}
		}

		val ongoingList = ArrayList<Manga>(deduplicated.size)
		for (jo in deduplicated.values) {
			parseMangaItem(jo, extraTag = null)?.let(ongoingList::add)
		}

		val ongoingPages = if (ongoingList.isEmpty()) 0 else (ongoingList.size + pageSize - 1) / pageSize
		if (page <= ongoingPages) {
			val start = (page - 1) * pageSize
			val end = minOf(page * pageSize, ongoingList.size)
			return ongoingList.subList(start, end)
		}

		if (onlyOngoing) {
			return emptyList()
		}

		val finishedPage = page - ongoingPages
		val finishedUrl = "https://$apiDomain/api/webtoon/titlelist/finished?page=$finishedPage&pageSize=$pageSize&order=$apiOrder"
		val finishedJson = fetchApiJson(finishedUrl)
		if (isPastTotalPages(finishedJson, finishedPage)) {
			return emptyList()
		}
		val finishedList = finishedJson.optJSONArray("titleList") ?: return emptyList()
		return parseMangaList(finishedList, extraTag = null)
	}

	private fun mapWebtoonOrder(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "VIEW"
		SortOrder.RATING -> "STAR"
		SortOrder.UPDATED -> "UPDATE"
		SortOrder.NEWEST -> "USER"
		else -> "VIEW"
	}
}

internal abstract class NaverChallengeBaseParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	pathPrefix: String,
) : NaverComicParser(
	context = context,
	source = source,
	pageSize = 30,
	serviceTicketId = "comic_challenge",
	pagePrefix = "challenge",
	pathPrefix = pathPrefix,
) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTags,
	)

	override suspend fun fetchGenreListPage(
		tag: MangaTag,
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga> {
		val bcOrder = mapChallengeOrder(order)
		val url = "https://$apiDomain/api/$pathPrefix/list?genre=${tag.key.urlEncoded()}&page=$page&order=$bcOrder"
		val json = fetchApiJson(url)
		if (isPastTotalPages(json, page)) {
			return emptyList()
		}
		val list = json.optJSONArray("list") ?: return emptyList()
		return parseMangaList(list, extraTag = tag)
	}

	override suspend fun fetchDefaultListPage(
		page: Int,
		order: SortOrder,
		states: Set<MangaState>,
	): List<Manga> {
		val bcOrder = mapChallengeOrder(order)
		val url = "https://$apiDomain/api/$pathPrefix/list?page=$page&order=$bcOrder"
		val json = fetchApiJson(url)
		if (isPastTotalPages(json, page)) {
			return emptyList()
		}
		val list = json.optJSONArray("list") ?: return emptyList()
		return parseMangaList(list, extraTag = null)
	}

	private fun mapChallengeOrder(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "VIEW"
		SortOrder.UPDATED -> "UPDATE"
		else -> "UPDATE"
	}
}

@MangaSourceParser("NAVERBESTCHALLENGE", "Naver Best Challenge", "ko")
internal class NaverBestChallengeParser(context: MangaLoaderContext) : NaverChallengeBaseParser(
	context = context,
	source = MangaParserSource.NAVERBESTCHALLENGE,
	pathPrefix = "bestChallenge",
)

@MangaSourceParser("NAVERCHALLENGE", "Naver Challenge", "ko")
internal class NaverChallengeParser(context: MangaLoaderContext) : NaverChallengeBaseParser(
	context = context,
	source = MangaParserSource.NAVERCHALLENGE,
	pathPrefix = "challenge",
)
