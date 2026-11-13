package org.koitharu.kotatsu.parsers.site.liliana.ja

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.liliana.LilianaParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.normalizeSearchQuery
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("MANGAKOMA01", "MangaKoma01", "ja")
internal class MangaKoma01(context: MangaLoaderContext) :
	LilianaParser(context, MangaParserSource.MANGAKOMA01, "mangakoma01.top") {

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				val normalized = filter.query.normalizeSearchQuery().ifEmpty { filter.query.trim() }
				append("/search/")
				append(page)
				append("/?keyword=")
				append(normalized.urlEncoded())
			} else {
				append("/filter/")
				append(page)
				append("/?sort=")
				when (order) {
					SortOrder.UPDATED -> append("latest-updated")
					SortOrder.POPULARITY -> append("views")
					SortOrder.POPULARITY_MONTH -> append("views_month")
					SortOrder.POPULARITY_WEEK -> append("views_week")
					SortOrder.POPULARITY_TODAY -> append("views_day")
					SortOrder.ALPHABETICAL -> append("az")
					SortOrder.ALPHABETICAL_DESC -> append("za")
					SortOrder.NEWEST -> append("new")
					SortOrder.NEWEST_ASC -> append("old")
					SortOrder.RATING -> append("score")
					else -> append("latest-updated")
				}
				append("&genres=")
				filter.tags.joinTo(this, ",") { it.key }
				append("&notGenres=")
				filter.tagsExclude.joinTo(this, ",") { it.key }
				if (filter.states.isNotEmpty()) {
					append("&status=")
					append(
						when (filter.states.first()) {
							MangaState.ONGOING -> "on-going"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "on-hold"
							MangaState.ABANDONED -> "canceled"
							else -> ""
						},
					)
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.items .item").mapNotNull { element ->
			val link = element.selectFirst("figcaption h3 a") ?: element.selectFirst("a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val title = link.textOrNull() ?: element.selectFirst("a")?.attr("title").orEmpty()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = element.selectFirst("img")?.src(),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = doc.selectFirst("li.author p.col-xs-8")?.textOrNull()?.takeUnless {
			it == "未詳" || it.equals("updating", true)
		}
		return manga.copy(
			description = doc.selectFirst(".detail-content p")?.html()
				?: doc.selectFirst(".detail-content")?.html(),
			largeCoverUrl = doc.selectFirst(".detail-info img")?.src(),
			tags = doc.select("li.kind a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
					title = a.text().toTitleCase(sourceLocale),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			state = when (doc.selectFirst("li.status p.col-xs-8")?.text()?.lowercase().orEmpty()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in paused -> MangaState.PAUSED
				in abandoned -> MangaState.ABANDONED
				else -> null
			},
			chapters = doc.select(".list-chapter li div.chapter a, #nt_listchapter li div.chapter a")
				.mapChapters(reversed = true) { i, a ->
					val href = a.attrAsRelativeUrl("href")
					MangaChapter(
						id = generateUid(href),
						title = a.textOrNull(),
						number = i + 1f,
						volume = 0,
						url = href,
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/filter/1/").parseHtml()
		return doc.select("ul.nav a[href*='/genres/']").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/').ifEmpty { return@mapNotNullToSet null }
			val title = a.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}
}
