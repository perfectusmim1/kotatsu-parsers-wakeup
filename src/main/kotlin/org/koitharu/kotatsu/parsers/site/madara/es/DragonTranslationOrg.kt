package org.koitharu.kotatsu.parsers.site.madara.es

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat

@MangaSourceParser("DRAGONTRANSLATIONORG", "DragonTranslation.org", "es")
internal class DragonTranslationOrg(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.DRAGONTRANSLATIONORG, "dragontranslation.org", 16) {
	override val datePattern = "MMMM d, yyyy"

	override val selectDesc =
		"div:has(> p):has(+ button), div.hero__in div.clamp, div.syn > p, div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, div.post-content div.manga-summary, div.post-content div.desc"
	override val selectGenre = "div > a[href*=/manga-genre/], div.genres-content a"
	override val selectPage = "img.wp-manga-chapter-img"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val isUnfilteredLatest = order == SortOrder.UPDATED &&
			filter.query.isNullOrBlank() &&
			filter.tags.isEmpty() &&
			filter.states.isEmpty() &&
			filter.contentRating.isEmpty() &&
			filter.year == 0 &&
			filter.author.isNullOrBlank()
		if (isUnfilteredLatest) {
			val url = buildString {
				append("https://")
				append(domain)
				append('/')
				append(listUrl)
				if (page > 0) {
					append("page/")
					append(page + 1)
					append('/')
				}
				append("?m_orderby=latest")
			}
			val latestList = parseMangaList(webClient.httpGet(url).parseHtml())
			if (latestList.isNotEmpty() || page > 0) {
				return latestList
			}
		}
		return super.getListPage(page, order, filter)
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val standard = super.parseMangaList(doc)
		if (standard.isNotEmpty()) {
			return standard
		}
		val cards = doc.select("a:has(img.wp-post-image)").ifEmpty {
			doc.select("a:has(img.ac-cover)")
		}
		if (cards.isEmpty()) {
			return emptyList()
		}
		return cards.mapNotNull { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = a.attrOrNull("title")
				?: a.selectFirst("h1, h2, h3, h4, h5, .ac-t")?.textOrNull()
				?: a.textOrNull()
				?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(a.host ?: domain),
				coverUrl = a.selectFirst("img")?.src(),
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

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val genreLinks = doc.select("a[href*=/manga-genre/]")
		if (genreLinks.isEmpty()) {
			return runCatchingCancellable { super.fetchAvailableTags() }.getOrDefault(emptySet())
		}
		val keys = HashSet<String>(genreLinks.size)
		return genreLinks.mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix('/').substringAfterLast('/', "")
			val name = a.textOrNull()?.toTitleCase(sourceLocale)
			if (key.isEmpty() || name == null || !keys.add(key)) {
				return@mapNotNullToSet null
			}
			MangaTag(key = key, title = name, source = source)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val jsonChapters = parseChaptersFromJson(doc)
		val chaptersDeferred = async {
			if (jsonChapters.isNotEmpty()) {
				jsonChapters
			} else if (doc.select(selectTestAsync).isNotEmpty()) {
				getChapters(manga, doc)
			} else {
				runCatchingCancellable { loadChapters(href, doc) }.getOrElse { getChapters(manga, doc) }
			}
		}

		val desc = doc.selectFirst(selectDesc)?.html()?.takeIf { it.isNotBlank() }
			?: doc.select(selectDesc).html()

		val statusText = (
			doc.selectFirst("span:matchesOwn(^Estado$) + span")
				?: doc.selectFirst("div.sir:has(span.l:contains(Estado)) span.v")
				?: doc.selectFirst("div.htags > span.htag")
				?: doc.selectFirst(selectState)?.selectLast("div.summary-content")
			)?.text()?.lowercase()

		val state = when {
			statusText == null -> null
			statusText in ongoing || "emisión" in statusText || "emision" in statusText || "public" in statusText -> MangaState.ONGOING
			statusText in finished || "complet" in statusText || "finaliz" in statusText -> MangaState.FINISHED
			statusText in abandoned || "cancel" in statusText -> MangaState.ABANDONED
			statusText in paused || "paus" in statusText -> MangaState.PAUSED
			else -> null
		}

		val alt = (
			doc.selectFirst("span:matchesOwn(^Alternativo$) + span")
				?: doc.selectFirst("div.sir:has(span.l:contains(Alternativo)) span.v")
				?: doc.body().select(selectAlt).firstOrNull()?.tableValue()
			)?.textOrNull()?.takeUnless { it == "-" || it.equals("N/A", ignoreCase = true) }

		val titleEl = doc.selectFirst(".type-wp-manga h1") ?: doc.selectFirst("div.hero__in h1") ?: doc.selectFirst("h1")
		val title = titleEl?.ownText()?.trim()?.nullIfEmpty() ?: titleEl?.textOrNull() ?: manga.title
		val cover = doc.selectFirst("img.wp-post-image")?.src() ?: manga.coverUrl

		manga.copy(
			title = title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = cover,
			tags = doc.body().select(selectGenre).mapNotNullToSet { a ->
				createMangaTag(a)?.takeIf { it.key.isNotEmpty() && it.title.isNotEmpty() }
			},
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = chaptersDeferred.await(),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		if (doc.getElementById("chapter-protector-data") != null) {
			return super.getPages(chapter)
		}
		if (doc.selectFirst(selectRequiredLogin) != null) {
			throw AuthRequiredException(source)
		}
		val images = doc.select(selectPage).ifEmpty {
			doc.select("div.reading-content img, div.page-break img")
		}
		if (images.isEmpty()) {
			throw ParseException("No image found, try to log in", fullUrl)
		}
		return images.mapNotNull { img ->
			val src = img.src()?.trim()?.nullIfEmpty() ?: return@mapNotNull null
			val url = src.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChaptersFromJson(doc: Document): List<MangaChapter> {
		val script = doc.selectFirst("script[type=application/json]:containsData(mangaId)")
			?: doc.selectFirst("div.mk-chapters script[type=application/json]")
			?: doc.selectFirst("#mkChapters script[type=application/json]")
			?: doc.selectFirst("script#mk-chapters-data")
			?: doc.select("script[type=application/json]").firstOrNull { it.data().contains("\"items\"") }
			?: return emptyList()

		val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return emptyList()
		val items = json.optJSONArray("items") ?: return emptyList()
		if (items.length() == 0) {
			return emptyList()
		}

		val monthDateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val slashDateFormat = SimpleDateFormat("dd/MM/yyyy", sourceLocale)
		val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", sourceLocale)

		val list = ArrayList<MangaChapter>(items.length())
		for (index in 0 until items.length()) {
			val item = items.optJSONObject(index) ?: continue
			val rawUrl = item.getStringOrNull("url")?.nullIfEmpty() ?: continue
			val href = rawUrl.toRelativeUrl(domain) + stylePage
			val title = item.getStringOrNull("name") ?: item.getStringOrNull("title")
			val rawDate = item.getStringOrNull("ago") ?: item.getStringOrNull("date")
			val uploadDate = parseDragonChapterDate(rawDate, monthDateFormat, slashDateFormat, isoDateFormat)
			list.add(
				MangaChapter(
					id = generateUid(rawUrl.toRelativeUrl(domain)),
					title = title,
					number = 0f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				),
			)
		}
		return list.reversed().mapIndexed { i, chapter -> chapter.copy(number = i + 1f) }
	}

	private fun parseDragonChapterDate(
		rawDate: String?,
		monthFormat: SimpleDateFormat,
		slashFormat: SimpleDateFormat,
		isoFormat: SimpleDateFormat,
	): Long {
		val date = rawDate?.trim()?.nullIfEmpty() ?: return 0L
		val relative = parseChapterDate(monthFormat, date)
		if (relative > 0L) return relative
		return slashFormat.parseSafe(date).takeIf { it > 0L }
			?: isoFormat.parseSafe(date)
	}
}
