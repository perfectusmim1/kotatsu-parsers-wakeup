package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MIKOROKU", "Mikoroku", "id", ContentType.HENTAI)
internal class Mikoroku(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MIKOROKU, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("mikoroku.com", "www.mikoroku.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val items = getCatalog()
		val tags = items.flatMapTo(LinkedHashSet()) { it.tags }
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		)
	}

	private val catalogMutex = Mutex()
	private var cachedCatalog: List<MikorokuEntry>? = null

	private data class MikorokuEntry(
		val manga: Manga,
		val tags: Set<MangaTag>,
		val state: MangaState?,
		val authors: Set<String>,
		val description: String?,
		val rawGenres: Set<String>,
	)

	private suspend fun getCatalog(): List<MikorokuEntry> = catalogMutex.withLock {
		cachedCatalog?.let { return@withLock it }
		val arr = webClient.httpGet(MANGA_JSON_URL).parseJsonArray()
		val list = ArrayList<MikorokuEntry>(arr.length())
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			val slug = obj.getStringOrNull("slug")?.nullIfEmpty() ?: continue
			val title = obj.getStringOrNull("title")?.nullIfEmpty() ?: continue
			val altTitles = obj.getStringOrNull("altTitle")
				?.split(';')
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				?: emptySet()
			val rawImg = obj.getStringOrNull("img")?.trim().orEmpty().trimEnd('.')
			val coverUrl = when {
				rawImg.isEmpty() || rawImg == "-" -> null
				rawImg.startsWith("http://") || rawImg.startsWith("https://") -> rawImg
				else -> GITHUB_RAW + rawImg.removePrefix("/")
			}
			val genres = parseGenres(obj.opt("genres"))
			val tags = genres.mapToSet { g ->
				MangaTag(
					key = g.lowercase(Locale.ROOT),
					title = g.toTitleCase(sourceLocale),
					source = source,
				)
			}
			val state = when (obj.getStringOrNull("status")?.trim()?.lowercase(Locale.ROOT)) {
				"ongoing", "berjalan" -> MangaState.ONGOING
				"completed", "tamat", "finished" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"dropped", "cancelled" -> MangaState.ABANDONED
				else -> null
			}
			val authors = buildSet {
				obj.getStringOrNull("author")?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let(::add)
				obj.getStringOrNull("artist")?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let(::add)
			}
			val rating = obj.getStringOrNull("rating")?.toFloatOrNull()?.div(10f)?.coerceIn(0f, 1f)
				?: obj.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }?.toFloat()?.div(10f)?.coerceIn(0f, 1f)
				?: RATING_UNKNOWN
			val url = "/detail.html?slug=$slug"
			val manga = Manga(
				id = generateUid(slug),
				url = url,
				publicUrl = "https://$domain$url",
				coverUrl = coverUrl,
				title = title,
				altTitles = altTitles,
				rating = rating,
				tags = tags,
				authors = authors,
				state = state,
				source = source,
				contentRating = ContentRating.ADULT,
			)
			list.add(
				MikorokuEntry(
					manga = manga,
					tags = tags,
					state = state,
					authors = authors,
					description = obj.getStringOrNull("desc")?.trim(),
					rawGenres = genres.mapToSet { it.lowercase(Locale.ROOT) },
				),
			)
		}
		cachedCatalog = list
		return@withLock list
	}

	private fun parseGenres(raw: Any?): List<String> = when (raw) {
		is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).trim().nullIfEmpty() }
		is String -> raw.split(',', ';').mapNotNull { it.trim().nullIfEmpty() }
		else -> emptyList()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		var items = getCatalog().asSequence()
		val query = filter.query?.trim()?.lowercase(Locale.ROOT)
		if (!query.isNullOrEmpty()) {
			items = items.filter { entry ->
				entry.manga.title.lowercase(Locale.ROOT).contains(query) ||
					entry.manga.altTitles.any { it.lowercase(Locale.ROOT).contains(query) }
			}
		}
		if (filter.tags.isNotEmpty()) {
			val required = filter.tags.map { it.key.lowercase(Locale.ROOT) }
			items = items.filter { entry -> required.all { it in entry.rawGenres } }
		}
		if (filter.states.isNotEmpty()) {
			items = items.filter { entry -> entry.state in filter.states }
		}
		val sorted = when (order) {
			SortOrder.ALPHABETICAL -> items.sortedBy { it.manga.title.lowercase(Locale.ROOT) }
			SortOrder.ALPHABETICAL_DESC -> items.sortedByDescending { it.manga.title.lowercase(Locale.ROOT) }
			SortOrder.RATING -> items.sortedByDescending { it.manga.rating }
			else -> items
		}.toList()

		val fromIndex = (page - 1) * pageSize
		if (fromIndex >= sorted.size) return emptyList()
		val toIndex = minOf(fromIndex + pageSize, sorted.size)
		return sorted.subList(fromIndex, toIndex).map { it.manga }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("slug=").substringBefore("&")
		val entry = getCatalog().firstOrNull {
			it.manga.url.substringAfter("slug=").substringBefore("&") == slug
		}
		val base = entry?.manga ?: manga
		val feedUrl = "https://www.mikodrive.my.id/feeds/posts/default?alt=json&max-results=500&q=${base.title.urlEncoded()}"
		val json = runCatchingCancellable { webClient.httpGet(feedUrl).parseJson() }.getOrNull()
		val entries = json?.optJSONObject("feed")?.optJSONArray("entry")?.asTypedList<JSONObject>()?.reversed().orEmpty()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		val titleLower = base.title.lowercase(Locale.ROOT)
		val chapters = entries.mapIndexedNotNull { idx, j ->
			val chTitle = j.optJSONObject("title")?.getStringOrNull("\$t")?.trim() ?: return@mapIndexedNotNull null
			if (!chTitle.lowercase(Locale.ROOT).contains(titleLower)) return@mapIndexedNotNull null
			val href = j.optJSONArray("link")?.asTypedList<JSONObject>()
				?.firstOrNull { it.optString("rel") == "alternate" }
				?.getStringOrNull("href")
				?: return@mapIndexedNotNull null
			val dateText = j.optJSONObject("published")?.getStringOrNull("\$t")?.substringBefore("T")
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = chTitle,
				number = idx + 1f,
				volume = 0,
				branch = null,
				uploadDate = dateFormat.parseSafe(dateText),
				scanlator = null,
				source = source,
			)
		}
		return base.copy(
			description = entry?.description ?: base.description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()
		return doc.select(".post-body img, div.check-box img, article#reader .separator img, #reader img, #readerarea img")
			.mapNotNull { img ->
				val url = img.src()?.trim()?.nullIfEmpty() ?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private companion object {
		const val GITHUB_RAW = "https://raw.githubusercontent.com/moemaomao/mymangadata/main/"
		const val MANGA_JSON_URL = "${GITHUB_RAW}all-manga.json"
	}
}
