package org.koitharu.kotatsu.parsers.site.madara.tr

import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("NABISCANS", "NabiScans", "tr")
internal class NabiScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.NABISCANS, "www.nabicix.com", 10, 10) {
	override val datePattern = "MMMM d, yyyy"

	private var orderedTagCache: Map<String, MangaTag>? = null

	override suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		orderedTagCache?.let { return@withLock it }
		val url = listUrl.toAbsoluteUrl(domain)
		val tagElements = webClient.httpGet(url).parseHtml().select("ul.genrez > li")
		val tagMap = LinkedHashMap<String, MangaTag>(tagElements.size)
		for (el in tagElements) {
			val rawText = el.textOrNull() ?: continue
			tagMap[rawText] = MangaTag(
				title = rawText.toTitleCase(sourceLocale),
				key = el.selectFirst("input")?.attrOrNull("value") ?: continue,
				source = source,
			)
		}
		orderedTagCache = tagMap
		return@withLock tagMap
	}
}
