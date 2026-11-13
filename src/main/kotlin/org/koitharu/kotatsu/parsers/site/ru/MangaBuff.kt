package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("MANGABUFF", "MangaBuff", "ru")
internal class MangaBuff(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGABUFF, pageSize = 24),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("mangabuff.ru")

	override val authUrl: String
		get() = "https://$domain/login"

	override val signUpUrl: String
		get() = "https://$domain/reg"

	override suspend fun isAuthorized(): Boolean {
		val cookies = context.cookieJar.getCookies(domain)
		if (cookies.any { it.name.startsWith("remember_web", ignoreCase = true) && it.value.isNotBlank() }) {
			return true
		}
		if (cookies.none { it.name.contains("session", ignoreCase = true) && it.value.isNotBlank() }) {
			return false
		}
		return runCatching {
			val doc = webClient.httpGet("https://$domain/").parseHtml()
			extractUsernameFromDoc(doc) != null
		}.getOrDefault(false)
	}

	override suspend fun getUsername(): String {
		val doc = runCatching { webClient.httpGet("https://$domain/").parseHtml() }
			.getOrElse { throw AuthRequiredException(source, it) }
		return extractUsernameFromDoc(doc) ?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatching {
			val token = getToken()
			val headers = Headers.Builder()
				.add("X-Requested-With", "XMLHttpRequest")
				.add("X-CSRF-TOKEN", token)
				.build()
			webClient.httpPost("https://$domain/logout".toHttpUrl(), mapOf("_token" to token), headers).close()
		}
		context.cookieJar.insertCookies(domain, "mangabuff_session=; Max-Age=0; Path=/")
		context.clearAuthValues(source)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = false,
		supportsReplies = true,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsChapterComments = false,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(SourceCommentSort.NEWEST),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isYearSupported = true,
		)

	@Volatile
	private var csrfToken: String? = null

	private val chapterDateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)
	private val stableGenres: Set<MangaTag> by lazy {
		STABLE_GENRES.mapTo(LinkedHashSet()) { (id, title) ->
			MangaTag(
				key = TAG_GENRE_PREFIX + id,
				title = title,
				source = source,
			)
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = stableGenres,
			availableStates = emptySet(),
			availableContentTypes = emptySet(),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query.orEmpty().trim()

		if (query.startsWith(SEARCH_PREFIX, ignoreCase = true)) {
			val slug = query.substringAfter(SEARCH_PREFIX).trim('/').trim()
			if (slug.isEmpty()) {
				return emptyList()
			}
			val url = "/manga/$slug"
			val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
			return listOf(parseDirectMangaCard(doc, url))
		}

		if (query.isNotEmpty()) {
			val url = "https://$domain/search".toHttpUrl().newBuilder().apply {
				addQueryParameter("q", query)
				if (page != 1) {
					addQueryParameter("page", page.toString())
				}
			}.build()
			return parseMangaList(webClient.httpGet(url).parseHtml())
		}

		val url = "https://$domain/manga".toHttpUrl().newBuilder().apply {
			filter.tags.forEach { tag ->
				val id = tag.key.removePrefix(TAG_GENRE_PREFIX)
				if (id.isNotBlank()) {
					addQueryParameter("genres[]", id)
				}
			}
			filter.tagsExclude.forEach { tag ->
				val id = tag.key.removePrefix(TAG_GENRE_PREFIX)
				if (id.isNotBlank()) {
					addQueryParameter("without_genres[]", id)
				}
			}
			if (filter.year != YEAR_UNKNOWN) {
				addQueryParameter("year[]", filter.year.toString())
			}
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.UPDATED -> "latest"
					else -> "latest"
				},
			)
			if (page != 1) {
				addQueryParameter("page", page.toString())
			}
		}.build()
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapterElements = ArrayList(doc.select(CHAPTER_SELECTOR))

		if (doc.selectFirst(".load-chapters-trigger") != null) {
			val mangaId = doc.selectFirst(".manga")?.attrOrNull("data-id")
			if (!mangaId.isNullOrBlank()) {
				chapterElements.addAll(loadMoreChapterElements(mangaId))
			}
		}

		val stateText = doc.select(".manga__middle-links > a:last-child, .manga-mobile__info > a:last-child").text()
		val altTitles = doc.select(".manga__name-alt > span, .manga-mobile__name-alt > span")
			.eachText()
			.filter { it.isNotBlank() }
			.toSet()

		val rating = doc.selectFirst(".manga__rating")
			?.text()
			?.replace(',', '.')
			?.toFloatOrNull()
			?.div(10f) ?: manga.rating

		return manga.copy(
			title = doc.selectFirst("h1, .manga__name, .manga-mobile__name")?.text().orEmpty().ifBlank { manga.title },
			altTitles = altTitles,
			description = buildDetailsDescription(doc),
			tags = collectTags(doc),
			state = parseStatus(stateText),
			coverUrl = doc.selectFirst(".manga__img img, img.manga-mobile__image")?.src() ?: manga.coverUrl,
			rating = rating,
			chapters = chapterElements.mapChapters(reversed = true) { i, e ->
				val chapterHref = e.attrAsAbsoluteUrlOrNull("href")
					?: e.attrOrNull("href")
					?: throw ParseException("Cannot find chapter href", manga.url.toAbsoluteUrl(domain))
				val chapterUrl = chapterHref.toRelativeUrl(domain)
				val title = e.select(".chapters__volume, .chapters__value, .chapters__name").text()
					.ifBlank { e.text() }
				val chapterNumber = CHAPTER_NUMBER_REGEX.find(title)
					?.groupValues
					?.firstOrNull()
					?.replace(',', '.')
					?.toFloatOrNull()
				MangaChapter(
					id = generateUid(chapterUrl),
					title = title,
					number = chapterNumber ?: (i + 1f),
					volume = 0,
					url = chapterUrl,
					uploadDate = chapterDateFormat.parseSafe(e.selectFirst(".chapters__add-date")?.text()),
					scanlator = null,
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select(".reader__pages img")
			.mapNotNull { img ->
				val pageUrl = img.attrAsAbsoluteUrlOrNull("data-src")
					?: img.attrAsAbsoluteUrlOrNull("src")
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(pageUrl),
					url = pageUrl.toRelativeUrl(domain),
					preview = null,
					source = source,
				)
			}
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(".cards .cards__item").mapNotNull { item ->
			val a = item.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val slug = href.removeSuffix("/").substringAfterLast('/')
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = item.selectFirst(".cards__name")?.text().orEmpty().ifBlank { a.text().ifBlank { slug } },
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				coverUrl = item.selectFirst("img")?.src() ?: "https://$domain/img/manga/posters/$slug.jpg",
				contentRating = null,
				source = source,
			)
		}
	}

	private fun parseDirectMangaCard(doc: Document, relativeUrl: String): Manga {
		val title = doc.selectFirst("h1, .manga__name, .manga-mobile__name")?.text().orEmpty()
		val cover = doc.selectFirst(".manga__img img, img.manga-mobile__image")?.src()
		val stateText = doc.select(".manga__middle-links > a:last-child, .manga-mobile__info > a:last-child").text()
		return Manga(
			id = generateUid(relativeUrl),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			title = title.ifBlank { relativeUrl.substringAfterLast('/') },
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			authors = emptySet(),
			state = parseStatus(stateText),
			coverUrl = cover,
			contentRating = null,
			source = source,
			description = doc.selectFirst(".manga__description")?.text(),
		)
	}

	private fun buildDetailsDescription(doc: Document): String {
		val lines = ArrayList<String>(6)
		doc.selectFirst(".manga__description")?.text()?.takeIf { it.isNotBlank() }?.let(lines::add)

		doc.selectFirst(".manga__rating")?.text()?.replace(',', '.')?.toDoubleOrNull()?.let {
			lines.add("Рейтинг: %.0f%%".format(Locale("ru"), it * 10))
		}
		doc.selectFirst(".manga__views")?.text()?.replace(" ", "")?.toIntOrNull()?.let {
			lines.add("Просмотров: %,d".format(Locale("ru"), it))
		}
		doc.selectFirst(".manga")?.attrOrNull("data-fav-count")?.toIntOrNull()?.let {
			lines.add("Избранное: %,d".format(Locale("ru"), it))
		}
		val alt = doc.select(".manga__name-alt > span, .manga-mobile__name-alt > span")
			.eachText()
			.filter { it.isNotBlank() }
		if (alt.isNotEmpty()) {
			lines.add("Альтернативные названия:")
			lines.addAll(alt.map { "• $it" })
		}
		return lines.joinToString(separator = "\n\n").ifBlank { "" }
	}

	private fun collectTags(doc: Document): Set<MangaTag> {
		val elements = doc.select(
			".manga__middle-links > a:not(:last-child), " +
				".manga-mobile__info > a:not(:last-child), " +
				".tags > .tags__item",
		)
		return elements.mapNotNullTo(LinkedHashSet()) { a ->
			val title = a.text().trim()
			if (title.isEmpty()) {
				return@mapNotNullTo null
			}
			val key = a.attrOrNull("href")
				?.removeSuffix("/")
				?.substringAfterLast('/')
				?.takeIf { it.isNotBlank() }
				?: title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	private fun parseStatus(value: String): MangaState? = when (value.lowercase(Locale.ROOT).trim()) {
		"завершен" -> MangaState.FINISHED
		"продолжается" -> MangaState.ONGOING
		"заморожен" -> MangaState.PAUSED
		"заброшен" -> MangaState.ABANDONED
		else -> null
	}

	private suspend fun loadMoreChapterElements(mangaId: String): List<Element> {
		fun headers(token: String): Headers = Headers.Builder()
			.add("X-Requested-With", "XMLHttpRequest")
			.add("X-CSRF-TOKEN", token)
			.build()

		val endpoint = "https://$domain/chapters/load".toHttpUrl()

		return try {
			val json = webClient.httpPost(endpoint, mapOf("manga_id" to mangaId), headers(getToken())).parseJson()
			Jsoup.parseBodyFragment(json.optString("content")).select(CHAPTER_SELECTOR)
		} catch (e: HttpStatusException) {
			if (e.statusCode == 419) {
				csrfToken = null
				val json = webClient.httpPost(endpoint, mapOf("manga_id" to mangaId), headers(getToken())).parseJson()
				Jsoup.parseBodyFragment(json.optString("content")).select(CHAPTER_SELECTOR)
			} else {
				throw e
			}
		}
	}

	private suspend fun getToken(): String {
		csrfToken?.let { return it }
		val url = "https://$domain/"
		val doc = webClient.httpGet(url).parseHtml()
		val token = doc.selectFirst("head meta[name*=csrf-token]")?.attr("content").orEmpty()
		if (token.isEmpty()) {
			throw ParseException("Unable to find CSRF token", url)
		}
		csrfToken = token
		return token
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = manga.url.toAbsoluteUrl(domain)
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		val rawRating = doc?.selectFirst("[itemprop=ratingValue], .manga__rating")
			?.let { (it.attrOrNull("content") ?: it.textOrNull())?.let(::parseFloatRating) }
		val ratingCount = doc?.selectFirst("[itemprop=ratingCount]")
			?.attrOrNull("content")
			?.toIntOrNull()
		val viewCount = doc?.selectFirst(".manga__views")
			?.textOrNull()
			?.filter { it.isDigit() }
			?.toLongOrNull()
		val followCount = doc?.selectFirst(".manga[data-fav-count]")
			?.attrOrNull("data-fav-count")
			?.toLongOrNull()
		val commentCount = doc?.selectFirst(".comments[data-count]")
			?.attrOrNull("data-count")
			?.toIntOrNull()
		val authorized = runCatching { isAuthorized() }.getOrDefault(false)
		val username = if (authorized) runCatching { getUsername() }.getOrNull() else null

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = commentCount,
			canCurrentUserComment = authorized,
			currentUsername = username,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
		if (page == 1 && chapter == null) {
			val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
			if (doc != null) {
				val total = doc.selectFirst(".comments[data-count]")?.attrOrNull("data-count")?.toIntOrNull()
				val items = doc.select(".comments__list > .comments__item").mapNotNull { el ->
					parseMangaBuffComment(el)
				}
				if (items.isNotEmpty()) {
					return SourceCommentPage(
						comments = items,
						page = 1,
						hasNextPage = false,
						totalCount = total,
						engine = SourceCommentEngine.NATIVE,
					)
				}
			}
		}
		val widget = resolveWidgetContext(manga, chapter)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = widget,
			engine = widget.engine,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
		val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		if (doc != null) {
			WidgetDetectors.detect(doc, pageUrl)?.let { return it }
		}
		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSTOM_WIDGET,
			pageUrl = pageUrl,
			widgetUrl = pageUrl,
			title = doc?.title()?.nullIfEmpty() ?: manga.title,
			baseUrl = pageUrl,
		)
	}

	private fun parseMangaBuffComment(el: Element, parentId: String? = null): SourceComment? {
		val id = el.id().removePrefix("comment_").nullIfEmpty() ?: return null
		val author = el.selectFirst(".comments__name")?.textOrNull() ?: "Аноним"
		val avatar = el.selectFirst(".comments__avatar img:not(.frame)")?.attrAsAbsoluteUrlOrNull("src")
		val dateText = el.selectFirst(".comments__date")?.textOrNull()
		val epochMs = el.attrOrNull("data-time")?.toLongOrNull()?.let { it * 1000L }
		val bodyEl = el.selectFirst("> .comments__inner .comments__body") ?: el.selectFirst(".comments__body")
		val body = bodyEl?.text()?.trim().orEmpty()
		val bodyHtml = bodyEl?.html()?.nullIfEmpty()
		val score = el.selectFirst("> .comments__inner .comments__rating-number")?.textOrNull()?.toIntOrNull() ?: 0
		val replies = el.select("> .comments__children > .comments__item, > .comments__replies > .comments__item")
			.mapNotNull { child -> parseMangaBuffComment(child, parentId = id) }

		return SourceComment(
			id = id,
			authorName = author,
			authorAvatarUrl = avatar,
			body = body,
			bodyHtml = bodyHtml,
			createdAtEpochMs = epochMs,
			createdAtText = dateText,
			likesCount = score.coerceAtLeast(0),
			dislikesCount = (-score).coerceAtLeast(0),
			repliesCount = replies.size,
			parentId = parentId,
			replies = replies,
		)
	}

	private fun extractUsernameFromDoc(doc: Document): String? {
		if (doc.selectFirst("a[href$=/login]") != null && doc.selectFirst(".header-profile, .header__profile") == null) {
			return null
		}
		return doc.selectFirst(".header-profile__name, .header__profile-name, .user-menu__name, .header__user-name")
			?.textOrNull()
			?: doc.selectFirst(".header-profile a[href*=/users/], .header__profile a[href*=/users/]")
				?.attrOrNull("title")
				?.nullIfEmpty()
			?: doc.selectFirst(".header-profile a[href*=/users/], .header__profile a[href*=/users/]")
				?.textOrNull()
	}

	private companion object {
		private const val SEARCH_PREFIX = "slug:"
		private const val CHAPTER_SELECTOR = "a.chapters__item"
		private const val TAG_GENRE_PREFIX = "g:"
		private val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
		private val STABLE_GENRES = listOf(
			"1" to "Арт",
			"4" to "Боевые искусства",
			"5" to "Вампиры",
			"6" to "Гарем",
			"7" to "Гендерная интрига",
			"8" to "Героическое фэнтези",
			"9" to "Детектив",
			"10" to "Дзёсэй",
			"11" to "Додзинси",
			"12" to "Драма",
			"39" to "Ёнкома",
			"18" to "Игра",
			"13" to "История",
			"21" to "Киберпанк",
			"40" to "Кодомо",
			"14" to "Комедия",
			"20" to "Махо-сёдзе",
			"15" to "Меха",
			"16" to "Мистика",
			"28" to "Мурим",
			"17" to "Научная фантастика",
			"19" to "Повседневность",
			"22" to "Постапокалиптика",
			"24" to "Приключения",
			"25" to "Психология",
			"26" to "Романтика",
			"30" to "Сверхъестественное",
			"31" to "Сёдзё",
			"29" to "Сёнэн",
			"32" to "Спорт",
			"33" to "Сэйнэн",
			"23" to "Трагедия",
			"34" to "Триллер",
			"35" to "Ужасы",
			"27" to "Фантастика",
			"36" to "Фэнтези",
			"3" to "Школьная жизнь",
			"2" to "Экшен",
			"37" to "Эротика",
			"38" to "Этти",
		)
	}
}
