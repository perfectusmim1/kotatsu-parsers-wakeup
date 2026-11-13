package org.koitharu.kotatsu.parsers.site.tr

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.exception.ParseException

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.site.community.OkutoonCommunityHelper
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("OKUTOON", "OkuToon", "tr", ContentType.MANGA)
internal class Okutoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.OKUTOON, pageSize = 24),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("okutoon.com")

	private val communityHelper by lazy {
		OkutoonCommunityHelper(
			context = context,
			webClient = webClient,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
		)
	}

	override val authUrl: String
		get() = communityHelper.authUrl

	override val signUpUrl: String?
		get() = communityHelper.signUpUrl

	override suspend fun isAuthorized(): Boolean = communityHelper.isAuthorized()

	override suspend fun getUsername(): String = communityHelper.getUsername()

	override suspend fun logout() = communityHelper.logout()

	override val capabilities: SourceCommunityCapabilities
		get() = communityHelper.capabilities

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = communityHelper.getMangaCommunityInfo(manga, chapter)

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = communityHelper.submitRating(manga, score, chapter)

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = communityHelper.getComments(manga, chapter, page, cursor)

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage = communityHelper.getReplies(manga, comment, chapter, page, cursor)

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment = communityHelper.postComment(manga, text, chapter, parentComment)

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment = communityHelper.editComment(manga, comment, newText, chapter)

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) = communityHelper.deleteComment(manga, comment, chapter)

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment = communityHelper.toggleCommentLike(manga, comment, chapter)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA),
	)

	private fun fetchAvailableTags(): Set<MangaTag> {
		return setOf(
			MangaTag("shounen", "Shounen", source),
			MangaTag("seinen", "Seinen", source),
			MangaTag("shoujo", "Shoujo", source),
			MangaTag("josei", "Josei", source),
			MangaTag("aile", "Aile", source),
			MangaTag("aksiyon", "Aksiyon", source),
			MangaTag("askeri", "Askeri", source),
			MangaTag("bilim-kurgu", "Bilim Kurgu", source),
			MangaTag("dogaustu", "Doğaüstü", source),
			MangaTag("dovus-sanatlari", "Dövüş Sanatları", source),
			MangaTag("dram", "Dram", source),
			MangaTag("ecchi", "Ecchi", source),
			MangaTag("fantezi", "Fantezi", source),
			MangaTag("gerilim", "Gerilim", source),
			MangaTag("gizem", "Gizem", source),
			MangaTag("gunluk-hayat", "Günlük Hayat", source),
			MangaTag("harem", "Harem", source),
			MangaTag("hayatta-kalma", "Hayatta Kalma", source),
			MangaTag("isekai", "Isekai", source),
			MangaTag("kiyamet", "Kıyamet", source),
			MangaTag("komedi", "Komedi", source),
			MangaTag("korku", "Korku", source),
			MangaTag("macera", "Macera", source),
			MangaTag("murim", "Murim", source),
			MangaTag("okul-hayati", "Okul Hayatı", source),
			MangaTag("oyun", "Oyun", source),
			MangaTag("psikolojik", "Psikolojik", source),
			MangaTag("reenkarnasyon", "Reenkarnasyon", source),
			MangaTag("romantizm", "Romantizm", source),
			MangaTag("sihir", "Sihir", source),
			MangaTag("sistem", "Sistem", source),
			MangaTag("spor", "Spor", source),
			MangaTag("suc", "Suç", source),
			MangaTag("super-kahraman", "Süper Kahraman", source),
			MangaTag("tarihi", "Tarihi", source),
			MangaTag("vampir", "Vampir", source),
			MangaTag("zamanda-yolculuk", "Zamanda Yolculuk", source),
			MangaTag("zindan", "Zindan", source),
			MangaTag("zombi", "Zombi", source),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tur?sayfa=")
			append(page)

			if (!filter.query.isNullOrEmpty()) {
				append("&q=")
				append(filter.query.urlEncoded())
			}

			append("&sira=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.UPDATED -> "updated"
					SortOrder.NEWEST -> "newest"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "az"
					else -> "popular"
				},
			)

			for (state in filter.states) {
				val s = when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					else -> null
				}
				if (s != null) {
					append("&durum=")
					append(s)
					break
				}
			}

			for (type in filter.types) {
				val t = when (type) {
					ContentType.MANHWA -> "webtoon"
					ContentType.MANGA -> "manga"
					else -> null
				}
				if (t != null) {
					append("&tip=")
					append(t)
					break
				}
			}

			for (tag in filter.tags) {
				append("&k[]=")
				append(tag.key.urlEncoded())
			}
		}

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("a.series-card").mapNotNull { a ->
			val href = a.attrAsRelativeUrlOrNull("href")?.let { "/" + it.removePrefix("/") } ?: return@mapNotNull null
			val title = a.selectFirst("h3.series-card-title")?.text().orEmpty()
			if (title.isBlank()) return@mapNotNull null
			val coverUrl = a.selectFirst("img")?.src()?.toAbsoluteUrl(domain)
			val author = a.selectFirst(".series-card-author")?.textOrNull()?.takeUnless { it == "Bilinmiyor" }
			val statusText = a.selectFirst(".cover-badge")?.text().orEmpty()
			val state = when {
				statusText.contains("Tamamlandı", ignoreCase = true) -> MangaState.FINISHED
				statusText.contains("Devam", ignoreCase = true) -> MangaState.ONGOING
				statusText.contains("Ara", ignoreCase = true) -> MangaState.PAUSED
				else -> null
			}

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
				state = state,
				authors = setOfNotNull(author),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val statusText = doc.selectFirst(".series-status")?.text().orEmpty()
		val altTitleText = doc.selectFirst(".series-detail-alt-title")?.textOrNull()
		val authorText = doc.selectFirst(".series-detail-author")?.textOrNull()?.takeUnless { it == "Bilinmiyor" }
		val ratingText = doc.selectFirst(".series-stat--rating strong")?.textOrNull()
		val rating = ratingText?.toFloatOrNull()?.let { (it / 5f).coerceIn(0f, 1f) } ?: RATING_UNKNOWN

		val tags = doc.select(".series-detail-genres a.tag-link").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfterLast("/").nullIfEmpty() ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = a.text(),
				source = source,
			)
		}

		val chapters = doc.select("div.chapter-item").mapChapters(reversed = true) { i, item ->
			val a = item.selectFirst("a.chapter-item-link") ?: item.selectFirst("a")
			val href = a?.attrAsRelativeUrl("href")?.let { "/" + it.removePrefix("/") } ?: ""
			val number = item.attr("data-chapter-number").toFloatOrNull()
				?: item.attr("data-chapter-number-label").toFloatOrNull()
				?: (i + 1f)
			val name = item.selectFirst(".chapter-title")?.textOrNull()
			val dateText = item.selectFirst(".chapter-date")?.textOrNull()

			MangaChapter(
				id = generateUid(href.ifEmpty { "${manga.url}-$i" }),
				title = name ?: "Bölüm ${number.toChapterUrlPart()}",
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseDate(dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = doc.selectFirst("h1.series-detail-title")?.text() ?: manga.title,
			altTitles = altTitleText?.split(',')
				?.mapNotNull { it.trim().nullIfEmpty() }
				?.toSet()
				?: manga.altTitles,
			description = doc.selectFirst(".series-detail-desc-content")?.textOrNull() ?: manga.description,
			coverUrl = doc.selectFirst(".series-detail-cover img")?.src()?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			authors = setOfNotNull(authorText),
			rating = rating,
			tags = tags.ifEmpty { manga.tags },
			state = when {
				statusText.contains("Tamamlandı", ignoreCase = true) -> MangaState.FINISHED
				statusText.contains("Devam", ignoreCase = true) -> MangaState.ONGOING
				statusText.contains("Ara", ignoreCase = true) -> MangaState.PAUSED
				else -> manga.state
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = try {
			webClient.httpGet(fullUrl).parseHtml()
		} catch (_: Exception) {
			loadRenderedDocument(
				url = fullUrl,
				readySelector = "div.reader-pages img, .reader-pages .reader-page, img.reader-page, #readerPages img, .reader-page-frame img, [data-page] img",
			)
		}

		val imgElements = doc.select("div.reader-pages img, .reader-pages .reader-page, img.reader-page, #readerPages img")
			.ifEmpty { doc.select(".reader-page-frame img, [data-page] img") }

		return imgElements.mapNotNull { img ->
			val src = img.src() ?: img.attrOrNull("data-original-src") ?: img.attrOrNull("data-src")
			val url = src?.nullIfEmpty()?.toAbsoluteUrl(domain) ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun loadRenderedDocument(url: String, readySelector: String): Document {
		val script = """
			(() => {
				const root = document.documentElement;
				const html = (root && root.outerHTML) || '';
				const text = ((document.body && document.body.innerText) || (root && root.innerText) || '');
				const lower = (document.title + '\n' + text + '\n' + html).toLowerCase();
				const challengeDetected = () => {
					return document.querySelector('script[src*="challenge-platform"]') !== null ||
						document.querySelector('script[src*="turnstile"]') !== null ||
					document.querySelector('iframe[src*="challenges.cloudflare.com"]') !== null ||
					document.querySelector('[src*="challenges.cloudflare.com"]') !== null ||
					document.querySelector('.cf-turnstile') !== null ||
					document.querySelector('input[name="cf-turnstile-response"]') !== null ||
					document.getElementById('challenge-error-title') !== null ||
					document.getElementById('challenge-error-text') !== null ||
					document.querySelector('form[action*="__cf_chl"]') !== null ||
					document.querySelector('.cf-browser-verification') !== null ||
					((lower.includes('just a moment') || lower.includes('checking your browser')) && lower.includes('cloudflare')) ||
					lower.includes('private access token challenge') ||
					lower.includes('challenge-platform') ||
					lower.includes('challenges.cloudflare.com') ||
					lower.includes('cf-turnstile') ||
					lower.includes('turnstile') ||
					lower.includes('cf-chl-opt');
				};
				if (challengeDetected()) {
					return "CLOUDFLARE_BLOCKED";
				}
				if (document.querySelector('$readySelector') !== null) {
					return html;
				}
				return null;
			})();
		""".trimIndent()
		val rawResult = context.evaluateJs(url, script, 30000L).orEmpty().decodeWebViewString()
		if (rawResult == CLOUDFLARE_BLOCKED || isCloudflareChallengePage(rawResult)) {
			requestCloudflareVerification(url)
		}
		if (rawResult.isBlank()) {
			throw ParseException("OkuToon reader page did not load", url)
		}
		return Jsoup.parse(rawResult, url)
	}

	private fun requestCloudflareVerification(url: String): Nothing {
		try {
			context.requestCloudflareVerification(this, url)
		} catch (e: UnsupportedOperationException) {
			throw ParseException("Cloudflare verification is required", url, e)
		}
	}

	private fun isCloudflareChallengePage(html: String): Boolean {
		if (html.isBlank()) return false
		val lower = html.lowercase(Locale.US)
		return lower.contains("<title>access denied") ||
			lower.contains("<title>just a moment") ||
			((lower.contains("just a moment") || lower.contains("checking your browser")) && lower.contains("cloudflare")) ||
			lower.contains("cf-browser-verification") ||
			lower.contains("cf-chl-opt") ||
			lower.contains("/cdn-cgi/challenge-platform/") ||
			lower.contains("challenges.cloudflare.com") ||
			lower.contains("cf-turnstile") ||
			lower.contains("turnstile")
	}

		private fun String.decodeWebViewString(): String {
		if (length < 2 || first() != '"' || last() != '"') {
			return this
		}
		return substring(1, lastIndex)
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")
			.replace("\\/", "/")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\t", "\t")
			.replace(unicodeEscapeRegex) { match ->
				match.groupValues[1].toInt(16).toChar().toString()
			}
	}

	private companion object {
		const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
		val unicodeEscapeRegex = Regex("""\\u([0-9A-Fa-f]{4})""")
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val fullUrl = seed.url.toAbsoluteUrl(domain)
		val doc = try {
			webClient.httpGet(fullUrl).parseHtml()
		} catch (_: Exception) {
			return emptyList()
		}
		return doc.select("section.series-similar a.series-similar-card").mapNotNull { a ->
			val href = a.attrAsRelativeUrlOrNull("href")?.let { "/" + it.removePrefix("/") } ?: return@mapNotNull null
			val title = a.selectFirst(".series-similar-title")?.text()
				?: a.selectFirst("img")?.attr("alt")
				?: return@mapNotNull null
			val coverUrl = a.selectFirst("img")?.src()?.toAbsoluteUrl(domain)
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

	private fun parseDate(dateStr: String?): Long {
		if (dateStr.isNullOrBlank()) return 0L
		val trimmed = dateStr.trim().lowercase(Locale("tr"))

		val relativeMatch = Regex("""^(\d+)\s*(saniye|dakika|saat|gün|hafta|ay|yıl)\s*önce""").find(trimmed)
		if (relativeMatch != null) {
			val amount = relativeMatch.groupValues[1].toIntOrNull() ?: return 0L
			val unit = relativeMatch.groupValues[2]
			val calendar = Calendar.getInstance()
			when (unit) {
				"saniye" -> calendar.add(Calendar.SECOND, -amount)
				"dakika" -> calendar.add(Calendar.MINUTE, -amount)
				"saat" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
				"gün" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
				"hafta" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
				"ay" -> calendar.add(Calendar.MONTH, -amount)
				"yıl" -> calendar.add(Calendar.YEAR, -amount)
			}
			return calendar.timeInMillis
		}

		val format = SimpleDateFormat("d MMMM yyyy", Locale("tr"))
		return format.parseSafe(dateStr)
	}

	private fun Float.toChapterUrlPart(): String {
		return if (this % 1f == 0f) {
			toInt().toString()
		} else {
			toString().trimEnd('0').trimEnd('.')
		}
	}
}
