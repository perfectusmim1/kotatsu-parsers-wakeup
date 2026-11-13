package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderScriptSupport
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("LECTORHENTAI", "LectorHentai", "es", ContentType.HENTAI)
internal class LectorHentai(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LECTORHENTAI, "lectorhentai.com", pageSize = 25, searchPageSize = 25) {
	override val listUrl = "/tipo/all"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					val normalized = filter.query.normalizeSearchQuery().ifEmpty { filter.query.trim() }
					append(listUrl)
					append("?s=")
					append(normalized.urlEncoded())
					append("&page=")
					append(page.toString())
				}

				else -> {
					append(listUrl)
					append("?order=")
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

	override val selectChapter = ".releases"
	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val isoDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
		val shortEsFormat = SimpleDateFormat("d MMM yyyy", sourceLocale)
		val timeEl = docs.selectFirst("time")
		val uploadDate = timeEl?.attrOrNull("datetime")?.let { isoDateFormat.parseSafe(it) }
			?.takeIf { it > 0L }
			?: timeEl?.text()?.let { shortEsFormat.parseSafe(it).takeIf { d -> d > 0L } ?: dateFormat.parseSafe(it) }
			?: 0L
		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val url = element.selectFirst("a")?.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(url),
				title = element.selectFirst("h2")?.textOrNull(),
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
		return parseInfo(docs, manga, chapters)
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
		).ifEmpty { extractPagesFromHtml(docs) }
		if (imageUrls.isEmpty()) {
			docs.parseFailed("Cannot extract chapter pages")
		}
		return imageUrls.map { resolvedUrl ->
			MangaPage(
				id = generateUid(resolvedUrl),
				url = resolvedUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun extractPagesFromHtml(docs: Document): List<String> {
		return docs.select(selectPage).mapNotNull { img ->
			val url = img.src()?.trim() ?: return@mapNotNull null
			url.takeIf(MangaReaderScriptSupport::isValidImageUrl)
		}
	}
}
