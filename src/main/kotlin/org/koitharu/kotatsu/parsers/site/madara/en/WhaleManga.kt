package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken
@MangaSourceParser("WHALEMANGA", "WhaleManga", "en")
internal class WhaleManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.WHALEMANGA, "whalemanga.com", 10)
