package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderScriptSupport
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGATV", "MangaTv", "es")
internal class MangaTv(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MANGATV, "mangatv.net", pageSize = 25, searchPageSize = 25) {
	override val listUrl = "/lista"
	override val detailsDescriptionSelector = "b:contains(Sinopsis) + span"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
	override val datePattern = "yyyy-MM-dd"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {

				!filter.query.isNullOrEmpty() -> {
					append("/lista?s=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page.toString())
				}

				else -> {
					append(listUrl)

					append("/?order=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.ALPHABETICAL_DESC -> "titlereverse"
							SortOrder.NEWEST -> "latest"
							SortOrder.POPULARITY -> "popular"
							SortOrder.UPDATED -> "update"
							else -> ""
						},
					)

					filter.tags.forEach {
						append("&")
						append("genre[]".urlEncoded())
						append("=")
						append(it.key)
					}

					filter.tagsExclude.forEach {
						append("&")
						append("genre[]".urlEncoded())
						append("=-")
						append(it.key)
					}

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append("&status=")
							when (it) {
								MangaState.ONGOING -> append("ongoing")
								MangaState.FINISHED -> append("completed")
								MangaState.PAUSED -> append("hiatus")
								else -> append("")
							}
						}
					}

					append("&page=")
					append(page.toString())
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = webClient.httpGet(chapterUrl).parseHtml()
		val imageUrls = MangaReaderScriptSupport.extractScriptPageUrls(
			context = context,
			docs = docs,
			selectTestScript = selectTestScript,
			selectScript = selectScript,
			encodedSrc = encodedSrc,
		)
		if (imageUrls.isEmpty()) error("The image URLs could not be extracted")

		return imageUrls.map { decodedUrl ->
			MangaPage(
				id = generateUid(decodedUrl),
				url = decodedUrl,
				preview = null,
				source = source,
			)
		}
	}
}
