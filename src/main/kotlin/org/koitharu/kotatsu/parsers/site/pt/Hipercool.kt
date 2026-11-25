package org.koitharu.kotatsu.parsers.site.pt

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("HIPERCOOL", "Hipercool", "pt", ContentType.HENTAI)
internal class Hipercool(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.HIPERCOOL,
	pageSize = 30,
) {

	override val configKeyDomain = ConfigKey.Domain("lerhentais.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = GENRES.mapTo(LinkedHashSet(GENRES.size)) { genre ->
				MangaTag(
					key = genre,
					title = genre,
					source = source,
				)
			},
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.NOVEL,
				ContentType.COMICS,
				ContentType.ONE_SHOT,
			),
			availableContentRating = EnumSet.of(
				ContentRating.ADULT,
				ContentRating.SUGGESTIVE,
				ContentRating.SAFE,
			),
		)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set(HEADER_FLUX_NODE, FLUX_NODE_VALUE)
		.build()

	private val authLock = Any()

	override fun intercept(chain: Interceptor.Chain): Response {
		val original = chain.request()
		if (original.url.host != domain) {
			return chain.proceed(original)
		}
		val requestBuilder = original.newBuilder()
		if (original.header(HEADER_FLUX_NODE).isNullOrEmpty()) {
			requestBuilder.header(HEADER_FLUX_NODE, FLUX_NODE_VALUE)
		}
		if (original.header("Referer").isNullOrEmpty()) {
			requestBuilder.header("Referer", "https://$domain/")
		}
		val request = requestBuilder.build()
		val response = chain.proceed(request)
		if (response.code != 401 || request.tag(AuthRetryTag::class.java) != null) {
			return response
		}
		response.close()
		synchronized(authLock) {
			val bootstrapRequest = Request.Builder()
				.url("https://$domain/")
				.get()
				.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				.header(HEADER_FLUX_NODE, FLUX_NODE_VALUE)
				.header("Referer", "https://$domain/")
				.apply {
					request.header("User-Agent")?.let { header("User-Agent", it) }
				}
				.tag(MangaParserSource::class.java, source)
				.tag(AuthRetryTag::class.java, AuthRetryTag)
				.build()
			runCatching {
				chain.proceed(bootstrapRequest).close()
			}
		}
		val retryRequest = request.newBuilder()
			.tag(AuthRetryTag::class.java, AuthRetryTag)
			.build()
		return chain.proceed(retryRequest)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val typeValue = filter.types.oneOrThrowIfMany()?.let { type ->
			when (type) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				ContentType.NOVEL -> "novel"
				ContentType.COMICS -> "webtoon"
				ContentType.ONE_SHOT -> "one_shot"
				else -> null
			}
		}
		val statusValue = filter.states.oneOrThrowIfMany()?.let { state ->
			when (state) {
				MangaState.ONGOING -> "ongoing"
				MangaState.FINISHED -> "completed"
				MangaState.PAUSED -> "hiatus"
				MangaState.ABANDONED -> "cancelled"
				else -> null
			}
		}
		val ratingValue = filter.contentRating.oneOrThrowIfMany()?.let { rating ->
			when (rating) {
				ContentRating.ADULT -> "pornographic"
				ContentRating.SUGGESTIVE -> "suggestive"
				ContentRating.SAFE -> "safe"
			}
		}
		val genresList = filter.tags.map { it.key }
		val sortValue = when (order) {
			SortOrder.POPULARITY -> "popular"
			SortOrder.UPDATED -> "recent"
			SortOrder.NEWEST -> "newest"
			SortOrder.NEWEST_ASC -> "oldest"
			SortOrder.RATING -> "score"
			SortOrder.ALPHABETICAL -> "alphabetical"
			SortOrder.RELEVANCE -> "relevance"
			else -> "popular"
		}

		val filtersJson = JSONObject().apply {
			if (genresList.isEmpty()) {
				put("genres", JSONObject.NULL)
			} else {
				put("genres", JSONArray(genresList))
			}
			put("type", typeValue ?: JSONObject.NULL)
			put("status", statusValue ?: JSONObject.NULL)
			put("contentRating", ratingValue ?: JSONObject.NULL)
			put("author", JSONObject.NULL)
			put("artist", JSONObject.NULL)
			put("year", JSONObject.NULL)
		}

		val metaValues = JSONObject().apply {
			if (genresList.isEmpty()) put("filters.genres", JSONArray().put("undefined"))
			if (typeValue == null) put("filters.type", JSONArray().put("undefined"))
			if (statusValue == null) put("filters.status", JSONArray().put("undefined"))
			if (ratingValue == null) put("filters.contentRating", JSONArray().put("undefined"))
			put("filters.author", JSONArray().put("undefined"))
			put("filters.artist", JSONArray().put("undefined"))
			put("filters.year", JSONArray().put("undefined"))
		}

		val payload = JSONObject().put(
			"0",
			JSONObject()
				.put(
					"json",
					JSONObject()
						.put("q", filter.query.orEmpty())
						.put("sort", sortValue)
						.put("filters", filtersJson)
						.put("limit", pageSize)
						.put("offset", (page - 1) * pageSize)
						.put("maxRating", "pornographic"),
				)
				.put("meta", JSONObject().put("values", metaValues)),
		)

		val url = trpcUrl("search.query", payload)
		val response = webClient.httpGet(url).parseJsonArray()
		val data = extractTrpcJsonObject(response, 0) ?: return emptyList()
		val hits = data.optJSONArray("hits") ?: return emptyList()
		return hits.mapJSONNotNull(::parseManga)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlug(manga.url)
		val detailsPayload = JSONObject().put(
			"0",
			JSONObject().put(
				"json",
				JSONObject().put("slug", slug),
			),
		)
		val detailsResponse = webClient.httpGet(trpcUrl("series.bySlugWithGenres", detailsPayload)).parseJsonArray()
		val seriesJson = extractTrpcJsonObject(detailsResponse, 0) ?: return manga
		val seriesId = seriesJson.optLong("id", 0L)
		val parsed = parseManga(seriesJson) ?: manga

		val chapters = if (seriesId > 0L) {
			fetchChapters(slug, seriesId)
		} else {
			emptyList()
		}

		return manga.copy(
			title = parsed.title.ifEmpty { manga.title },
			altTitles = parsed.altTitles.ifEmpty { manga.altTitles },
			url = parsed.url,
			publicUrl = parsed.publicUrl,
			rating = if (parsed.rating != RATING_UNKNOWN) parsed.rating else manga.rating,
			contentRating = parsed.contentRating ?: manga.contentRating,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = parsed.largeCoverUrl ?: parsed.coverUrl ?: manga.largeCoverUrl,
			tags = parsed.tags.ifEmpty { manga.tags },
			state = parsed.state ?: manga.state,
			authors = parsed.authors.ifEmpty { manga.authors },
			description = seriesJson.getStringOrNull("synopsis").orEmpty(),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.trim('/').split('/')
		val slug = if (segments.size >= 2 && segments[0] == MANGA_PATH) {
			segments[1]
		} else {
			extractSlug(chapter.url)
		}
		val chapterId = segments.getOrNull(2)?.takeIf { it.all(Char::isDigit) }
		val numberFromUrl = segments.getOrNull(3)?.toDoubleOrNull()
		val chapterNumber = when {
			numberFromUrl != null && numberFromUrl > 0.0 -> numberFromUrl
			chapter.number > 0f -> chapter.number.toDouble()
			else -> 1.0
		}

		val reqJson = JSONObject()
			.put("seriesSlug", slug)
			.put(
				"chapterNumber",
				if (chapterNumber % 1.0 == 0.0) chapterNumber.toLong() else chapterNumber,
			)
		if (!chapterId.isNullOrEmpty()) {
			reqJson.put("chapterId", chapterId)
		}
		val payload = JSONObject().put(
			"0",
			JSONObject().put("json", reqJson),
		)
		val response = webClient.httpGet(trpcUrl("reader.chapterPages", payload)).parseJsonArray()
		val pagesArray = extractTrpcJsonArray(response, 0) ?: return emptyList()

		val parsed = pagesArray.mapJSONNotNull { pageJson ->
			val rawUrl = pageJson.getStringOrNull("webpUrl")
				?: pageJson.getStringOrNull("avifUrl")
				?: return@mapJSONNotNull null
			val absoluteUrl = rawUrl.toAbsoluteUrl(domain)
			val order = pageJson.optInt("pageOrder", 0)
			order to MangaPage(
				id = generateUid(absoluteUrl.substringBefore('?')),
				url = absoluteUrl,
				preview = null,
				source = source,
			)
		}
		return parsed.sortedBy { it.first }.map { it.second }
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments.filter { it.isNotBlank() }
		if (segments.size < 2 || segments[0] != MANGA_PATH) {
			return null
		}
		val slug = segments[1]
		val payload = JSONObject().put(
			"0",
			JSONObject().put("json", JSONObject().put("slug", slug)),
		)
		val response = webClient.httpGet(trpcUrl("series.bySlugWithGenres", payload)).parseJsonArray()
		val seriesJson = extractTrpcJsonObject(response, 0) ?: return null
		return parseManga(seriesJson)
	}

	private suspend fun fetchChapters(slug: String, seriesId: Long): List<MangaChapter> {
		val payload = JSONObject().put(
			"0",
			JSONObject().put(
				"json",
				JSONObject().put("seriesId", seriesId),
			),
		)
		val response = webClient.httpGet(trpcUrl("series.chapters", payload)).parseJsonArray()
		val chaptersArray = extractTrpcJsonArray(response, 0) ?: return emptyList()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val raw = chaptersArray.mapJSONNotNull { chJson ->
			val chapterId = chJson.optLong("id", 0L)
			if (chapterId <= 0L) return@mapJSONNotNull null
			val number = chJson.optDouble("number", 0.0).toFloat()
			val volume = chJson.optInt("volume", 0).coerceAtLeast(0)
			val rawTitle = chJson.getStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
			val numText = formatChapterNumber(number)
			val title = buildChapterTitle(rawTitle, numText, number)
			val uploadDate = chJson.getStringOrNull("createdAt")?.let { dateFormat.parseSafe(it) } ?: 0L
			ChapterRecord(
				id = chapterId,
				number = number,
				volume = volume,
				title = title,
				numText = numText,
				uploadDate = uploadDate,
			)
		}.asReversed()

		val seenKeys = HashSet<Pair<Int, Float>>(raw.size)
		return raw.map { item ->
			val isDuplicateNumber = item.number > 0f && !seenKeys.add(item.volume to item.number)
			MangaChapter(
				id = generateUid(item.id),
				title = item.title,
				number = if (isDuplicateNumber) 0f else item.number,
				volume = item.volume,
				url = "/$MANGA_PATH/$slug/${item.id}/${item.numText}",
				scanlator = null,
				uploadDate = item.uploadDate,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseManga(json: JSONObject): Manga? {
		val slug = json.getStringOrNull("slug")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val title = json.getStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val url = "/$MANGA_PATH/$slug"
		val cover = json.getStringOrNull("coverUrl")?.toAbsoluteUrl(domain)
		val score = json.optDouble("score", Double.NaN)
		val rating = if (score.isFinite() && score > 0.0) {
			(score / 5.0).toFloat().coerceIn(0f, 1f)
		} else {
			RATING_UNKNOWN
		}
		val tags = json.optJSONArray("genres")?.let { arr ->
			val set = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val name = arr.optString(i).trim()
				if (name.isNotEmpty()) {
					set.add(MangaTag(key = name, title = name, source = source))
				}
			}
			set
		} ?: emptySet()

		val authors = LinkedHashSet<String>()
		json.optJSONArray("authors")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(authors::add)
			}
		}
		json.optJSONArray("artists")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(authors::add)
			}
		}

		val altTitles = parseAltTitles(json.opt("alternativeTitles"))
		val state = when (json.getStringOrNull("status")?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled", "canceled" -> MangaState.ABANDONED
			else -> null
		}
		val contentRating = when (json.getStringOrNull("contentRating")?.lowercase(Locale.ROOT)) {
			"pornographic", "erotica" -> ContentRating.ADULT
			"suggestive" -> ContentRating.SUGGESTIVE
			"safe" -> ContentRating.SAFE
			else -> ContentRating.ADULT
		}

		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = altTitles,
			url = url,
			publicUrl = "https://$domain$url",
			rating = rating,
			contentRating = contentRating,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = state,
			authors = authors,
			description = json.getStringOrNull("synopsis"),
			source = source,
		)
	}

	private fun parseAltTitles(raw: Any?): Set<String> {
		val array = when (raw) {
			is JSONArray -> raw
			is String -> {
				val trimmed = raw.trim()
				if (trimmed.startsWith("[")) {
					runCatching { JSONArray(trimmed) }.getOrNull()
				} else if (trimmed.isNotEmpty() && trimmed != "null") {
					return setOf(trimmed)
				} else {
					null
				}
			}

			else -> null
		} ?: return emptySet()
		val result = LinkedHashSet<String>(array.length())
		for (i in 0 until array.length()) {
			array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(result::add)
		}
		return result
	}

	private fun buildChapterTitle(rawTitle: String?, numText: String, number: Float): String {
		val baseLabel = "Capítulo $numText"
		if (rawTitle.isNullOrEmpty()) {
			return baseLabel
		}
		val containsSameNumber = NUMBER_REGEX.findAll(rawTitle).any { match ->
			match.value.toFloatOrNull() == number
		}
		return if (containsSameNumber) rawTitle else "$baseLabel - $rawTitle"
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private fun extractSlug(rawUrl: String): String {
		val cleaned = rawUrl.substringBefore('#').substringBefore('?').trim('/')
		val segments = cleaned.split('/')
		val mangaIdx = segments.indexOf(MANGA_PATH)
		if (mangaIdx >= 0 && mangaIdx + 1 < segments.size) {
			return segments[mangaIdx + 1]
		}
		return segments.lastOrNull().orEmpty()
	}

	private fun trpcUrl(procedure: String, input: JSONObject): HttpUrl {
		return "https://$domain/api/trpc/$procedure".toHttpUrl().newBuilder()
			.addQueryParameter("batch", "1")
			.addQueryParameter("input", input.toString())
			.build()
	}

	private fun extractTrpcJsonObject(array: JSONArray, index: Int): JSONObject? {
		val item = array.optJSONObject(index) ?: return null
		return item.optJSONObject("result")
			?.optJSONObject("data")
			?.optJSONObject("json")
	}

	private fun extractTrpcJsonArray(array: JSONArray, index: Int): JSONArray? {
		val item = array.optJSONObject(index) ?: return null
		return item.optJSONObject("result")
			?.optJSONObject("data")
			?.optJSONArray("json")
	}

	private data class ChapterRecord(
		val id: Long,
		val number: Float,
		val volume: Int,
		val title: String,
		val numText: String,
		val uploadDate: Long,
	)

	private object AuthRetryTag

	private companion object {
		const val MANGA_PATH = "manga"
		const val HEADER_FLUX_NODE = "x-flux-node"
		const val FLUX_NODE_VALUE = "G2ZsDdWhUwdU82Vw"
		val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")

		val GENRES = listOf(
			"Ação",
			"Adulto",
			"Ahegao",
			"Anal",
			"Aventura",
			"BDSM",
			"Biquíni",
			"Bondage",
			"Bunda Grande",
			"Campus",
			"Casada",
			"Chantagem",
			"Colegial",
			"Colorido",
			"Comédia",
			"Cosplay",
			"Creampie",
			"Defloração",
			"Drama",
			"Ecchi",
			"Escolar",
			"Esporte",
			"Exibicionismo",
			"Fantasia",
			"Femdom",
			"Futanari",
			"Grupo",
			"Harém",
			"Hipnose",
			"Histórico",
			"Incesto",
			"Isekai",
			"Lingerie",
			"Maduro",
			"Magia",
			"Masturbação",
			"Mature",
			"Meia Calça",
			"MILF",
			"Mistério",
			"Mãe",
			"Netorare",
			"Netori",
			"Oral",
			"Oyakodon",
			"Peitões",
			"Professor(a)",
			"Sci-fi",
			"School Life",
			"Seinen",
			"Slice of Life",
			"Smut",
			"Sobrenatural",
			"Traição",
			"Tsundere",
			"Vida Cotidiana",
			"Voyeur",
			"Óculos",
			"Romance",
		)
	}
}
