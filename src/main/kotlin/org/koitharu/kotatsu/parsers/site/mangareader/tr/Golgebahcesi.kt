package org.koitharu.kotatsu.parsers.site.mangareader.tr

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("GOLGEBAHCESI", "GolgeBahcesi", "tr")
internal class Golgebahcesi(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.GOLGEBAHCESI, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("golgebahcesi.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	@Volatile
	private var cachedTags: Set<MangaTag>? = null

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
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
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrlBuilder()
			.addPathSegment("series")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					else -> "latest"
				},
			)
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
					addQueryParameter("search", it)
				}
				filter.tags.oneOrThrowIfMany()?.let {
					addQueryParameter("genre", it.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.PAUSED -> "HIATUS"
						MangaState.ABANDONED -> "DROPPED"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					when (type) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						else -> null
					}?.let { addQueryParameter("type", it) }
				}
			}.build()

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return data.mapJSONNotNull { item ->
			val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val title = item.getStringOrNull("title") ?: return@mapJSONNotNull null
			val relUrl = "/manga/$slug"
			val altTitles = item.optJSONArray("alternativeTitles")
				?.toStringSet()
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				?: emptySet()
			val rating = item.getDoubleOrDefault("rating", -1.0).let {
				if (it in 0.0..10.0) (it / 10.0).toFloat() else RATING_UNKNOWN
			}
			val authors = listOfNotNull(
				item.getStringOrNull("author"),
				item.getStringOrNull("artist"),
			).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }

			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = item.getStringOrNull("coverImage"),
				title = title,
				altTitles = altTitles,
				rating = rating,
				tags = emptySet(),
				authors = authors,
				state = parseState(item.getStringOrNull("status")),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val seriesUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(slug).build()
		val chaptersUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(slug).addPathSegment("chapters").build()

		val detail = webClient.httpGet(seriesUrl).parseJson()
		val chaptersJson = webClient.httpGet(chaptersUrl).parseJsonArray()

		val knownTags = runCatching { fetchAvailableTags() }.getOrDefault(emptySet())
		val tagsByTitle = knownTags.associateBy { it.title.lowercase(sourceLocale) }
		val tags = detail.optJSONArray("genres")?.toStringSet()?.mapNotNullToSet { name ->
			val clean = name.trim().nullIfEmpty() ?: return@mapNotNullToSet null
			tagsByTitle[clean.lowercase(sourceLocale)] ?: MangaTag(
				key = slugify(clean),
				title = clean.toTitleCase(sourceLocale),
				source = source,
			)
		} ?: emptySet()

		val altTitles = detail.optJSONArray("alternativeTitles")
			?.toStringSet()
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			?: manga.altTitles
		val authors = listOfNotNull(
			detail.getStringOrNull("author"),
			detail.getStringOrNull("artist"),
		).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }
		val rating = detail.getDoubleOrDefault("rating", -1.0).let {
			if (it in 0.0..10.0) (it / 10.0).toFloat() else manga.rating
		}

		val chapters = ArrayList<MangaChapter>(chaptersJson.length())
		for (i in chaptersJson.length() - 1 downTo 0) {
			val ch = chaptersJson.optJSONObject(i) ?: continue
			val chSlug = ch.getStringOrNull("slug") ?: continue
			val chUrl = "/manga/$slug/bolum/$chSlug"
			val number = ch.getFloatOrDefault("number", chapters.size + 1f)
			val chTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val dateStr = ch.getStringOrNull("releaseDate") ?: ch.getStringOrNull("createdAt")
			chapters.add(
				MangaChapter(
					id = generateUid(chUrl),
					title = chTitle,
					number = number,
					volume = 0,
					url = chUrl,
					scanlator = null,
					uploadDate = parseIsoDate(dateStr),
					branch = null,
					source = source,
				),
			)
		}

		return manga.copy(
			title = detail.getStringOrNull("title") ?: manga.title,
			altTitles = altTitles,
			coverUrl = detail.getStringOrNull("coverImage") ?: manga.coverUrl,
			description = detail.getStringOrNull("description")?.trim()?.nullIfEmpty(),
			tags = tags,
			authors = authors,
			state = parseState(detail.getStringOrNull("status")) ?: manga.state,
			rating = rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.trim('/').split('/')
		val seriesSlug = segments.getOrNull(1)
			?: throw ParseException("Invalid chapter path", chapter.url)
		val chapterSlug = segments.getOrNull(3)
			?: throw ParseException("Invalid chapter path", chapter.url)

		val url = apiUrlBuilder()
			.addPathSegment("chapters")
			.addPathSegment(seriesSlug)
			.addPathSegment(chapterSlug)
			.build()
		val json = webClient.httpGet(url).parseJson()
		val rawPages = json.optJSONArray("pages")
		val pages = rawPages?.mapJSONNotNull { page ->
			val imgUrl = page.getStringOrNull("url")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		} ?: emptyList()

		if (pages.isEmpty()) {
			if (json.getBooleanOrDefault("isLocked", false)) {
				throw AuthRequiredException(source)
			}
			throw ParseException("Encrypted chapter delivery is not supported", chapter.url)
		}
		return pages
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		val url = apiUrlBuilder().addPathSegment("genres").build()
		val arr = webClient.httpGet(url).parseJsonArray()
		val tags = arr.mapJSONNotNullToSet { item ->
			val slug = item.getStringOrNull("slug")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			val name = item.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}
		if (tags.isNotEmpty()) {
			cachedTags = tags
		}
		return tags
	}

	private fun apiUrlBuilder(): HttpUrl.Builder = HttpUrl.Builder()
		.scheme("https")
		.host("api.$domain")
		.addPathSegment("api")

	private fun parseState(status: String?): MangaState? = when (status?.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseIsoDate(dateStr: String?): Long {
		val clean = dateStr?.substringBefore('.')?.removeSuffix("Z")?.trim()?.nullIfEmpty() ?: return 0L
		return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}.parseSafe(clean)
	}

	private fun slugify(name: String): String {
		return name.lowercase(sourceLocale)
			.replace('ç', 'c')
			.replace('ğ', 'g')
			.replace('ı', 'i')
			.replace('ö', 'o')
			.replace('ş', 's')
			.replace('ü', 'u')
			.replace(Regex("""[^a-z0-9]+"""), "-")
			.trim('-')
	}
}
