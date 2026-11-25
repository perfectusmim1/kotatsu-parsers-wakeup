package org.koitharu.kotatsu.parsers.site.madara.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.host
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("TOONIZY", "Toonizy", "en", ContentType.HENTAI)
internal class Toonizy(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TOONIZY, "toonizy.com", 24) {

	override val datePattern = "MMM d, yy"
	override val listUrl = "webtoon/"

	override fun parseMangaList(doc: Document): List<Manga> {
		val elements = doc.select("div.row.c-tabs-item__content").ifEmpty {
			doc.select("div.page-item-detail")
		}
		if (elements.isEmpty()) {
			return emptyList()
		}
		return elements.mapNotNull { div ->
			val href = div.selectFirst("a")?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".item-summary")
			val authors = summary?.select(".mg_author a, .mg_artists a")
				?.mapNotNullToSet { it.textOrNull() }
				.orEmpty()
			val rawCover = div.selectFirst("img")?.src()
			val normalizedCover = normalizeCoverUrl(rawCover)
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = normalizedCover,
				largeCoverUrl = normalizedCover,
				title = (summary?.selectFirst("h3, h4") ?: div.selectFirst(".manga-name, .post-title"))?.text()
					.orEmpty(),
				altTitles = emptySet(),
				rating = div.selectFirst("span#averagerate, span.score, span.total_votes")
					?.ownText()
					?.trim()
					?.replace(',', '.')
					?.toFloatOrNull()
					?.takeIf { it in 0f..5f }
					?.div(5f)
					?: RATING_UNKNOWN,
				tags = summary?.selectFirst(".mg_genres")?.select("a")?.mapNotNullToSet { a ->
					MangaTag(
						key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
						title = a.text().ifEmpty { return@mapNotNullToSet null }.toTitleCase(),
						source = source,
					)
				}.orEmpty(),
				authors = authors,
				state = when (
					summary?.selectFirst(".mg_status")
						?.selectFirst(".summary-content")
						?.ownText()?.lowercase()
						.orEmpty()
				) {
					in ongoing -> MangaState.ONGOING
					in finished -> MangaState.FINISHED
					in abandoned -> MangaState.ABANDONED
					in paused -> MangaState.PAUSED
					in upcoming -> MangaState.UPCOMING
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain)
			?.nullIfEmpty() ?: manga.url
		val testCheckAsync = doc.select(selectTestAsync)
		val chaptersDeferred = if (testCheckAsync.isEmpty()) {
			async { loadChapters(href, doc) }
		} else {
			async { getChapters(manga, doc) }
		}

		val title = doc.selectFirst(".post-content h3, .post-title h1, h1")?.textOrNull() ?: manga.title
		val desc = doc.select(selectDesc).html()
		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")
		val state = stateDiv?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				in upcoming -> MangaState.UPCOMING
				else -> null
			}
		}
		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()
		val authors = doc.select(".author-content a, .artist-content a")
			.mapNotNullToSet { it.textOrNull() }
			.ifEmpty { manga.authors }
		val ogCover = doc.selectFirst("head meta[property='og:image']")?.attr("content")?.nullIfEmpty()
		val summaryCover = doc.selectFirst(".summary_image img")?.src()
		val resolvedLargeCover = normalizeCoverUrl(ogCover ?: summaryCover) ?: manga.largeCoverUrl ?: manga.coverUrl
		val resolvedCover = normalizeCoverUrl(summaryCover ?: ogCover) ?: manga.coverUrl

		manga.copy(
			title = title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = resolvedCover,
			largeCoverUrl = resolvedLargeCover,
			rating = parseDetailRating(doc) ?: manga.rating,
			tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
			description = desc,
			altTitles = setOfNotNull(alt),
			authors = authors,
			state = state,
			chapters = chaptersDeferred.await(),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		return super.getRelatedManga(seed).map { item ->
			val normalized = normalizeCoverUrl(item.coverUrl)
			if (normalized != item.coverUrl) {
				item.copy(coverUrl = normalized, largeCoverUrl = normalized)
			} else {
				item
			}
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments.filter { it.isNotBlank() }
		if (segments.size < 2 || segments[0] != "webtoon") {
			return null
		}
		val relativeUrl = "/webtoon/${segments[1]}/"
		val seed = Manga(
			id = generateUid(relativeUrl),
			title = "",
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return runCatching { getDetails(seed) }.getOrNull()
	}

	private fun normalizeCoverUrl(rawUrl: String?): String? {
		val trimmed = rawUrl?.trim()?.nullIfEmpty() ?: return null
		return WP_THUMB_RESIZE_REGEX.replace(trimmed, "$1")
	}

	private companion object {
		val WP_THUMB_RESIZE_REGEX = Regex("""(?<=/thumbnail)-\d+x\d+(\.[a-zA-Z0-9]+(?:$|\?))""")
	}
}
