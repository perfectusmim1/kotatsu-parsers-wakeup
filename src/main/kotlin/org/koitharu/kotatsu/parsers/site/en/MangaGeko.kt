package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAGEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAGEKO, 30) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc", "www.mgeko.com", "www.mangageko.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false, // Only one genre at a time
			isTagsExclusionSupported = true, // Single genre exclusion supported
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Use autocomplete API for search queries
		if (!filter.query.isNullOrEmpty()) {
			return getSearchResults(filter.query!!)
		}

		// Use regular browse for non-search requests
		val url = buildString {
			append("https://")
			append(domain)
			append("/browse-comics/")

			val params = mutableListOf<String>()

			// Add page parameter
			if (page > 1) {
				params.add("page=$page")
			} else {
				params.add("page=1")
			}

			// Add single genre filter (only one genre allowed)
			if (filter.tags.isNotEmpty()) {
				val singleTag = filter.tags.first() // Only use the first selected genre
				params.add("include_genres=${singleTag.key}")
			}

			// Add single excluded genre (only one genre allowed)
			if (filter.tagsExclude.isNotEmpty()) {
				val singleExcludeTag = filter.tagsExclude.first() // Only use the first excluded genre
				params.add("exclude_genres=${singleExcludeTag.key}")
			}

			// Add sort filter
			params.add("filter=" + when (order) {
				SortOrder.POPULARITY -> "views"
				SortOrder.UPDATED -> "Updated"
				SortOrder.NEWEST -> "New"
				else -> "Updated"
			})

			// Append parameters
			if (params.isNotEmpty()) {
				append("?")
				append(params.joinToString("&"))
			}
		}

		val directDoc = runCatchingCancellable {
			webClient.httpGet(url).parseHtml()
		}.getOrNull()
		val doc = if (directDoc != null && directDoc.select("article.comic-card").isNotEmpty()) {
			directDoc
		} else {
			captureDocument(url)
		}

		return doc.select("article.comic-card").map { article ->
			val href = article.selectFirstOrThrow("h3.comic-card__title a").attrAsRelativeUrl("href")
			val title = article.selectFirstOrThrow("h3.comic-card__title a").text()
			val coverUrl = article.selectFirst("div.comic-card__cover img")?.src() ?: ""

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun getSearchResults(query: String): List<Manga> {
		val url = "https://$domain/autocomplete?term=${query.urlEncoded()}"
		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("li.novel-item").mapNotNull { item ->
			val a = item.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val title = item.selectFirst("h4.novel-title")?.text()?.replace(Regex("<mark>|</mark>"), "") ?: return@mapNotNull null
			val coverUrl = item.selectFirst("img")?.src() ?: ""

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse-comics/").parseHtml()
		return doc.select("button.chip[data-group='include_genres']").mapToSet { button ->
			val value = button.attr("data-value")
			val title = button.text()
			MangaTag(
				key = value,
				title = title,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chaptersDeferred = async { loadChapters(manga.url) }
		val author = doc.selectFirstOrThrow(".author").textOrNull()
		manga.copy(
			altTitles = setOfNotNull(doc.selectFirstOrThrow(".alternative-title").textOrNull()),
			state = when (doc.selectFirstOrThrow(".header-stats span:contains(Status) strong").text()) {
				"Ongoing" -> MangaState.ONGOING
				"Completed" -> MangaState.FINISHED
				else -> null
			},
			tags = doc.select(".categories ul li a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('='),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = doc.selectFirstOrThrow(".description").html(),
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val urlChapter = mangaUrl + "all-chapters/"
		val doc = webClient.httpGet(urlChapter.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", sourceLocale)
		return doc.requireElementById("chapters").select("ul.chapter-list li")
			.mapChapters(reversed = true) { i, li ->
				val a = li.selectFirstOrThrow("a")
				val url = a.attrAsRelativeUrl("href")
				val name = li.selectFirstOrThrow(".chapter-title").text()
				val dateText = li.select(".chapter-update").attr("datetime").substringBeforeLast(',')
					.replace(".", "").replace("Sept", "Sep")
				MangaChapter(
					id = generateUid(url),
					title = name,
					number = i + 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateText),
					branch = null,
					source = source,
				)
			}
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("center img")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            // remove all invaild images + credits
            .filterNot { it.startsWith("data:image") || it.contains("credits-mgeko.png") }
            .distinct().map { url ->
                val finalUrl = url.toRelativeUrl(domain)
                MangaPage(
                    id = generateUid(finalUrl),
                    url = finalUrl,
                    preview = null,
                    source = source,
                )
            }
    }

	private suspend fun captureDocument(url: String): Document {
		val script = """
			(() => {
				// Check for different types of content
				const resultsContainer = document.querySelector('#results-container');
				const comicsContainer = document.querySelector('#comics-container');
				const comicCards = document.querySelectorAll('#results-container article.comic-card');

				// For browse-comics pages, wait for comic cards to be populated in results container
				if (resultsContainer && comicsContainer) {
					if (comicCards.length > 0) {
						window.stop();
						const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
						elementsToRemove.forEach(el => el.remove());
						return document.documentElement.outerHTML;
					}
					return null; // Keep waiting for content to load
				}

				// For other content types (details, chapters, tags)
				const hasMangaDetails = document.querySelector('.author') !== null ||
										document.querySelector('.description') !== null ||
										document.querySelector('.categories') !== null;

				const hasChapterList = document.querySelector('#chapters') !== null ||
									   document.querySelector('ul.chapter-list') !== null;

				const hasChapterPages = document.querySelector('center img') !== null;

				const hasTags = document.querySelector('button.chip[data-group="include_genres"]') !== null;

				// If any other expected content is found, stop loading and return HTML
				if (hasMangaDetails || hasChapterList || hasChapterPages || hasTags) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script, iframe, object, embed, style');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement.outerHTML;
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = context.evaluateJs(url, script, 30000L) ?: throw ParseException("Failed to load page", url)

		val html = if (rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
			rawHtml.substring(1, rawHtml.length - 1)
				.replace("\\\"", "\"")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
					val hexValue = match.groupValues[1]
					hexValue.toInt(16).toChar().toString()
				}
		} else rawHtml

		return Jsoup.parse(html, url)
	}
}
