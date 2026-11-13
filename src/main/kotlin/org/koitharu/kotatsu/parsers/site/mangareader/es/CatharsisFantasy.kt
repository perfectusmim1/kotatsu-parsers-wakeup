package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.ArrayList
import java.util.Base64
import org.koitharu.kotatsu.parsers.Broken

@Broken
@MangaSourceParser("CATHARSISFANTASY", "CatharsisFantasy", "es")
internal class CatharsisFantasy(context: MangaLoaderContext) :
	MangaReaderParser(
		context,
		MangaParserSource.CATHARSISFANTASY,
		"catharsisfantasy.com",
		pageSize = 30,
		searchPageSize = 10,
	) {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val firstDocs = webClient.httpGet(chapterUrl).parseHtml()
		val urlFinal = firstDocs.selectFirst("iframe")?.src() ?: chapterUrl
		val docs = webClient.httpGet(urlFinal).parseHtml()
		return parsePagesFromDocument(docs)
	}
}
