package org.koitharu.kotatsu.parsers.site.mangareader.tr

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("AFRODITSCANS", "AfroditScans", "tr")
internal class AfroditScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.AFRODITSCANS, "afroditscans.com", pageSize = 20, searchPageSize = 20) {

	override val isNetShieldProtected = false

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val data = fetchRouteData(urlBuilder().addPathSegment("manga").addPathSegment("__data.json").build().toString())
		val categories = data.optJSONArray("categories")?.mapJSONNotNullToSet { cat ->
			val slug = cat.getStringOrNull("value") ?: return@mapJSONNotNullToSet null
			val title = cat.getStringOrNull("label") ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}.orEmpty()
		return MangaListFilterOptions(
			availableTags = categories,
			availableStates = EnumSet.of(MangaState.FINISHED, MangaState.PAUSED, MangaState.ONGOING),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.addPathSegment("manga")
			.addPathSegment("__data.json")
			.addQueryParameter("page", page.toString())
			.apply {
				filter.query?.trim()?.nullIfEmpty()?.let {
					addQueryParameter("search", it)
				}
				filter.tags.oneOrThrowIfMany()?.let {
					addQueryParameter("category", it.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.FINISHED -> "1"
						MangaState.PAUSED -> "2"
						MangaState.ONGOING -> "3"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					when (type) {
						ContentType.MANGA -> "1"
						ContentType.MANHWA -> "2"
						ContentType.MANHUA -> "3"
						ContentType.OTHER -> "9"
						else -> null
					}?.let { addQueryParameter("country", it) }
				}
				val sortKey = when (order) {
					SortOrder.NEWEST -> "new"
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "name"
					SortOrder.UPDATED -> "update"
					else -> "new"
				}
				addQueryParameter("sort", sortKey)
			}
			.build()

		val data = fetchRouteData(url.toString())
		val series = data.optJSONArray("series") ?: return emptyList()
		return series.mapJSON { item -> parseSeriesCard(item) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slugUrl = manga.url.toAbsoluteUrl(domain).removeSuffix('/')
		val data = fetchRouteData("$slugUrl/__data.json")
		val series = data.optJSONObject("series") ?: throw ParseException("Series payload not found", slugUrl)

		val tags = series.optJSONArray("resolvedCategories")?.mapJSONNotNullToSet { cat ->
			val slug = cat.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
			val title = cat.getStringOrNull("title") ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		} ?: manga.tags

		val isAdult = tags.any { it.key.equals("yetiskin", ignoreCase = true) || it.key.contains("18") }
		val slug = series.getStringOrNull("slug") ?: manga.url.removeSuffix('/').substringAfterLast('/')
		val episodes = series.optJSONArray("SeriesEpisode")
			?.asTypedList<JSONObject>()
			?.sortedBy { it.optDouble("order", 0.0) }
			.orEmpty()

		val chapters = episodes.mapChapters { i, ep ->
			val epSlug = ep.getStringOrNull("slug") ?: return@mapChapters null
			val href = "/manga/$slug/$epSlug"
			val number = ep.optDouble("order", (i + 1).toDouble()).toFloat()
			MangaChapter(
				id = generateUid(href),
				title = ep.getStringOrNull("name")?.nullIfEmpty(),
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseIsoDate(ep.getStringOrNull("createdDate")),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = series.getStringOrNull("name") ?: manga.title,
			coverUrl = buildImageUrl(series.getStringOrNull("image")) ?: manga.coverUrl,
			rating = parseRating(series.optDouble("point", -1.0)),
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			tags = tags,
			state = parseState(series.optInt("status", -1)) ?: manga.state,
			description = series.getStringOrNull("description"),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain).removeSuffix('/')
		val data = fetchRouteData("$chapterUrl/__data.json")
		val images = data.optJSONObject("episode")?.optJSONArray("images")
		if (images != null && images.length() > 0) {
			return (0 until images.length()).mapNotNull { idx ->
				val path = images.optString(idx).nullIfEmpty() ?: return@mapNotNull null
				val url = buildImageUrl(path) ?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		return doc.select("img[src*=/_manga/]").map { img ->
			val url = img.requireSrc()
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}.ifEmpty {
			doc.parseFailed("Images not found")
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val slugUrl = seed.url.toAbsoluteUrl(domain).removeSuffix('/')
		val data = fetchRouteData("$slugUrl/__data.json")
		return data.optJSONArray("recommended")?.mapJSON { item -> parseSeriesCard(item) }.orEmpty()
	}

	private fun parseSeriesCard(item: JSONObject): Manga {
		val slug = item.getString("slug")
		val href = "/manga/$slug"
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = buildImageUrl(item.getStringOrNull("image")),
			title = item.getString("name"),
			altTitles = emptySet(),
			rating = parseRating(item.optDouble("point", -1.0)),
			tags = emptySet(),
			authors = emptySet(),
			state = parseState(item.optInt("status", -1)),
			source = source,
			contentRating = sourceContentRating,
		)
	}

	private fun parseState(status: Int): MangaState? = when (status) {
		1 -> MangaState.FINISHED
		2 -> MangaState.PAUSED
		3 -> MangaState.ONGOING
		else -> null
	}

	private fun parseRating(point: Double): Float {
		if (point.isNaN() || point <= 0.0) return RATING_UNKNOWN
		return (point.toFloat() / 10f).coerceIn(0f, 1f)
	}

	private fun buildImageUrl(path: String?): String? {
		val clean = path?.trim()?.nullIfEmpty() ?: return null
		return when {
			clean.startsWith("http://") || clean.startsWith("https://") -> clean
			clean.startsWith("/_manga/") -> "$CDN_URL$clean"
			else -> "https://$domain/upload${if (clean.startsWith('/')) clean else "/$clean"}"
		}
	}

	private suspend fun fetchRouteData(url: String): JSONObject {
		val root = webClient.httpGet(url).parseJson()
		val nodes = root.optJSONArray("nodes") ?: throw ParseException("Missing nodes", url)
		var dataArray: JSONArray? = null
		for (i in nodes.length() - 1 downTo 0) {
			val node = nodes.optJSONObject(i) ?: continue
			if (node.optString("type") == "data") {
				dataArray = node.optJSONArray("data")
				if (dataArray != null && dataArray.length() > 0) break
			}
		}
		val arr = dataArray ?: throw ParseException("Missing route data node", url)
		return deref(arr, 0) as? JSONObject ?: throw ParseException("Invalid root payload", url)
	}

	private fun deref(arr: JSONArray, ref: Any?, depth: Int = 0): Any? {
		if (depth > 16 || ref == null || ref === JSONObject.NULL) return null
		if (ref !is Number) return ref
		val idx = ref.toInt()
		if (idx < 0 || idx >= arr.length()) return null
		return when (val target = arr.get(idx)) {
			JSONObject.NULL -> null
			is JSONObject -> JSONObject().apply {
				val keys = target.keys()
				while (keys.hasNext()) {
					val k = keys.next()
					put(k, deref(arr, target.get(k), depth + 1) ?: JSONObject.NULL)
				}
			}

			is JSONArray -> {
				if (target.length() >= 2 && target.opt(0) == "Date" && target.opt(1) is String) {
					target.optString(1)
				} else if (target.length() > 0 && target.opt(0) is String) {
					target
				} else {
					JSONArray().apply {
						for (i in 0 until target.length()) {
							put(deref(arr, target.get(i), depth + 1) ?: JSONObject.NULL)
						}
					}
				}
			}

			else -> target
		}
	}

	private fun parseIsoDate(date: String?): Long {
		val clean = date?.trim()?.nullIfEmpty() ?: return 0L
		val normalized = clean.substringBefore('.').removeSuffix("Z")
		return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}.parseSafe(normalized)
	}

	private companion object {
		const val CDN_URL = "https://cdn-a.efsaneler2.can.re"
	}
}
