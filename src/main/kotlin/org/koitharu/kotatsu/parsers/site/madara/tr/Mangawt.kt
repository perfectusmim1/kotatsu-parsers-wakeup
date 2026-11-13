package org.koitharu.kotatsu.parsers.site.madara.tr

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.HttpStatusException
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

internal open class MangawtParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	defaultDomain: String = "mangawt.com",
) : PagedMangaParser(context, source, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain(defaultDomain)

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
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val selectedTag = filter.tags.oneOrThrowIfMany()
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }

		val url = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("mangas")
			.apply {
				// /api/mangas ignores ?genre= and /api/mangas/genre/{tag} ignores ?q=
				if (query == null && selectedTag != null) {
					addPathSegment("genre")
					addPathSegment(selectedTag.key)
				}
				addQueryParameter("page", page.toString())
				addQueryParameter("limit", pageSize.toString())
				addQueryParameter(
					"sort",
					when (order) {
						SortOrder.POPULARITY -> "popular"
						SortOrder.RATING -> "rated"
						SortOrder.NEWEST -> "newest"
						else -> "updated"
					},
				)
				if (query != null) {
					addQueryParameter("q", query)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					when (type) {
						ContentType.MANGA -> "Manga"
						ContentType.MANHWA -> "Manhwa"
						ContentType.MANHUA -> "Manhua"
						ContentType.OTHER -> "Webtoon"
						else -> null
					}?.let { addQueryParameter("type", it) }
				}
			}.build()

		val json = webClient.httpGet(url).parseJson()
		val items = json.optJSONArray("mangas") ?: return emptyList()
		return items.mapJSONNotNull { item ->
			val slug = item.getStringOrNull("slug")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			val title = item.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			val genres = item.optJSONArray("genres")?.toStringSet().orEmpty()
			if (query != null && selectedTag != null && genres.none { it.equals(selectedTag.key, ignoreCase = true) }) {
				return@mapJSONNotNull null
			}
			val relUrl = "/manga/$slug"
			val altTitles = item.optJSONArray("altTitles")
				?.toStringSet()
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				?: emptySet()
			val rating = item.getDoubleOrDefault("score", -1.0).let {
				if (it in 0.0..10.0) (it / 10.0).toFloat() else RATING_UNKNOWN
			}
			val tags = genres.mapNotNullToSet { g ->
				val clean = g.trim().nullIfEmpty() ?: return@mapNotNullToSet null
				MangaTag(
					key = clean,
					title = clean,
					source = source,
				)
			}
			val authors = listOfNotNull(
				item.getStringOrNull("author"),
				item.getStringOrNull("artist"),
			).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }

			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = item.getStringOrNull("coverUrl")?.toAbsoluteUrl(domain),
				title = title,
				altTitles = altTitles,
				rating = rating,
				tags = tags,
				authors = authors,
				state = parseState(item.getStringOrNull("status")),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val detailUrl = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("mangas")
			.addPathSegment("slug")
			.addPathSegment(slug)
			.build()
		val detail = webClient.httpGet(detailUrl).parseJson()
		val mangaId = detail.getStringOrNull("_id")?.trim()?.nullIfEmpty()
			?: throw ParseException("Missing manga id", detailUrl.toString())

		val altTitles = detail.optJSONArray("altTitles")
			?.toStringSet()
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			?: manga.altTitles
		val tags = detail.optJSONArray("genres")
			?.toStringSet()
			?.mapNotNullToSet { g ->
				val clean = g.trim().nullIfEmpty() ?: return@mapNotNullToSet null
				MangaTag(
					key = clean,
					title = clean,
					source = source,
				)
			}
			?: manga.tags
		val authors = listOfNotNull(
			detail.getStringOrNull("author"),
			detail.getStringOrNull("artist"),
		).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }
		val rating = detail.getDoubleOrDefault("score", -1.0).let {
			if (it in 0.0..10.0) (it / 10.0).toFloat() else manga.rating
		}

		val rawChapters = detail.optJSONArray("chapters")
		val chapters = rawChapters?.mapJSONNotNull { ch ->
			val rawNum = ch.opt("number")?.toString()?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			val number = ch.getFloatOrDefault("number", rawNum.toFloatOrNull() ?: 0f)
			val chUrl = "/api/mangas/$mangaId/chapters/$rawNum/pages"
			val chTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: "Bölüm $rawNum"
			val dateStr = ch.getStringOrNull("publishAt") ?: ch.getStringOrNull("createdAt")
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
			)
		}?.sortedBy { it.number }.orEmpty()

		return manga.copy(
			title = detail.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = altTitles,
			coverUrl = detail.getStringOrNull("coverUrl")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			description = detail.getStringOrNull("description")?.trim()?.nullIfEmpty(),
			tags = tags,
			authors = authors,
			state = parseState(detail.getStringOrNull("status")) ?: manga.state,
			rating = rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pagesUrl = chapter.url.toAbsoluteUrl(domain).toHttpUrl().newBuilder()
			.addQueryParameter("limit", "500")
			.build()
		val json = try {
			webClient.httpGet(pagesUrl).parseJson()
		} catch (e: HttpStatusException) {
			if (e.statusCode == 401 || e.statusCode == 403) {
				throw AuthRequiredException(source)
			}
			throw e
		}

		val rawPages = json.optJSONArray("pages")
		val sortedPages = rawPages?.mapJSONNotNull { it }
			?.sortedBy { it.getIntOrDefault("pageNumber", 0) }
			.orEmpty()
		val pages = sortedPages.mapNotNull { page ->
			val rawUrl = page.getStringOrNull("signedUrl")?.trim()?.nullIfEmpty()
				?: page.getStringOrNull("url")?.trim()?.nullIfEmpty()
				?: return@mapNotNull null
			val relUrl = rawUrl.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(relUrl),
				url = relUrl,
				preview = null,
				source = source,
			)
		}
		if (pages.isEmpty()) {
			throw AuthRequiredException(source)
		}
		return pages
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		val url = urlBuilder().addPathSegment("api").addPathSegment("settings").build()
		val genres = runCatching {
			webClient.httpGet(url).parseJson()
				.optJSONObject("settings")
				?.optJSONArray("customGenres")
				?.toStringSet()
		}.getOrNull().orEmpty().ifEmpty { DEFAULT_GENRES }

		val tags = genres.mapNotNullToSet { g ->
			val clean = g.trim().nullIfEmpty() ?: return@mapNotNullToSet null
			MangaTag(
				key = clean,
				title = clean,
				source = source,
			)
		}
		if (tags.isNotEmpty()) {
			cachedTags = tags
		}
		return tags
	}

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		else -> null
	}

	private fun parseIsoDate(dateStr: String?): Long {
		val clean = dateStr?.substringBefore('.')?.removeSuffix("Z")?.trim()?.nullIfEmpty() ?: return 0L
		return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}.parseSafe(clean)
	}

	private companion object {
		val DEFAULT_GENRES = listOf(
			"Action",
			"Adventure",
			"Comedy",
			"Drama",
			"Fantasy",
			"Harem",
			"Horror",
			"Isekai",
			"Martial Arts",
			"Romance",
			"School Life",
			"Sci-Fi",
		)
	}
}

@MangaSourceParser("MANGAWT", "MangaWt.com", "tr")
internal class Mangawt(context: MangaLoaderContext) :
	MangawtParser(context, MangaParserSource.MANGAWT)
