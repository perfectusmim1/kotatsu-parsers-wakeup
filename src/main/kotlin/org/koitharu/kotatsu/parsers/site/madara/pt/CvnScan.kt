package org.koitharu.kotatsu.parsers.site.madara.pt

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.normalizeSearchQuery
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("CVNSCAN", "CvnScan", "pt", ContentType.HENTAI)
internal class CvnScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.CVNSCAN, "covendasbruxonas.com") {

	override val withoutAjax = true
	override val listUrl = "bruxonas/manga/"
	override val tagPrefix = "manga-generos/"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1
		val url = buildString {
			append("https://")
			append(domain)
			append("/bruxonas")
			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}
			append("/?s=")
			filter.query?.let { query ->
				val normalized = query.normalizeSearchQuery().ifEmpty { query.trim() }
				append(normalized.urlEncoded())
			}
			append("&post_type=wp-manga")
			filter.tags.forEach {
				append("&genre[]=")
				append(it.key)
			}
			filter.states.forEach {
				append("&status[]=")
				when (it) {
					MangaState.ONGOING -> append("on-going")
					MangaState.FINISHED -> append("end")
					MangaState.ABANDONED -> append("canceled")
					MangaState.PAUSED -> append("on-hold")
					MangaState.UPCOMING -> append("upcoming")
					else -> throw IllegalArgumentException("$it not supported")
				}
			}
			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(
					when (it) {
						ContentRating.SAFE -> "0"
						ContentRating.ADULT -> "1"
						else -> ""
					},
				)
			}
			if (filter.year != 0) {
				append("&release=")
				append(filter.year.toString())
			}
			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				else -> Unit
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select("div.manga__item")
		if (items.isEmpty()) {
			return super.parseMangaList(doc)
		}
		return items.mapNotNull { div ->
			val a = div.selectFirst(".post-title a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = a.textOrNull() ?: return@mapNotNull null,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = div.select(".manga-genres a").mapNotNullToSet { tag ->
					MangaTag(
						key = tag.attr("href").removeSuffix("/").substringAfterLast('/'),
						title = tag.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
						source = source,
					)
				},
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/bruxonas/?s=&post_type=wp-manga").parseHtml()
		return doc.select("a[href*='$tagPrefix']").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/').ifEmpty {
				return@mapNotNullToSet null
			}
			val title = a.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}
}
