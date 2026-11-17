package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.HashSet
import java.util.Locale

@MangaSourceParser("YAOIMANGAONLINE", "YaoiMangaOnline", "en", ContentType.HENTAI)
internal class YaoiMangaOnline(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YAOIMANGAONLINE, 12) {

	override val configKeyDomain = ConfigKey.Domain("yaoimangaonline.com")

	private val listPath = "yaoi-manga"

	private val detailDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)

	private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }

	private val cloudflareMessage = "Cloudflare verification is required. Open the source in the in-app browser, complete the check, then try again."

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.RELEVANCE)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private val availableTags = suspendLazy(soft = true) { fetchTags() }

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val isSearch = !filter.query.isNullOrEmpty()
		val tagFilter = if (isSearch) {
			null
		} else {
			filter.tags.firstOrNull { tag ->
				tag.source == source && tag.key != tag.title
			}
		}
		val fullUrl = if (isSearch) {
			buildString {
				append("https://")
				append(domain)
				append("/?s=")
				append(filter.query!!.urlEncoded())
				if (page > 1) {
					append("&paged=")
					append(page)
				}
			}
		} else if (tagFilter != null) {
			buildString {
				append("https://")
				append(domain)
				append("/tag/")
				append(tagFilter.key)
				append('/')
				if (page > 1) {
					append("page/")
					append(page)
					append('/')
				}
			}
		} else {
			buildString {
				append("https://")
				append(domain)
				append('/')
				append(listPath)
				if (page > 1) {
					append("/page/")
					append(page)
					append('/')
				}
			}
		}

		val preferredMatch = when {
			isSearch -> Regex(
				"^https?://${Regex.escape(domain)}/.*[?&]s=.*$",
				RegexOption.IGNORE_CASE,
			)
			tagFilter != null -> buildPathPattern("tag/${tagFilter.key}")
			else -> buildPathPattern(listPath)
		}
		val document = fetchDocument(
			fullUrl,
			preferWebView = isSearch,
			preferredMatch = preferredMatch,
		)
		return document.select("article").mapNotNull { article ->
			val titleAnchor = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
			val rawHref = titleAnchor.attrAsRelativeUrlOrNull("href")
				?: titleAnchor.attr("href").toRelativeUrl(domain)
			val coverUrl = article.selectFirst("img")?.let { img ->
				img.resolveImageUrl()
			}

			Manga(
				id = generateUid(rawHref),
				url = rawHref,
				publicUrl = rawHref.toAbsoluteUrl(domain),
				title = titleAnchor.text().trim(),
				altTitles = emptySet(),
				coverUrl = coverUrl,
				largeCoverUrl = null,
				description = null,
				authors = emptySet(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				source = source,
				contentRating = ContentRating.ADULT,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDocument(
			detailUrl,
			preferredMatch = buildPathPattern(manga.url),
		)
		val description = doc.select("div.entry-content > p").joinToString(separator = "\n\n") { it.text() }
			.nullIfEmpty()
		val tagSet = doc.select("a[rel='tag'], a[rel='category tag']").mapNotNullToSet { anchor ->
			val text = anchor.text().nullIfEmpty() ?: return@mapNotNullToSet null
			val slug = anchor.attrOrNull("href")
				?.trimEnd('/')
				?.substringAfterLast('/')
				?.nullIfEmpty()
				?: text.lowercase(Locale.US).replace(' ', '-')
			MangaTag(
				key = slug,
				title = text.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val chapterElements = doc.select("nav.mpp-toc ul li a")
		val chapters = if (chapterElements.isNotEmpty()) {
			data class ChapterCandidate(
				val index: Int,
				val href: String,
				val title: String,
				val number: Float?,
			)

			val candidates = chapterElements.mapIndexedNotNull { index, anchor ->
				val href = anchor.attrAsRelativeUrlOrNull("href")
					?: anchor.attr("href").toRelativeUrl(domain)
				val rawTitle = anchor.text().nullIfEmpty() ?: anchor.attrOrNull("title")?.nullIfEmpty()
					?: "Chapter ${index + 1}"
				val number = chapterNumberRegex.find(rawTitle)?.value?.toFloatOrNull()
				ChapterCandidate(
					index = index,
					href = href,
					title = rawTitle,
					number = number,
				)
			}
			val sorted = candidates.sortedWith(
				compareBy<ChapterCandidate> { it.number ?: (it.index + 1).toFloat() }
					.thenBy { it.index },
			)
			sorted.mapIndexed { mappedIndex, candidate ->
				MangaChapter(
					id = generateUid(candidate.href),
					url = candidate.href,
					title = candidate.title,
					number = candidate.number ?: (mappedIndex + 1).toFloat(),
					uploadDate = 0L,
					volume = 0,
					branch = null,
					scanlator = null,
					source = source,
				)
			}
		} else {
			val uploadDate = doc.selectFirst("div.entry-meta span.updated")?.text()
				?.let { detailDateFormat.parseSafe(it) }
				?: 0L
			listOf(
				MangaChapter(
					id = manga.id,
					url = manga.url,
					title = "Oneshot",
					number = 1f,
					uploadDate = uploadDate,
					volume = 0,
					branch = null,
					scanlator = null,
					source = source,
				),
			)
		}

		return manga.copy(
			description = description,
			tags = if (tagSet.isEmpty()) manga.tags else tagSet,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterDoc = fetchDocument(
			chapter.url.toAbsoluteUrl(domain),
			preferredMatch = buildPathPattern(chapter.url),
		)
		val contentRoot = chapterDoc.selectFirst("div.entry-content") ?: chapterDoc.body()
		val seen = HashSet<String>()
		return contentRoot.select("img").mapNotNull { img ->
			val imageUrl = img.resolveImageUrl()
				?.takeUnless { it.startsWith("data:") }
				?.takeUnless { !it.contains("/wp-content/") }
				?: return@mapNotNull null
			if (!seen.add(imageUrl)) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private val captureAllPattern = Regex(".*")

	private fun buildPathPattern(path: String): Regex {
		val sanitized = path
			.removePrefix("https://${domain}")
			.removePrefix("http://${domain}")
			.removePrefix("/")
			.substringBefore("?")
			.removeSuffix("/")
		if (sanitized.isEmpty()) {
			return Regex("^https?://${Regex.escape(domain)}(?:/.*)?$", RegexOption.IGNORE_CASE)
		}
		return Regex(
			"^https?://${Regex.escape(domain)}/${Regex.escape(sanitized)}(?:[/?].*)?$",
			RegexOption.IGNORE_CASE,
		)
	}

	private suspend fun fetchDocument(
		url: String,
		preferWebView: Boolean = false,
		preferredMatch: Regex? = null,
	): Document {
		return captureDocument(url, preferredMatch, preferWebViewFirst = preferWebView)
	}

	private suspend fun captureDocument(
		initialUrl: String,
		preferredMatch: Regex? = null,
		preferWebViewFirst: Boolean = false,
		timeoutMs: Long = 15000L,
		allowBrowserAction: Boolean = true,
	): Document {
		// Only use WebView for search operations, regular browsing should work with HTTP
		if (preferWebViewFirst) {
			// For search, use WebView first
			loadDocumentViaWebView(initialUrl)?.let { doc ->
				return doc
			}

			val capturedUrls = try {
				context.captureWebViewUrls(
					pageUrl = initialUrl,
					urlPattern = captureAllPattern,
					timeout = timeoutMs,
				)
			} catch (e: Exception) {
				e.rethrowIfCritical()
				throw ParseException(cloudflareMessage, initialUrl, e)
			}

			if (capturedUrls.isNotEmpty()) {
				val resolvedUrl = preferredMatch?.let { regex ->
					capturedUrls.firstOrNull { url -> regex.containsMatchIn(url) }
				} ?: capturedUrls.firstOrNull { url ->
					url.startsWith("https://$domain") || url.startsWith("http://$domain")
				} ?: capturedUrls.first()

				loadDocumentViaWebView(resolvedUrl)?.let { doc ->
					return doc
				}
			}

			if (allowBrowserAction) {
				requestCloudflareVerification(initialUrl)
			}
			throw ParseException(cloudflareMessage, initialUrl)
		} else {
			// For regular browsing, just use HTTP
			tryHttpDocument(initialUrl)?.let { doc ->
				return doc
			}

			throw ParseException("Failed to load page", initialUrl)
		}
	}

	private fun requestCloudflareVerification(url: String): Nothing {
		try {
			context.requestCloudflareVerification(this, url)
		} catch (e: UnsupportedOperationException) {
			throw ParseException(cloudflareMessage, url, e)
		}
	}

	private suspend fun tryHttpDocument(url: String): Document? {
		val response = try {
			webClient.httpGet(url)
		} catch (e: Exception) {
			e.rethrowIfCritical()
			return null
		}
		return response.use { res ->
			val doc = runCatchingCancellable { res.parseHtml() }.getOrNull() ?: return null

			// Check for successful YaoiMangaOnline content first
			if (hasValidYaoiContent(doc)) {
				return doc
			}

			// Only reject if it's clearly an active Cloudflare challenge
			val html = doc.outerHtml()
			if (isActiveCloudflareChallenge(html)) {
				requestCloudflareVerification(url)
			}

			// If we're not sure, allow the page through
			doc
		}
	}

	private suspend fun loadDocumentViaWebView(url: String): Document? {
		val script = """
			(() => {
				const hasYaoiContent = () => {
					if (!document.documentElement) {
						return false;
					}
					// Check for actual yaoi content
					return document.querySelectorAll("article").length > 0 ||
						   document.querySelector("nav.mpp-toc") !== null ||
						   document.querySelectorAll("h2.entry-title").length > 0 ||
						   document.querySelector("div.entry-content") !== null;
				};
				return new Promise(resolve => {
					const finish = () => {
						resolve(document.documentElement ? document.documentElement.outerHTML : "");
					};
					const waitForContent = (start) => {
						if (hasYaoiContent() || Date.now() - start > 3500) {
							finish();
						} else {
							setTimeout(() => waitForContent(start), 150);
						}
					};
					if (document.readyState === "complete") {
						waitForContent(Date.now());
					} else {
						window.addEventListener("load", () => waitForContent(Date.now()), { once: true });
						setTimeout(() => waitForContent(Date.now()), 2000);
					}
					setTimeout(finish, 5000);
				});
			})();
		""".trimIndent()

		val html = context.evaluateJs(url, script, timeout = 15000L) ?: return null
		if (html.isBlank()) {
			return null
		}

		val doc = Jsoup.parse(html, url)

		// Check for successful YaoiMangaOnline content first
		if (hasValidYaoiContent(doc)) {
			return doc
		}

		// Only reject if it's clearly an active Cloudflare challenge
		if (isActiveCloudflareChallenge(html)) {
			return null
		}

		// If we're not sure, allow the page through
		return doc
	}

	private fun hasValidYaoiContent(doc: Document): Boolean {
		// Check for YaoiMangaOnline-specific content that indicates successful load
		return doc.select("article").isNotEmpty() ||
			doc.select("h2.entry-title").isNotEmpty() ||
			doc.select("nav.mpp-toc").isNotEmpty() ||
			doc.select("div.entry-content").isNotEmpty() ||
			doc.select("a[rel='tag']").isNotEmpty() ||
			doc.title().contains("yaoi", ignoreCase = true)
	}

	private fun isActiveCloudflareChallenge(html: String): Boolean {
		if (html.length < 100) {
			return true
		}
		val lower = html.lowercase(Locale.US)
		// Only reject pages that are clearly active challenge pages
		return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
			(lower.contains("checking your browser") && lower.contains("cloudflare")) ||
			lower.contains("cf-browser-verification") ||
			lower.contains("cf-chl-opt")
	}

	private fun Element.resolveImageUrl(): String? {
		return attrAsAbsoluteUrlOrNull("data-src")
			?: attrAsAbsoluteUrlOrNull("data-lazy-src")
			?: attrAsAbsoluteUrlOrNull("src")
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = fetchDocument(
			url = "https://$domain/",
			preferredMatch = buildPathPattern("/"),
		)
		return doc.select("div.tagcloud a").mapNotNullToSet { anchor ->
			val text = anchor.text().nullIfEmpty() ?: return@mapNotNullToSet null
			val href = anchor.attrOrNull("href")?.trimEnd('/') ?: return@mapNotNullToSet null
			val slug = href.substringAfterLast('/')
			MangaTag(
				key = slug,
				title = text.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}
