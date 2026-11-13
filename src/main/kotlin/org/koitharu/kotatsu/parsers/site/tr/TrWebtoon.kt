package org.koitharu.kotatsu.parsers.site.tr

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRWEBTOON", "TrWebtoon", "tr")
internal class TrWebtoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TRWEBTOON, pageSize = 24) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("trmanga.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.addPathSegment("webtoon-listesi")
			.addQueryParameter("page", page.toString())
			.apply {
				filter.query?.trim()?.nullIfEmpty()?.let {
					addQueryParameter("q", it)
				}
				if (filter.tags.isNotEmpty()) {
					addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
				}
				if (filter.tagsExclude.isNotEmpty()) {
					addQueryParameter("haric", filter.tagsExclude.joinToString(",") { it.key })
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "continues"
						MangaState.FINISHED -> "complated"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty()) {
					addQueryParameter("f", "1")
				}
				val (sortKey, sortType) = when (order) {
					SortOrder.UPDATED -> "updated" to "DESC"
					SortOrder.POPULARITY -> "views" to "DESC"
					SortOrder.RATING -> "rating" to "DESC"
					SortOrder.NEWEST -> "released" to "DESC"
					SortOrder.NEWEST_ASC -> "released" to "ASC"
					SortOrder.ALPHABETICAL -> "name" to "ASC"
					SortOrder.ALPHABETICAL_DESC -> "name" to "DESC"
					else -> "views" to "DESC"
				}
				addQueryParameter("sort", sortKey)
				addQueryParameter("short_type", sortType)
			}
			.build()

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("main div.grid a.group[href^='/webtoon/']").map { a ->
			val href = a.attrAsRelativeUrl("href")
			val ratingValue = a.select("span")
				.lastOrNull { it.selectFirst("use[href$=#star]") != null }
				?.text()
				?.trim()
				?.toFloatOrNull()
				?.div(5f)
				?: RATING_UNKNOWN
			val isAdult = a.select("span.badge").any { it.text().contains("16+") }
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = a.selectFirst("img")?.src(),
				title = a.attrOrNull("title")
					?: a.selectFirst("span.line-clamp-2")?.textOrNull()
					?: a.parseFailed("Title not found"),
				altTitles = emptySet(),
				rating = ratingValue,
				tags = emptySet(),
				authors = emptySet(),
				state = parseState(a.selectFirst("span[data-cover-ov]")?.text()),
				source = source,
				contentRating = if (isAdult) ContentRating.ADULT else sourceContentRating,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/webtoon-listesi").parseHtml()
		return doc.requireElementById("wlGenres").select("button[data-slug]").mapNotNullToSet { btn ->
			val key = btn.attrOrNull("data-slug") ?: return@mapNotNullToSet null
			val title = btn.attrOrNull("data-name") ?: btn.textOrNull() ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		var altTitles = manga.altTitles
		var authors = manga.authors
		var state = manga.state
		for ((dt, dd) in doc.select("dl dt").zip(doc.select("dl dd"))) {
			when (dt.text().trim()) {
				"Diğer isimler" -> {
					altTitles = dd.text().split(',')
						.mapNotNullToSet { it.trim().nullIfEmpty() }
				}

				"Yazar" -> {
					val linked = dd.select("a").mapNotNullToSet { it.textOrNull() }
					authors = linked.ifEmpty {
						dd.text().split(',').mapNotNullToSet { it.trim().nullIfEmpty() }
					}
				}

				"Durum" -> {
					state = parseState(dd.text()) ?: state
				}
			}
		}

		val tags = doc.select("section.wt-hero a[href^='/tur/']").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			val title = a.textOrNull() ?: return@mapNotNullToSet null
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val dotDateFormat = SimpleDateFormat("dd.MM.yyyy", sourceLocale)
		val slashDateFormat = SimpleDateFormat("dd/MM/yyyy", sourceLocale)

		return manga.copy(
			title = doc.selectFirst("h1#wt-title")?.textOrNull() ?: manga.title,
			coverUrl = doc.selectFirst("img.wt-cover")?.src() ?: manga.coverUrl,
			altTitles = altTitles,
			authors = authors,
			tags = tags,
			description = doc.selectFirst("#wtiDesc")?.html(),
			state = state,
			chapters = doc.select("ol#chapters > li.wt-ch").mapChapters(reversed = true) { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val url = a.attrAsRelativeUrl("href")
				val number = li.attrOrNull("data-chapter")?.toFloatOrNull() ?: (i + 1f)
				val chTitle = a.selectFirst(".wt-ch-t")?.textOrNull()
					?.takeUnless { it.equals("Bölüm", ignoreCase = true) }
				val metaEl = li.selectFirst(".wt-ch-m")
				val scanlator = metaEl?.selectFirst("a.fs-chip")?.ownText()?.trim()?.nullIfEmpty()
				val dateText = metaEl?.ownText()?.trim()
				MangaChapter(
					id = generateUid(url),
					title = chTitle,
					number = number,
					volume = 0,
					url = url,
					scanlator = scanlator,
					uploadDate = parseChapterDate(dotDateFormat, slashDateFormat, dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	private fun parseState(text: String?): MangaState? = when (text?.trim()) {
		"Devam Ediyor", "Güncel" -> MangaState.ONGOING
		"Tamamlandı" -> MangaState.FINISHED
		"Bırakıldı" -> MangaState.ABANDONED
		"Durduruldu", "Ara Verildi" -> MangaState.PAUSED
		else -> null
	}

	private fun parseChapterDate(dotFormat: DateFormat, slashFormat: DateFormat, date: String?): Long {
		val d = date?.trim()?.lowercase(sourceLocale) ?: return 0L
		if (d.isEmpty()) return 0L
		return when {
			d.startsWith("saat ") -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			d.endsWith(" önce") -> parseRelativeDate(d)

			else -> dotFormat.parseSafe(date).takeIf { it != 0L } ?: slashFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("saniye").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("dakika").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("saat").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("gün").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("hafta").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("ay").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("yıl").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val imgs = doc.getElementById("images")?.select("img.reader-img, img").orEmpty()
		if (imgs.isEmpty()) {
			if (doc.html().contains("\"locked\":true")) {
				throw AuthRequiredException(source)
			}
			doc.parseFailed("Images not found")
		}
		return imgs.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
