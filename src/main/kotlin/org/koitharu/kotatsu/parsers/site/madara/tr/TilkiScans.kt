package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("TILKISCANS", "TilkiScans", "tr")
internal class TilkiScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TILKISCANS, "www.tilkiscans.com", pageSize = 18) {
	override val datePattern = "dd/MM/yyyy"
	override val tagPrefix = "seri-turleri/"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val links = doc.select("nav#mgcr-modern-header-categories a[href*=$tagPrefix]")
		if (links.isNotEmpty()) {
			val tags = links.mapNotNullToSet { a ->
				val key = a.attr("href").removeSuffix("/").substringAfterLast(tagPrefix, "")
				val title = a.textOrNull()?.toTitleCase(sourceLocale)
				if (key.isEmpty() || title == null) {
					null
				} else {
					MangaTag(key = key, title = title, source = source)
				}
			}
			if (tags.isNotEmpty()) {
				return tags
			}
		}
		val checkboxes = doc.select("input[name='mgcr_genre[]']")
		if (checkboxes.isNotEmpty()) {
			val tags = checkboxes.mapNotNullToSet { input ->
				val key = input.attr("value").trim()
				val title = input.parent()?.textOrNull()?.toTitleCase(sourceLocale)
					?: input.id().nullIfEmpty()?.let { id ->
						doc.selectFirst("label[for=$id]")?.textOrNull()?.toTitleCase(sourceLocale)
					}
				if (key.isEmpty() || title == null) {
					null
				} else {
					MangaTag(key = key, title = title, source = source)
				}
			}
			if (tags.isNotEmpty()) {
				return tags
			}
		}
		return super.fetchAvailableTags()
	}
}
