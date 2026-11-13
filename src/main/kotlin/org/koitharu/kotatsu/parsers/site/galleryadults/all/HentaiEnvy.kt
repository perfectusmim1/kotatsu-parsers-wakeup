package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.ErrorMessages
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.*

@MangaSourceParser("HENTAIENVY", "HentaiEnvy", type = ContentType.HENTAI)
internal class HentaiEnvy(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.HENTAIENVY, "hentaienvy.com", pageSize = 24) {
	override val selectGalleryLink = "a"
	override val selectGalleryTitle = "div.title"
	override val selectTags = ".tags_items"
	override val selectTag = ".hnv-gallery-entity-group:contains(Tags:) .hnv-gallery-entity-items"
	override val selectAuthor = ".hnv-gallery-entity-group:contains(Artists:) .hnv-gallery-tag__name"
	override val selectLanguageChapter = ".hnv-gallery-entity-group:contains(Languages:) .hnv-gallery-tag__name"
	override val selectUrlChapter = "a.hnv-gallery-read, .hnv-gallery-cover a"
	override val selectTotalPage = "[data-total-label]"
	override val idImg = "readerImg"

	override fun Element.parseTags() = select("a.hnv-gallery-tag, a").mapToSet {
		val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
		val name = it.selectFirst(".hnv-gallery-tag__name")?.text() ?: it.html().substringBefore("<")
		MangaTag(
			key = key,
			title = name,
			source = source,
		)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableLocales = setOf(
			Locale.ENGLISH,
			Locale.FRENCH,
			Locale.JAPANESE,
			Locale.CHINESE,
			Locale("es"),
			Locale("ru"),
			Locale("ko"),
			Locale.GERMAN,
			Locale("pt"),
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search/?s_key=")
					append(filter.query.urlEncoded())
					append("&")
				}

				else -> {
					if (filter.tags.isNotEmpty()) {
						if (filter.locale != null) {
							throw IllegalArgumentException(ErrorMessages.FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED)
						}
						filter.tags.oneOrThrowIfMany()?.let {
							append("/tag/")
							append(it.key)
							if (order == SortOrder.POPULARITY) {
								append("/popular")
							}
							append("/?")
						}
					} else if (filter.locale != null) {
						append("/language/")
						append(filter.locale.toLanguagePath())
						append("/?")
					} else {
						append("/?")
					}
				}
			}
			append("page=")
			append(page)

		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}
}
