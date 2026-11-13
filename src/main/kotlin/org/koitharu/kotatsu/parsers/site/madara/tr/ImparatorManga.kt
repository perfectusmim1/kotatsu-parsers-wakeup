package org.koitharu.kotatsu.parsers.site.madara.tr

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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("IMPARATORMANGA", "ImparatorManga", "tr")
internal class ImparatorManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.IMPARATORMANGA, 24) {

	override val configKeyDomain = ConfigKey.Domain("imparatorscans.com")

	init {
		setFirstPage(1)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isYearSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet("https://$domain/titles").parseHtml()
		val tags = doc.select("[data-tristate-group=genre] [data-tristate-chip]").mapNotNullToSet { chip ->
			val key = chip.attr("data-slug").trim()
			val title = chip.selectFirst(".filter-chip__label")?.textOrNull()?.toTitleCase(sourceLocale)
			if (key.isEmpty() || title == null) {
				null
			} else {
				MangaTag(key = key, title = title, source = source)
			}
		}
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.PAUSED,
				MangaState.FINISHED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.COMICS,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			val url = "https://$domain/search?q=${filter.query.urlEncoded()}&page=$page"
			val doc = webClient.httpGet(url).parseHtml()
			return doc.select("article.card").mapNotNull { card ->
				val a = card.selectFirst("a.card__link") ?: return@mapNotNull null
				val href = a.attrAsRelativeUrl("href")
				val title = card.selectFirst(".card__title")?.textOrNull() ?: return@mapNotNull null
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					coverUrl = card.selectFirst("img")?.src(),
					title = title,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					tags = emptySet(),
					authors = emptySet(),
					state = parseState(card.selectFirst(".card__badges")?.textOrNull()),
					source = source,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				)
			}
		}

		val url = buildString {
			append("https://")
			append(domain)
			append("/titles?page=")
			append(page)
			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					SortOrder.NEWEST -> "newest"
					SortOrder.ALPHABETICAL -> "alphabetical"
					else -> "updated"
				},
			)
			filter.tags.forEach {
				append("&include_genres[]=")
				append(it.key.urlEncoded())
			}
			filter.tagsExclude.forEach {
				append("&exclude_genres[]=")
				append(it.key.urlEncoded())
			}
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.PAUSED -> "hiatus"
						MangaState.FINISHED -> "completed"
						else -> ""
					},
				)
			}
			filter.types.forEach {
				append("&include_types[]=")
				append(
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						ContentType.COMICS -> "webtoon"
						else -> ""
					},
				)
			}
			if (filter.year != 0) {
				append("&year_min=")
				append(filter.year)
				append("&year_max=")
				append(filter.year)
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.title-card").mapNotNull { card ->
			val a = card.selectFirst("a.title-card__cover, a.title-card__head") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val title = card.selectFirst(".title-card__title")?.textOrNull() ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = card.selectFirst("img")?.src(),
				title = title,
				altTitles = emptySet(),
				rating = org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = parseState(card.selectFirst(".title-card__status")?.textOrNull()),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
		val chapters = doc.select("li.chapter-card").mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a.chapter-card__link") ?: return@mapChapters null
			val href = a.attrAsRelativeUrl("href")
			val title = li.selectFirst(".chapter-card__title")?.textOrNull()
			val dateStr = li.selectFirst("time[datetime]")?.attr("datetime")
			MangaChapter(
				id = generateUid(href),
				title = title,
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = dateFormat.parseSafe(dateStr),
				scanlator = null,
				branch = null,
				source = source,
			)
		}
		val tags = doc.select(".series-main__genres a.series-genre").mapNotNullToSet { a ->
			val slug = a.attr("href").substringAfter("genre=", "").ifEmpty {
				a.attr("href").removeSuffix("/").substringAfterLast('/')
			}
			val title = a.textOrNull()?.toTitleCase(sourceLocale)
			if (slug.isEmpty() || title == null) null else MangaTag(key = slug, title = title, source = source)
		}
		val author = doc.select("dl.series-aside__stats div").firstOrNull { div ->
			div.selectFirst("dt")?.text()?.contains("Yazar", ignoreCase = true) == true
		}?.selectFirst("dd")?.textOrNull()
		val stateText = doc.selectFirst(".status-pill")?.textOrNull()

		return manga.copy(
			title = doc.selectFirst("h1.series-main__title, h1")?.textOrNull() ?: manga.title,
			description = doc.selectFirst(".series-summary")?.textOrNull(),
			tags = tags,
			authors = setOfNotNull(author),
			state = parseState(stateText) ?: manga.state,
			chapters = chapters,
			contentRating = if (isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("figure.reader-image img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseState(raw: String?): MangaState? {
		val s = raw?.lowercase(sourceLocale) ?: return null
		return when {
			"devam" in s || "ongoing" in s -> MangaState.ONGOING
			"ara" in s || "hiatus" in s -> MangaState.PAUSED
			"tamam" in s || "bitti" in s || "completed" in s -> MangaState.FINISHED
			else -> null
		}
	}
}
