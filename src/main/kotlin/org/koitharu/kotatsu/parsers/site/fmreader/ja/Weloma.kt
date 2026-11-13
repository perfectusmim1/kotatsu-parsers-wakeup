package org.koitharu.kotatsu.parsers.site.fmreader.ja

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.fmreader.FmreaderParser
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl

@MangaSourceParser("WELOMA", "Weloma", "ja")
internal class Weloma(context: MangaLoaderContext) :
	FmreaderParser(context, MangaParserSource.WELOMA, "weloma.net") {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(selectPage).mapNotNull { img ->
			val encoded = img.attrOrNull("data-img")
			val url = if (encoded != null) {
				context.decodeBase64(encoded).toString(Charsets.UTF_8)
			} else {
				img.src()?.takeUnless { it.startsWith("data:") }
			} ?: return@mapNotNull null
			val finalUrl = url.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(finalUrl),
				url = finalUrl,
				preview = null,
				source = source,
			)
		}
	}
}
