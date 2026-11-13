package org.koitharu.kotatsu.parsers.site.mangareader.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@Broken
@MangaSourceParser("IRISSCANLATOR", "IrisScanlator", "pt")
internal class IrisScanlator(context: MangaLoaderContext) :
	MangaReaderParser(
		context,
		MangaParserSource.IRISSCANLATOR,
		"irisscanlator.com.br",
		pageSize = 20,
		searchPageSize = 10,
	)
