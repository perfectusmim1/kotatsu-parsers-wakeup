package org.koitharu.kotatsu.parsers.site.keyoapp.en

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.keyoapp.KeyoappParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.containsNormalizedQuery
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.host
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.util.Locale

@MangaSourceParser("SURYASCANS", "GenzToon", "en")
internal class SuryaScans(context: MangaLoaderContext) :
	KeyoappParser(context, MangaParserSource.SURYASCANS, "genztoons.org") {

	override val configKeyDomain = ConfigKey.Domain("genztoons.org", "genzupdates.com")

	override val selectDesc = "div.container > p, div.grid > div.overflow-hidden > p"
	override val selectTag = "a[href*='/series/?genre='], div.grid:has(>h1) > div > a"

	private val tagsMutex = Mutex()
	private var cachedTags: Set<MangaTag>? = null

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchSeriesTags(),
	)

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query.orEmpty()
		val tag = filter.tags.firstOrNull()?.title.orEmpty()
		val useSeriesPage = query.isNotEmpty() || tag.isNotEmpty() || order != SortOrder.UPDATED
		val url = urlBuilder().apply {
			addPathSegment(if (useSeriesPage) "series" else "latest")
		}.build()
		return parseMangaList(webClient.httpGet(url).parseHtml(), tag, query)
	}

	private suspend fun fetchSeriesTags(): Set<MangaTag> = tagsMutex.withLock {
		cachedTags?.let { return@withLock it }
		val url = urlBuilder().addPathSegment("series").addPathSegment("").build()
		val doc = webClient.httpGet(url).parseHtml()
		val byKey = LinkedHashMap<String, MangaTag>()
		for (btn in doc.select("#searched_series_page button[tags]")) {
			val rawArray = runCatching { JSONArray(btn.attr("tags")) }.getOrNull() ?: continue
			for (i in 0 until rawArray.length()) {
				val raw = rawArray.optString(i).trim()
				if (raw.isEmpty() || raw.none { it in 'a'..'z' || it in 'A'..'Z' }) continue
				val normalized = raw.lowercase(Locale.ROOT).toTitleCase(sourceLocale)
				val first = normalized.firstOrNull() ?: continue
				if (first.isLowerCase()) continue
				val key = normalized.lowercase(Locale.ROOT)
				byKey.putIfAbsent(
					key,
					MangaTag(
						title = normalized,
						key = key,
						source = source,
					),
				)
			}
		}
		val tags = byKey.values.toSet()
		if (tags.isNotEmpty()) {
			cachedTags = tags
		}
		tags
	}

	override fun parseMangaList(doc: Document, tag: String, query: String): List<Manga> {
		val items = doc.select("#searched_series_page button").ifEmpty {
			doc.select("div.grid > div.group")
		}
		return items.mapNotNull { div ->
			val anchor = div.selectFirst("a") ?: return@mapNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = div.selectFirst("h3")?.text()?.trim()?.nullIfEmpty()
				?: anchor.attrOrNull("title")?.trim()?.nullIfEmpty()
				?: div.attrOrNull("alt")?.trim()?.nullIfEmpty()
				?: div.selectFirst("div.font-bold")?.text()?.trim()?.nullIfEmpty()
				?: return@mapNotNull null
			if (query.isNotEmpty() && !title.containsNormalizedQuery(query)) {
				return@mapNotNull null
			}
			val itemTagNames = extractCardTagNames(div)
			if (tag.isNotEmpty() && itemTagNames.none { it.equals(tag, ignoreCase = true) }) {
				return@mapNotNull null
			}
			val coverUrl = cover(div)
			val mangaTags = itemTagNames.mapNotNullTo(LinkedHashSet()) { raw ->
				val normalized = raw.lowercase(Locale.ROOT).toTitleCase(sourceLocale)
				normalized.takeIf { it.isNotEmpty() }?.let {
					MangaTag(
						title = it,
						key = it.lowercase(Locale.ROOT),
						source = source,
					)
				}
			}
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = coverUrl,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = mangaTags,
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailed = super.getDetails(manga)
		val cleanedTags = detailed.tags.mapNotNullTo(LinkedHashSet()) { t ->
			val cleanTitle = t.title.trim().trimEnd(',', ' ').lowercase(Locale.ROOT).toTitleCase(sourceLocale)
			cleanTitle.nullIfEmpty()?.let {
				MangaTag(
					title = it,
					key = it.lowercase(Locale.ROOT),
					source = source,
				)
			}
		}
		return detailed.copy(
			tags = cleanedTags.ifEmpty { manga.tags },
		)
	}

	private fun extractCardTagNames(div: Element): List<String> {
		val rawTags = div.attr("tags").trim()
		if (rawTags.startsWith('[')) {
			val arr = runCatching { JSONArray(rawTags) }.getOrNull()
			if (arr != null) {
				return List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
			}
		}
		return div.select("div.gap-1 a").map { it.text().trim() }.filter { it.isNotEmpty() }
	}
}
