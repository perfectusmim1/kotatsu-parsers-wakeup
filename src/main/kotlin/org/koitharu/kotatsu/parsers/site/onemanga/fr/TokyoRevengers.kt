package org.koitharu.kotatsu.parsers.site.onemanga.fr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.onemanga.OneMangaParser

@Broken
@MangaSourceParser("TOKYOREVENGERS", "TokyoRevengers", "fr")
internal class TokyoRevengers(context: MangaLoaderContext) :
	OneMangaParser(context, MangaParserSource.TOKYOREVENGERS, "tokyorevengers.fr")
