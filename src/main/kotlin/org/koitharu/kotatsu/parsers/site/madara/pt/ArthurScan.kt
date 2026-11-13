package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("ARTHUR_SCAN", "ArthurScan", "pt")
internal class ArthurScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ARTHUR_SCAN, "arthurscan.xyz") {

	override val withoutAjax = true

	override fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select("div.manga__item")
		if (items.isEmpty()) {
			return super.parseMangaList(doc)
		}
		return items.mapNotNull { div ->
			val a = div.selectFirst(".post-title a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = a.textOrNull() ?: return@mapNotNull null,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = div.select(".manga-genres a").mapNotNullToSet { tag ->
					MangaTag(
						key = tag.attr("href").removeSuffix("/").substringAfterLast('/'),
						title = tag.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
						source = source,
					)
				},
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?s=&post_type=wp-manga").parseHtml()
		return doc.select("a[href*='genre=']").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast("genre=").substringBefore('&').ifEmpty {
				return@mapNotNullToSet null
			}
			val title = a.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}
}
