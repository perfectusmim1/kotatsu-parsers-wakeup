package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@Broken
@MangaSourceParser("MIAUSCAN", "LectorMiau", "es")
internal class MiauScan(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MIAUSCAN, "leemiau.com", pageSize = 20, searchPageSize = 10)
