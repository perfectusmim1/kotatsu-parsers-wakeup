package org.koitharu.kotatsu.parsers.site.cupfox.de

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.cupfox.CupFoxParser

@Broken
@MangaSourceParser("MANGAHAUS", "MangaHaus", "de")
internal class MangaHaus(context: MangaLoaderContext) :
	CupFoxParser(context, MangaParserSource.MANGAHAUS, "www.mangahaus.com")
