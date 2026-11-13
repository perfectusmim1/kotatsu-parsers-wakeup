package org.koitharu.kotatsu.parsers.site.mangareader.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.Locale

@MangaSourceParser("TAROTSCANS", "TarotScans", "tr")
internal class TarotScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TAROTSCANS, "www.tarotscans.com", pageSize = 10) {

	override val sourceLocale: Locale = Locale.ENGLISH
}
