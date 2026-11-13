package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.initmanga.InitMangaParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@Broken
@MangaSourceParser("RAGNARSCANS", "Ragnarscans", "tr")
internal class Ragnarscans(context: MangaLoaderContext) :
	InitMangaParser(
		context = context,
		source = MangaParserSource.RAGNARSCANS,
		domain = "ragnarscans2.com",
		pageSize = 20,
		searchPageSize = 20,
		mangaUrlDirectory = "manga",
		popularUrlSlug = "en-cok-takip-edilenler",
		isCloudflareProtected = true,
	) {

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			super.getPages(chapter)
		} catch (e: Exception) {
			val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
			if (doc.selectFirst("#chapter-content .lock-card, .lock-card") != null) {
				throw AuthRequiredException(source)
			}
			throw e
		}
	}
}
