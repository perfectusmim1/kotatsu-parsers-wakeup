package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("MANGATILKISI", "MangaTilkisi", "tr")
internal class MangaTilkisi(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGATILKISI, "www.tilkiscans.com", pageSize = 10) {
	override val datePattern = "dd/MM/yyyy"
	override val tagPrefix = "seri-turleri/"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val links = doc.select("nav#mgcr-modern-header-categories a[href*=$tagPrefix]")
		if (links.isEmpty()) {
			return super.fetchAvailableTags()
		}
		return links.mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast(tagPrefix, "")
			val title = a.textOrNull()?.toTitleCase(sourceLocale)
			if (key.isEmpty() || title == null) {
				null
			} else {
				MangaTag(key = key, title = title, source = source)
			}
		}
	}
}
