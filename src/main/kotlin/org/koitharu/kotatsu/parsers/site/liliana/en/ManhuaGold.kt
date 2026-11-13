package org.koitharu.kotatsu.parsers.site.liliana.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.liliana.LilianaParser

@Broken
@MangaSourceParser("MANHUAGOLD", "ManhuaGold", "en")
internal class ManhuaGold(context: MangaLoaderContext) :
	LilianaParser(context, MangaParserSource.MANHUAGOLD, "manhuagold.top")
