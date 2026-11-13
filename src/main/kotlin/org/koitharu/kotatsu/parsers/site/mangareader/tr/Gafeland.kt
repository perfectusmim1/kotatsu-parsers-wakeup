package org.koitharu.kotatsu.parsers.site.mangareader.tr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("GAFELAND", "Gafeland", "tr")
internal class Gafeland(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.GAFELAND, "gafeland.com", pageSize = 24, searchPageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("gafeland.com", "www.gafeland.com")

	override val isNetShieldProtected: Boolean = false

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet(urlBuilder().addPathSegment("seriler").addPathSegment("").build()).parseHtml()
		val tags = doc.select("select[name=genre] option[value]").mapNotNullToSet { opt ->
			val key = opt.attr("value").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			val title = opt.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}
		return MangaListFilterOptions(
			availableTags = tags,
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
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.addPathSegment("seriler")
			.addPathSegment("")
			.apply {
				if (page > 1) {
					addQueryParameter("pg", page.toString())
				}
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
					addQueryParameter("q", it)
				}
				filter.tags.oneOrThrowIfMany()?.let {
					addQueryParameter("genre", it.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "Devam Ediyor"
						MangaState.FINISHED -> "Tamamlandı"
						MangaState.PAUSED -> "Ara Verildi"
						MangaState.ABANDONED -> "Bırakıldı"
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
					}?.let { addQueryParameter("format", it) }
				}
				val orderBy = when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "title"
					SortOrder.NEWEST -> "date"
					else -> "modified"
				}
				addQueryParameter("orderby", orderBy)
			}.build()

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.ymk-card-view").mapNotNull { card ->
			val link = card.selectFirst("a.ymk-card-title-link")
				?: card.selectFirst("a.ymk-card-cover")
				?: return@mapNotNull null
			val rawUrl = link.attr("href").ifBlank { card.attr("data-series-url") }
			val relUrl = rawUrl.toSiteRelativeUrl().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			val poster = card.selectFirst("img.ymk-card-poster-img")
			val title = card.selectFirst("a.ymk-card-title-link")?.text()?.trim()?.takeIf { it.isNotEmpty() }
				?: poster?.attrOrNull("alt")?.trim()
				?: return@mapNotNull null
			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = poster?.src(),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst(".ymk-series-header h1, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
			?: manga.title
		val coverUrl = doc.select(".ymk-series-hero img, img.ymk-card-poster-img")
			.firstNotNullOfOrNull { it.src() }
			?: manga.coverUrl
		val description = doc.selectFirst(".ymk-series-synopsis-content")?.text()?.trim()?.nullIfEmpty()

		val tags = doc.select(".ymk-series-genre-pills a.ymk-series-genre-pill").mapNotNullToSet { a ->
			val key = a.attr("href").toHttpUrlOrNull()?.queryParameter("genre")?.trim()?.nullIfEmpty()
				?: return@mapNotNullToSet null
			val tagTitle = a.selectFirst("span")?.text()?.trim()?.nullIfEmpty()
				?: a.text().trim().nullIfEmpty()
				?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = tagTitle.toTitleCase(sourceLocale),
				source = source,
			)
		}

		var state: MangaState? = null
		var authors = emptySet<String>()
		for (item in doc.select(".ymk-series-quick-meta-strip .ymk-qm-item")) {
			val label = item.selectFirst(".ymk-qm-label")?.text()?.trim()?.lowercase(sourceLocale) ?: continue
			val value = item.selectFirst(".ymk-qm-value")?.text()?.trim().orEmpty()
			when {
				label.contains("durum") -> state = parseState(value)
				label.contains("yazar") || label.contains("çizer") -> {
					if (value.isNotEmpty() && value != "—" && value != "-" && !value.equals("bilinmiyor", ignoreCase = true)) {
						authors = value.split(',', '/').mapNotNullToSet { it.trim().nullIfEmpty() }
					}
				}
			}
		}

		val chapterDateFormat = SimpleDateFormat("dd.MM.yyyy", sourceLocale)
		val chapterElements = doc.select(".ymk-chapter-list a.ymk-chapter-item").asReversed()
		val chapters = chapterElements.mapChapters { index, a ->
			val relUrl = a.attr("href").toSiteRelativeUrl()
			val numLabel = a.selectFirst(".ymk-ch-num-label")?.text()?.trim().orEmpty()
			val subTitle = a.select("div:first-child > span:not(.ymk-ch-num-label):not(.ymk-ch-read-pill)")
				.text()
				.trim()
			val chapterTitle = buildString {
				if (numLabel.isNotEmpty()) append(numLabel)
				if (subTitle.isNotEmpty()) {
					if (isNotEmpty()) append(' ')
					append(subTitle)
				}
			}.ifEmpty {
				a.attrOrNull("data-ch-title")?.trim()
			}
			val number = a.attrOrNull("data-ch-num")?.toFloatOrNull()
				?: Regex("""(\d+(?:\.\d+)?)""").find(numLabel)?.value?.toFloatOrNull()
				?: (index + 1f)
			val dateText = a.selectFirst("div:last-child > span")?.text()?.trim()
			MangaChapter(
				id = generateUid(relUrl),
				title = chapterTitle,
				number = number,
				volume = 0,
				url = relUrl,
				scanlator = null,
				uploadDate = parseChapterDate(chapterDateFormat, dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			tags = tags,
			authors = authors,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img.ymk-chapter-img, .ymk-reader img").mapIndexed { index, img ->
			val url = img.requireSrc().toSiteRelativeUrl()
			MangaPage(
				id = generateUid(url.ifEmpty { "${chapter.url}:$index" }),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private fun String.toSiteRelativeUrl(): String {
		return toRelativeUrl(domain)
			.toRelativeUrl("gafeland.com")
			.toRelativeUrl("www.gafeland.com")
	}

	private fun parseState(text: String): MangaState? = when (text.lowercase(sourceLocale)) {
		"devam ediyor", "güncel", "yeni başladı" -> MangaState.ONGOING
		"tamamlandı" -> MangaState.FINISHED
		"ara verildi", "sezon finali" -> MangaState.PAUSED
		"bırakıldı" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseChapterDate(dateFormat: SimpleDateFormat, date: String?): Long {
		val d = date?.trim()?.lowercase(sourceLocale) ?: return 0L
		if (d.isEmpty()) return 0L
		if (!d.endsWith(" önce")) {
			return dateFormat.parseSafe(date)
		}
		val number = Regex("""(\d+)""").find(d)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("saniye").anyWordIn(d) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("dakika").anyWordIn(d) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("saat").anyWordIn(d) -> cal.apply { add(Calendar.HOUR_OF_DAY, -number) }.timeInMillis
			WordSet("gün").anyWordIn(d) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("hafta").anyWordIn(d) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("ay").anyWordIn(d) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("yıl").anyWordIn(d) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}
}
