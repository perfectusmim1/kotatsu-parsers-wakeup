package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource

@MangaSourceParser("MANGAWT_NET", "MangaWt.net", "tr")
internal class MangaWtNet(context: MangaLoaderContext) :
	MangawtParser(context, MangaParserSource.MANGAWT_NET)
