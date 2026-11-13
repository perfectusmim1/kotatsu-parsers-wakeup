package org.koitharu.kotatsu.parsers.site.mangareader.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@Broken
@MangaSourceParser("ORIGAMIORPHEANS", "Origami Orpheans", "pt")
internal class Origamiorpheans(context: MangaLoaderContext) :
	MangaReaderParser(
		context,
		MangaParserSource.ORIGAMIORPHEANS,
		"origami-orpheans.com",
		pageSize = 20,
		searchPageSize = 10,
	)
