package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Broken
@MangaSourceParser("MYCOMICLIST", "MyComicList", "en", ContentType.COMICS)
internal class MyComicList(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MYCOMICLIST, 20) {

	override val configKeyDomain = ConfigKey.Domain("comichubfree.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search-comic?key=")
					append(filter.query.urlEncoded())
				}

				filter.tags.isNotEmpty() -> {
					append("/")
					append(filter.tags.first().key)
					append("-comic")
				}

				else -> when (order) {
					SortOrder.UPDATED -> append("/hot-comic")
					SortOrder.POPULARITY -> append("/popular-comic")
					SortOrder.NEWEST -> append("/new-comic")
					else -> append("/ongoing-comic")
				}
			}
			if (page > 1) {
				append(if ('?' in this) "&page=" else "?page=")
				append(page)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.cartoon-box").map { div ->
			val link = div.selectFirst("h3 a") ?: div.selectFirstOrThrow("a")
			val href = link.attrAsRelativeUrl("href")
			val img = div.selectFirst("img.lazyload") ?: div.selectFirst("img")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = link.text(),
				altTitles = emptySet(),
				authors = emptySet(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = img?.src(),
				state = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val author = doc.selectFirst("dt.movie-dt:contains(Author:) + dd")?.textOrNull()
		return manga.copy(
			tags = doc.select("dt.movie-dt:contains(Genres:) + dd a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('/').substringBefore("-comic"),
					title = a.text().toTitleCase(sourceLocale),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			state = when (doc.selectFirst("dt.movie-dt:contains(Status:) + dd a")?.text()?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				else -> null
			},
			description = doc.selectFirst("#film-content")?.html(),
			chapters = doc.select("tbody#list tr").mapChapters(reversed = true) { i, tr ->
				val a = tr.selectFirst("td a") ?: return@mapChapters null
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()

				MangaChapter(
					id = generateUid(href),
					title = name,
					number = name.substringAfter('#').toFloatOrNull() ?: (i + 1f),
					url = href,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
					volume = 0,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain) + "/all"
		val doc = webClient.httpGet(fullUrl).parseHtml()

		return doc.select("img.chapter_img.lazyload").mapNotNull { img ->
			val imageUrl = img.attrOrNull("data-src") ?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.select("div.right-box ul.tag-box a.tag-link").mapToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfterLast('/').substringBefore("-comic")
			MangaTag(
				key = key,
				title = a.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}
