package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat

@MangaSourceParser("LUNASCANS", "LunaScans", "tr", ContentType.HENTAI)
internal class LunaScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LUNASCANS, "tuhafscans.com") {
	override val withoutAjax = true
	override val datePattern = "dd.MM.yyyy"

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		if (doc.selectFirst("#tuhaf-manga-outer") == null) {
			return super.getDetails(manga)
		}
		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = doc.select("#tuhaf-chapter-grid > a.tuhaf-ch-item").mapChapters(reversed = true) { i, a ->
			val chHref = a.attrAsRelativeUrl("href")
			MangaChapter(
				id = generateUid(chHref),
				title = a.selectFirst(".tuhaf-ch-name")?.textOrNull(),
				number = i + 1f,
				volume = 0,
				url = chHref + stylePage,
				uploadDate = parseChapterDate(dateFormat, a.selectFirst(".tuhaf-ch-date")?.text()),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
		val sidebarRows = doc.select("#tuhaf-sidebar .tuhaf-sidebar-row")
		val stateText = sidebarRows
			.firstOrNull { it.selectFirst(".tuhaf-sidebar-label")?.text()?.contains("Durum", ignoreCase = true) == true }
			?.selectFirst(".tuhaf-sidebar-value")?.text()?.lowercase().orEmpty()
		val state = when (stateText) {
			in ongoing -> MangaState.ONGOING
			in finished -> MangaState.FINISHED
			in abandoned -> MangaState.ABANDONED
			in paused -> MangaState.PAUSED
			in upcoming -> MangaState.UPCOMING
			else -> null
		}
		val author = sidebarRows
			.firstOrNull { it.selectFirst(".tuhaf-sidebar-label")?.text()?.contains("Yazar", ignoreCase = true) == true }
			?.selectFirst(".tuhaf-sidebar-value")?.textOrNull()
		val altTitles = doc.selectFirst("#tuhaf-manga-alt")?.textOrNull()
			?.split(',')
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			.orEmpty()

		return manga.copy(
			title = doc.selectFirst("#tuhaf-manga-title")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = doc.select(".tuhaf-genre-list a").mapNotNullToSet { createMangaTag(it) },
			description = doc.selectFirst(".tuhaf-excerpt-inner")?.html(),
			altTitles = altTitles,
			authors = setOfNotNull(author),
			state = state,
			chapters = chapters,
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}
}
