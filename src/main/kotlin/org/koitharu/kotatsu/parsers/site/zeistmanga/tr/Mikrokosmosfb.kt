package org.koitharu.kotatsu.parsers.site.zeistmanga.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.net.URLDecoder

@MangaSourceParser("MIKROKOSMOSFB", "Mikrokosmosfb", "tr", ContentType.HENTAI)
internal class Mikrokosmosfb(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.MIKROKOSMOSFB, "mikrokosmosfb.blogspot.com") {
	override val sateOngoing: String = "Devam Ediyor"
	override val sateFinished: String = "Tamamlandı"
	override val sateAbandoned: String = "Güncel"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/search").parseHtml()
		val seen = HashSet<String>()
		return doc.select("a[href*='/search/label/']").mapNotNull { a ->
			val rawLabel = a.attr("href")
				.substringAfter("/search/label/", "")
				.substringBefore('?')
				.substringBefore('#')
				.trim()
			if (rawLabel.isEmpty() || '\'' in rawLabel) return@mapNotNull null
			val decoded = runCatching {
				URLDecoder.decode(rawLabel, Charsets.UTF_8.name())
			}.getOrDefault(rawLabel).trim()
			if (decoded.isEmpty() || '\'' in decoded) return@mapNotNull null
			if (decoded.equals(mangaCategory, ignoreCase = true) ||
				decoded.equals("Seriler", ignoreCase = true) ||
				decoded.equals("Project", ignoreCase = true) ||
				decoded.equals("Slider", ignoreCase = true) ||
				decoded.equals("Update", ignoreCase = true)
			) {
				return@mapNotNull null
			}
			if (!seen.add(decoded.lowercase(sourceLocale))) return@mapNotNull null
			val title = decoded.removeSuffix(",").trim().toTitleCase(sourceLocale)
			if (title.isEmpty()) return@mapNotNull null
			MangaTag(
				title = title,
				key = decoded,
				source = source,
			)
		}.toSet()
	}
}
