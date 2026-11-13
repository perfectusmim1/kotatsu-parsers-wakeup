package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.removeSuffix
import java.util.*

@MangaSourceParser("HENTAIROX", "HentaiRox", type = ContentType.HENTAI)
internal class HentaiRox(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.HENTAIROX, "hentairox.com") {
	override val selectGallery = "article.hr-home-card"
	override val selectGalleryLink = "a.hr-home-card__cover"
	override val selectGalleryImg = "a.hr-home-card__cover img"
	override val selectGalleryTitle = ".hr-home-card__title a"
	override val selectTags = ".gtags"
	override val selectTag = ".hr-gallery-entity-group:contains(Tags:) .hr-gallery-entity-items"
	override val selectAuthor = ".hr-gallery-entity-group:contains(Artists:) .hr-gallery-tag__name"
	override val selectLanguageChapter = ".hr-gallery-entity-group:contains(Languages:) .hr-gallery-tag__name"
	override val selectUrlChapter = "a.hr-gallery-read, .hr-gallery-cover a"
	override val selectTotalPage = "[data-total-label]"
	override val idImg = "readerImg"

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return super.getFilterOptions().copy(
			availableLocales = setOf(
				Locale.ENGLISH,
				Locale.FRENCH,
				Locale.JAPANESE,
				Locale("es"),
				Locale("ru"),
				Locale("ko"),
				Locale.GERMAN,
			),
		)
	}

	override fun Element.parseTags() = select("a.tag, a.hr-gallery-tag, .gallery_title a").mapToSet {
		val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
		val name = it.selectFirst(".item_name, .hr-gallery-tag__name")?.text() ?: it.text()
		MangaTag(
			key = key,
			title = name,
			source = source,
		)
	}
}
