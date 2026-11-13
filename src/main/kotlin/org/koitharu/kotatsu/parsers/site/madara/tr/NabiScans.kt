package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("NABISCANS", "NabiScans", "tr")
internal class NabiScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.NABISCANS, "www.nabicix.com", 10, 10) {
	override val datePattern = "MMMM d, yyyy"
}
