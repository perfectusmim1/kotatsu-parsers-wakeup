package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken
@MangaSourceParser("NEATMANGA", "NeatManga", "en")
internal class NeatManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.NEATMANGA, "neatmangas.com", pageSize = 20) {
	override val datePattern = "dd MMMM yyyy"
}
