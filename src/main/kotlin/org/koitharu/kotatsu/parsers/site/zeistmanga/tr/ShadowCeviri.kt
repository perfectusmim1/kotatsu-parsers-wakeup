package org.koitharu.kotatsu.parsers.site.zeistmanga.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.net.URLDecoder

@MangaSourceParser("SHADOWCEVIRI", "ShadowCeviri", "tr", ContentType.COMICS)
internal class ShadowCeviri(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.SHADOWCEVIRI, "shadowceviri.blogspot.com") {
	override val sateOngoing: String = "Devam Ediyor"
	override val sateFinished: String = "Tamamlandı"
	override val sateAbandoned: String = "Güncel"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/search").parseHtml()
		val activeCategories = runCatching {
			val entries = webClient.httpGet(
				"https://$domain/feeds/posts/default/-/$mangaCategory?alt=json&max-results=150",
			).parseJson().optJSONObject("feed")?.optJSONArray("entry")
			buildSet {
				if (entries != null) {
					for (i in 0 until entries.length()) {
						val cats = entries.optJSONObject(i)?.optJSONArray("category") ?: continue
						for (j in 0 until cats.length()) {
							cats.optJSONObject(j)?.optString("term")
								?.trim()
								?.takeIf { it.isNotEmpty() }
								?.let { add(it.lowercase(sourceLocale)) }
						}
					}
				}
			}
		}.getOrDefault(emptySet())

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
				decoded.equals("Project", ignoreCase = true)
			) {
				return@mapNotNull null
			}
			val lower = decoded.lowercase(sourceLocale)
			if (activeCategories.isNotEmpty() && lower !in activeCategories) {
				return@mapNotNull null
			}
			if (!seen.add(lower)) return@mapNotNull null
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
