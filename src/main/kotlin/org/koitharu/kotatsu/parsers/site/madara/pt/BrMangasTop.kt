package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken
@MangaSourceParser("BRMANGASTOP", "BrMangasTop", "pt")
internal class BrMangasTop(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.BRMANGASTOP, "brmangas.top", 10) {
	override val datePattern: String = "dd 'de' MMMMM 'de' yyyy"
}
