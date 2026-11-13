package org.koitharu.kotatsu.parsers.site.madara

import androidx.collection.scatterSetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.MadaraCommunityHelper
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLDecoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

internal abstract class MadaraParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 12,
) : PagedMangaParser(context, source, pageSize), MangaParserAuthProvider, MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// Change these values only if the site does not support manga listings via ajax
	protected open val withoutAjax = false
	protected open val authorSearchSupported = false

	// For sites that mix manga with novels: lists only the titles whose chapters are images (ajax listings only)
	protected open val mangaChaptersOnly = false

	override val availableSortOrders: Set<SortOrder> by lazy(::setupAvailableSortOrders)

	private fun setupAvailableSortOrders(): Set<SortOrder> {
		return if (!withoutAjax) {
			EnumSet.of(
				SortOrder.UPDATED,
				SortOrder.UPDATED_ASC,
				SortOrder.POPULARITY,
				SortOrder.POPULARITY_ASC,
				SortOrder.NEWEST,
				SortOrder.NEWEST_ASC,
				SortOrder.ALPHABETICAL,
				SortOrder.ALPHABETICAL_DESC,
				SortOrder.RATING,
				SortOrder.RATING_ASC,
				SortOrder.RELEVANCE,
			)
		} else {
			EnumSet.of(
				SortOrder.UPDATED,
				SortOrder.POPULARITY,
				SortOrder.NEWEST,
				SortOrder.ALPHABETICAL,
				SortOrder.RATING,
				SortOrder.RELEVANCE,
			)
		}
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = !withoutAjax,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
			isAuthorSearchSupported = authorSearchSupported,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
	)

	override val authUrl: String
		get() = "https://${domain}"

	override val signUpUrl: String?
		get() = "https://${domain}/?action=register"

	override suspend fun isAuthorized(): Boolean {
		return getWordPressLoggedInCookies().isNotEmpty()
	}

	override suspend fun getUsername(): String {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val domUsername = runCatching {
			webClient.httpGet("https://${domain}/").parseHtml().body().selectFirst(
				".c-user_name, .user-name, #user-name, .wp-admin-bar-my-account .display-name, .c-modal_item .user-profile-name",
			)?.textOrNull()
		}.getOrNull()
		if (!domUsername.isNullOrBlank()) {
			return domUsername
		}
		val cookieUsername = getWordPressLoggedInCookies()
			.firstNotNullOfOrNull { cookie ->
				runCatching {
					URLDecoder.decode(cookie.value, Charsets.UTF_8.name())
						.substringBefore('|')
						.trim()
						.takeIf { it.isNotEmpty() }
				}.getOrNull()
			}
		return cookieUsername ?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		// Cookies are cleared by the host cookie manager on domain clear; also clear stored auth values.
		context.clearAuthValues(source)
	}

	private fun getWordPressLoggedInCookies(): List<okhttp3.Cookie> {
		val baseDomain = domain.trim()
		val alternateDomain = if (baseDomain.startsWith("www.", ignoreCase = true)) {
			baseDomain.substring(4)
		} else {
			"www.$baseDomain"
		}
		val cookies = context.cookieJar.getCookies(baseDomain) + context.cookieJar.getCookies(alternateDomain)
		return cookies.filter { cookie ->
			cookie.name.contains("wordpress_logged_in", ignoreCase = true) &&
				cookie.value.isNotBlank() &&
				!cookie.value.equals("deleted", ignoreCase = true)
		}
	}

	protected val communityHelper: MadaraCommunityHelper by lazy {
		MadaraCommunityHelper(context, webClient, source, ::domain, ::getRequestHeaders)
	}

	open override val capabilities: SourceCommunityCapabilities
		get() = communityHelper.baseCapabilities

	open override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = communityHelper.getMangaCommunityInfo(manga, chapter)

	open override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = communityHelper.submitRating(manga, score, chapter)

	open override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = communityHelper.getComments(manga, chapter, page, sort, cursor)

	open override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage = super.getReplies(manga, comment, chapter, page, cursor)

	open override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment = communityHelper.postComment(manga, text, chapter, parentComment, isSpoiler)

	open override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment = communityHelper.editComment(manga, comment, newText, chapter)

	open override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) = communityHelper.deleteComment(manga, comment, chapter)

	open override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment = communityHelper.toggleCommentLike(manga, comment, isLike, chapter)

	open override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? = communityHelper.resolveWidgetContext(manga, chapter)

	protected open val tagPrefix = "manga-genre/"
	protected open val datePattern = "MMMM d, yyyy"
	protected open val stylePage = "?style=list"
	protected open val postReq = false

	init {
		paginator.firstPage = 0
		searchPaginator.firstPage = 0
	}

	protected fun Element.tableValue(): Element {
		for (p in parents()) {
			val children = p.children()
			if (children.size == 2) {
				return children[1]
			}
		}
		parseFailed("Cannot find tableValue for node ${text()}")
	}

	@JvmField
	protected val ongoing = scatterSetOf(
		"مستمرة",
		"en curso",
		"ongoing",
		"on going",
        "OnGoing",
		"ativo",
		"en cours",
		"en cours \uD83D\uDFE2",
		"en cours de publication",
		"activo",
		"đang tiến hành",
		"em lançamento",
		"онгоінг",
		"publishing",
		"devam ediyor",
		"em andamento",
		"in corso",
		"güncel",
		"berjalan",
		"продолжается",
		"updating",
		"lançando",
		"in arrivo",
		"emision",
		"en emision",
		"مستمر",
		"curso",
		"en marcha",
		"publicandose",
		"publicando",
		"连载中",
	)

	@JvmField
	protected val finished = scatterSetOf(
		"completed",
		"complete",
		"completo",
		"complété",
		"fini",
		"achevé",
		"terminé",
		"terminé ⚫",
		"tamamlandı",
		"đã hoàn thành",
		"hoàn thành",
		"مكتملة",
		"завершено",
		"завершен",
		"finished",
		"finalizado",
		"completata",
		"one-shot",
		"bitti",
		"tamat",
		"completado",
		"concluído",
		"concluido",
		"已完结",
		"bitmiş",
		"end",
		"منتهية",
	)

	@JvmField
	protected val abandoned = scatterSetOf(
		"canceled",
		"cancelled",
		"cancelado",
		"cancellato",
		"cancelados",
		"dropped",
		"discontinued",
		"abandonné",
	)

	@JvmField
	protected val paused = scatterSetOf(
		"hiatus",
		"on hold",
		"pausado",
		"en espera",
		"en pause",
		"en attente",
	)

	@JvmField
	protected val upcoming = scatterSetOf(
		"upcoming",
		"لم تُنشَر بعد",
		"prochainement",
		"à venir",
	)

	// can be changed to retrieve tags see getTags
	protected open val listUrl = "manga/"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (withoutAjax) {
			val pages = page + 1

			val url = buildString {
				append("https://")
				append(domain)

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

				// Known bug: in some cases, if there are no manga with the associated tags, the source returns the full list of manga
				if (filter.tags.isNotEmpty()) {
					filter.tags.forEach {
						append("&genre[]=")
						append(it.key)
					}
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

				if (!filter.author.isNullOrEmpty()) {
					filter.author.let {
						append("&author=")
						// should be like "minamida-usuke"
						append(it.lowercase().replace(" ", "-"))
					}
				}

				// Support artist
				//filter.artist?.let {
				//	append("&artist=")
				//	append(filter.artist)
				//}


				append("&m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> append("alphabet")
					SortOrder.RATING -> append("rating")
					SortOrder.RELEVANCE -> {}
					else -> {}
				}
			}
			return parseMangaList(webClient.httpGet(url).parseHtml())
		} else {

			val payload = createRequestTemplate()

			payload["page"] = page.toString()

			filter.query?.let { query ->
				val normalized = query.normalizeSearchQuery().ifEmpty { query.trim() }
				payload["vars[s]"] = normalized.urlEncoded()
			}

			if (filter.tags.isNotEmpty()) {
				payload["vars[tax_query][0][taxonomy]"] = "wp-manga-genre"
				payload["vars[tax_query][0][field]"] = "slug"
				filter.tags.forEachIndexed { i, it ->
					payload["vars[tax_query][0][terms][$i]"] = it.key
				}
				payload["vars[tax_query][0][operator]"] = "IN"
			}

			if (filter.tagsExclude.isNotEmpty()) {
				payload["vars[tax_query][1][taxonomy]"] = "wp-manga-genre"
				payload["vars[tax_query][1][field]"] = "slug"
				filter.tagsExclude.forEachIndexed { i, it ->
					payload["vars[tax_query][1][terms][$i]"] = it.key
				}
				payload["vars[tax_query][1][operator]"] = "NOT IN"
			}

			if (filter.year != 0) {
				payload["vars[tax_query][2][taxonomy]"] = "wp-manga-release"
				payload["vars[tax_query][2][field]"] = "slug"
				payload["vars[tax_query][2][terms][]"] = filter.year.toString()
			}

			// Support author
			//  filter.author.let {
			//	payload["vars[tax_query][3][taxonomy]"] = "wp-manga-author"
			//	payload["vars[tax_query][3][field]"] = "name"
			//	payload["vars[tax_query][3][terms][0]"] = filter.author
			//	payload["vars[tax_query][3][operator]"] = "IN"
			//}


			// Support artist
			//  filter.artist.let {
			//	payload["vars[tax_query][4][taxonomy]"] = "wp-manga-artist"
			//	payload["vars[tax_query][4][field]"] = "name"
			//	payload["vars[tax_query][4][terms][0]"] = filter.artist
			//	payload["vars[tax_query][4][operator]"] = "IN"
			//}

			if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.year != 0) {
				payload["vars[tax_query][relation]"] = "AND"
			}

			when (order) {
				SortOrder.POPULARITY -> {
					payload["vars[meta_key]"] = "_wp_manga_views"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "desc"
				}

				SortOrder.POPULARITY_ASC -> {
					payload["vars[meta_key]"] = "_wp_manga_views"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "asc"
				}

				SortOrder.UPDATED -> {
					payload["vars[meta_key]"] = "_latest_update"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "desc"
				}

				SortOrder.UPDATED_ASC -> {
					payload["vars[meta_key]"] = "_latest_update"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "asc"
				}

				SortOrder.NEWEST -> {
					payload["vars[orderby]"] = "date"
					payload["vars[order]"] = "desc"
				}

				SortOrder.NEWEST_ASC -> {
					payload["vars[orderby]"] = "date"
					payload["vars[order]"] = "asc"
				}

				SortOrder.ALPHABETICAL -> {
					payload["vars[orderby]"] = "post_title"
					payload["vars[order]"] = "asc"
				}

				SortOrder.ALPHABETICAL_DESC -> {
					payload["vars[orderby]"] = "post_title"
					payload["vars[order]"] = "desc"
				}

				SortOrder.RATING -> {
					payload["vars[meta_query][0][query_avarage_reviews][key]"] = "_manga_avarage_reviews"
					payload["vars[meta_query][0][query_total_reviews][key]"] = "_manga_total_votes"

					payload["vars[orderby][query_avarage_reviews]"] = "DESC"
					payload["vars[orderby][query_total_reviews]"] = "DESC"
				}

				SortOrder.RATING_ASC -> {
					payload["vars[meta_query][0][query_avarage_reviews][key]"] = "_manga_avarage_reviews"
					payload["vars[meta_query][0][query_total_reviews][key]"] = "_manga_total_votes"

					payload["vars[orderby][query_avarage_reviews]"] = "ASC"
					payload["vars[orderby][query_total_reviews]"] = "ASC"
				}

				SortOrder.RELEVANCE -> {
					payload["vars[orderby]"] = ""
				}

				else -> payload["vars[orderby]"] = ""
			}

			filter.states.forEach {
				payload["vars[meta_query][0][0][key]"] = "_wp_manga_status"
				payload["vars[meta_query][0][0][compare]"] = "IN"
				payload["vars[meta_query][0][0][value][]"] =
					when (it) {
						MangaState.ONGOING -> "on-going"
						MangaState.FINISHED -> "end"
						MangaState.ABANDONED -> "canceled"
						MangaState.PAUSED -> "on-hold"
						MangaState.UPCOMING -> "upcoming"
						else -> throw IllegalArgumentException("$it not supported")
					}
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				payload["vars[meta_query][0][1][key]"] = "manga_adult_content"
				payload["vars[meta_query][0][1][value]"] =
					when (it) {
						ContentRating.SAFE -> ""
						ContentRating.ADULT -> "a%3A1%3A%7Bi%3A0%3Bs%3A3%3A%22yes%22%3B%7D"
						else -> ""
					}
			}

			if (mangaChaptersOnly) {
				payload["vars[meta_query][0][2][key]"] = "_wp_manga_chapter_type"
				payload["vars[meta_query][0][2][value]"] = "manga"
			}

			return parseMangaList(
				webClient.httpPost(
					"https://$domain/wp-admin/admin-ajax.php",
					payload,
				).parseHtml(),
			)
		}
	}

	protected open fun parseMangaList(doc: Document): List<Manga> {
		val elements = doc.select("div.row.c-tabs-item__content").ifEmpty {
			doc.select("div.page-item-detail")
		}

		// Avoid "Content not found or removed" errors
		if (elements.isEmpty()) {
			return emptyList()
		}

		return elements.map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".item-summary")
			val author = summary?.selectFirst(".mg_author, .mg_artists")?.selectFirst("a")?.ownText()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
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
						key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
						title = a.text().ifEmpty { return@mapNotNullToSet null }.toTitleCase(),
						source = source,
					)
				}.orEmpty(),
				authors = setOfNotNull(author),
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

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val body = doc.body()
		val root1 = body.selectFirst("header")?.selectFirst("ul.second-menu")
		val root2 = body.selectFirst("div.genres_wrap")?.selectFirst("ul.list-unstyled")
		if (root1 == null && root2 == null) {
			doc.parseFailed("Root not found")
		}
		val list = root1?.select("li").orEmpty() + root2?.select("li").orEmpty()
		val keySet = HashSet<String>(list.size)
		return list.mapNotNullToSet { li ->
			val a = li.selectFirst("a") ?: return@mapNotNullToSet null
			val href = a.attr("href").removeSuffix('/').substringAfterLast(tagPrefix, "")
			if (href.isEmpty() || !keySet.add(href)) {
				return@mapNotNullToSet null
			}
			MangaTag(
				key = href,
				title = a.ownText().ifEmpty {
					a.selectFirst(".menu-image-title")?.textOrNull()
				}?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
				source = source,
			)
		}
	}

	protected open val selectDesc =
		"div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, div.post-content div.manga-summary, div.post-content div.desc, div.c-page__content div.summary__content"
	protected open val selectGenre = "div.genres-content a"
	protected open val selectTestAsync = "div.listing-chapters_wrap"
	protected open val selectState =
		"div.post-content_item:contains(Status), div.post-content_item:contains(Statut), " +
			"div.post-content_item:contains(État), div.post-content_item:contains(حالة العمل), div.post-content_item:contains(Estado), div.post-content_item:contains(สถานะ)," +
			"div.post-content_item:contains(Stato), div.post-content_item:contains(Durum), div.post-content_item:contains(Statüsü), div.post-content_item:contains(Статус)," +
			"div.post-content_item:contains(状态), div.post-content_item:contains(الحالة), div.post-content_item:contains(Tình trạng)"
	protected open val selectAlt =
		".post-content_item:contains(Alt) .summary-content, .post-content_item:contains(Nomes alternativos: ) .summary-content"

	protected open suspend fun createMangaTag(a: Element): MangaTag? {
		return MangaTag(
			key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
			title = a.text().toTitleCase(),
			source = source,
		)
	}

	protected open fun parseDetailRating(doc: Document): Float? {
		val rawText = doc.selectFirst("span#averagerate, .post-total-rating .score, .post-rating span#averagerate")
			?.textOrNull()
			?: doc.selectFirst("[itemprop=ratingValue]")?.let { el ->
				el.attrOrNull("content") ?: el.textOrNull()
			}
			?: return null
		val rawScore = RATING_NUMBER_REGEX.find(rawText)?.value?.replace(',', '.')?.toFloatOrNull() ?: return null
		return rawScore.takeIf { it > 0f && it <= 5f }?.div(5f)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val testCheckAsync = doc.select(selectTestAsync)
		val chaptersDeferred = if (testCheckAsync.isEmpty()) {
			async { loadChapters(href, doc) }
		} else {
			async { getChapters(manga, doc) }
		}

		val desc = doc.select(selectDesc).html()

		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")

		val state = stateDiv?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}

		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()

		manga.copy(
			title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = parseDetailRating(doc) ?: manga.rating,
			tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
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


	protected open val selectDate = "span.chapter-release-date i"
    protected open val selectChapter = "li.wp-manga-chapter, div.wp-manga-chapter"

	protected open suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val link = href + stylePage
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
			val name = a.selectFirst("p")?.text() ?: a.ownText()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = i + 1f,
				volume = 0,
				url = link,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	protected open val postDataReq = "action=manga_get_chapters&manga="

	protected open suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val doc = if (postReq) {
			val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
			val url = "https://$domain/wp-admin/admin-ajax.php"
			val postData = postDataReq + mangaId
			webClient.httpPost(url, postData).parseHtml()
		} else {
			val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/"
			webClient.httpPost(url, emptyMap()).parseHtml()
		}
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return doc.select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val link = href + stylePage
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
			val name = a.selectFirst("p")?.text() ?: a.ownText()
			MangaChapter(
				id = generateUid(href),
				url = link,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				scanlator = null,
				source = source,
			)
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.body().selectFirstOrThrow(".related-manga")
		return root.select("div.related-reading-wrap").mapNotNull { div ->
			val a = div.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(a.host ?: domain),
				altTitles = emptySet(),
				title = div.selectFirstOrThrow(".widget-title").text(),
				authors = emptySet(),
				coverUrl = div.selectFirst("img")?.src(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	protected open val selectBodyPage = "div.main-col-inner div.reading-content"
	protected open val selectPage = "div.page-break"
	protected open val selectRequiredLogin = ".content-blocked, .login-required"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chapterProtector = doc.getElementById("chapter-protector-data")
		if (chapterProtector == null) {
			throw if (doc.selectFirst(selectRequiredLogin) != null) {
				AuthRequiredException(source)
			} else {
				val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
					"No image found, try to log in",
					fullUrl,
				)
				val rawEntries = ArrayList<ExtractedPage>()
				val filteredEntries = ArrayList<ExtractedPage>()
				for (div in root.select(selectPage)) {
					val containerIgnored = isIgnoredPageContainer(div)
					for (img in div.select("img")) {
						val rawUrl = img.src()?.trim()?.nullIfEmpty() ?: continue
						if (isPlaceholderScheme(rawUrl)) continue
						val relativeUrl = rawUrl.toRelativeUrl(domain)
						val page = MangaPage(
							id = generateUid(relativeUrl),
							url = relativeUrl,
							preview = null,
							source = source,
						)
						val entry = ExtractedPage(page, rawUrl)
						rawEntries.add(entry)
						if (!containerIgnored && !isInvalidPageImage(img, rawUrl, relativeUrl)) {
							filteredEntries.add(entry)
						}
					}
				}
				if (rawEntries.isEmpty()) {
					throw ParseException("No image found", fullUrl)
				}
				val cleanedEntries = if (filteredEntries.isNotEmpty()) {
					filterTrailingPromoEntries(filteredEntries)
				} else {
					rawEntries
				}
				return cleanedEntries.map { it.page }
			}
		} else {

			val chapterProtectorHtml = chapterProtector.attr("src")
				.takeIf { it.startsWith("data:text/javascript;base64,") }
				?.substringAfter("data:text/javascript;base64,")
				?.let {
					Base64.getDecoder().decode(it).decodeToString()
				}
				?: chapterProtector.html()

			val password = chapterProtectorHtml.substringAfter("wpmangaprotectornonce='").substringBefore("';")
			val chapterData = JSONObject(
				chapterProtectorHtml.substringAfter("chapter_data='").substringBefore("';").replace("\\/", "/"),
			)
			val unsaltedCiphertext = context.decodeBase64(chapterData.getString("ct"))
			val salt = chapterData.getString("s").toString().decodeHex()
			val ciphertext = "Salted__".toByteArray(Charsets.UTF_8) + salt + unsaltedCiphertext

			val rawImgArray = CryptoAES(context).decrypt(context.encodeBase64(ciphertext), password)
			val imgArrayString = rawImgArray.filterNot { c -> c == '[' || c == ']' || c == '\\' || c == '"' }

			val rawPages = imgArrayString.split(",").mapNotNull { raw ->
				val url = raw.trim().nullIfEmpty() ?: return@mapNotNull null
				if (isPlaceholderScheme(url)) return@mapNotNull null
				val rel = url.toRelativeUrl(domain)
				MangaPage(
					id = generateUid(rel),
					url = rel,
					preview = null,
					source = source,
				)
			}
			val filtered = rawPages.filterNot { page ->
				isInvalidPageUrl(page.url, page.url)
			}
			return filtered.ifEmpty { rawPages }
		}
	}

	protected open fun isIgnoredPageContainer(div: Element): Boolean {
		val classAndId = buildList {
			addAll(div.classNames())
			div.id().nullIfEmpty()?.let(::add)
		}
		return classAndId.any { attr ->
			attr.lowercase(Locale.ROOT) in IGNORED_CONTAINER_CLASSES
		}
	}

	protected open fun isInvalidPageImage(img: Element, rawUrl: String, relativeUrl: String): Boolean {
		if (isPlaceholderScheme(rawUrl)) return true
		val width = img.attr("width").trim().toIntOrNull()
		val height = img.attr("height").trim().toIntOrNull()
		if ((width != null && width in 1..2) || (height != null && height in 1..2)) {
			return true
		}
		if (img.styleValueOrNull("width") == "1px" || img.styleValueOrNull("height") == "1px") {
			return true
		}
		val anchor = img.selectFirstParent("a[href]")
		if (anchor != null && isPromoAnchor(anchor)) {
			return true
		}
		return isInvalidPageUrl(rawUrl, relativeUrl)
	}

	protected open fun isInvalidPageUrl(rawUrl: String, relativeUrl: String): Boolean {
		if (isPlaceholderScheme(rawUrl)) return true
		val parsed = rawUrl.toHttpUrlOrNull()
		val host = parsed?.host?.lowercase(Locale.ROOT)
		if (host != null && AD_HOSTS.any { adHost -> host == adHost || host.endsWith(".$adHost") }) {
			return true
		}
		val path = (parsed?.encodedPath ?: rawUrl.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
		if ("/wp-content/themes/" in path || "/wp-content/plugins/" in path) {
			return true
		}
		val fileName = path.substringAfterLast('/')
		if (fileName.isEmpty() || fileName.endsWith(".svg")) {
			return true
		}
		val stem = fileName.substringBeforeLast('.')
		val tokens = stem.split(STEM_TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
		if (tokens.isEmpty() || tokens.all { it.all(Char::isDigit) }) {
			return false
		}
		return tokens.any { token ->
			token in PLACEHOLDER_STEM_TOKENS || token in PROMO_STEM_TOKENS
		}
	}

	protected open fun isTrailingPromoPage(
		rawUrl: String,
		relativeUrl: String,
		dominantDir: String?,
		leadingPagesCount: Int,
	): Boolean {
		if (leadingPagesCount < 2 || dominantDir == null) return false
		val parsed = rawUrl.toHttpUrlOrNull()
		val path = (parsed?.encodedPath ?: rawUrl.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
		val dir = directoryKey(parsed?.host, path)
		if (dir == dominantDir) return false
		val fileName = path.substringAfterLast('/')
		val stem = fileName.substringBeforeLast('.')
		if (WP_MEDIA_DATE_PATH_REGEX.containsMatchIn(path) && "/wp-manga/" in dominantDir && !stem.all(Char::isDigit)) {
			return true
		}
		val hasDigits = stem.any(Char::isDigit)
		if (!hasDigits && (fileName.endsWith(".gif") || TRAILING_PROMO_DIR_TOKENS.any { it in path })) {
			return true
		}
		return false
	}

	private fun filterTrailingPromoEntries(entries: List<ExtractedPage>): List<ExtractedPage> {
		if (entries.size < 3) return entries
		val dirCounts = HashMap<String, Int>()
		for (entry in entries) {
			val parsed = entry.rawUrl.toHttpUrlOrNull()
			val path = (parsed?.encodedPath ?: entry.rawUrl.substringBefore('?').substringBefore('#'))
				.lowercase(Locale.ROOT)
			val fileStem = path.substringAfterLast('/').substringBeforeLast('.')
			if (fileStem.any(Char::isDigit) || "/wp-manga/" in path) {
				val key = directoryKey(parsed?.host, path)
				dirCounts[key] = (dirCounts[key] ?: 0) + 1
			}
		}
		val dominantDir = dirCounts.entries
			.filter { it.value >= 2 }
			.maxByOrNull { it.value }
			?.key
			?: return entries
		var endExclusive = entries.size
		while (endExclusive > 1) {
			val candidate = entries[endExclusive - 1]
			if (isTrailingPromoPage(candidate.rawUrl, candidate.page.url, dominantDir, endExclusive - 1)) {
				endExclusive--
			} else {
				break
			}
		}
		return if (endExclusive == entries.size) entries else entries.subList(0, endExclusive)
	}

	private fun isPlaceholderScheme(url: String): Boolean =
		url.startsWith("data:", ignoreCase = true) ||
			url.startsWith("about:", ignoreCase = true) ||
			url.startsWith("javascript:", ignoreCase = true)

	private fun isPromoAnchor(anchor: Element): Boolean {
		val rel = anchor.attr("rel").lowercase(Locale.ROOT)
		if ("sponsored" in rel) return true
		val href = anchor.attr("href").trim().lowercase(Locale.ROOT)
		if (href.isEmpty() || href.startsWith("#")) return false
		if (IMAGE_EXTENSIONS.any { ext -> href.substringBefore('?').endsWith(ext) }) return false
		val host = anchor.attrAsAbsoluteUrlOrNull("href")?.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)
		if (host != null && (AD_HOSTS.any { host == it || host.endsWith(".$it") } || PROMO_LINK_HOSTS.any { host == it || host.endsWith(".$it") })) {
			return true
		}
		return "discord.gg/" in href || "discord.com/invite" in href
	}

	private fun directoryKey(host: String?, path: String): String {
		val parent = path.substringBeforeLast('/', "")
		return "${host?.lowercase(Locale.ROOT).orEmpty()}$parent"
	}

	private data class ExtractedPage(
		val page: MangaPage,
		val rawUrl: String,
	)

	protected fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {

			WordSet(
				" ago", "atrás", " hace", " publicado", " назад", " önce", " trước", "مضت",
				" h", " d", " días", " jour", " horas", " heure", " mins", " minutos", " minute", " mois",
			).endsWith(d) -> {
				parseRelativeDate(d)
			}

			WordSet("há ", "منذ", "il y a").startsWith(d) -> {
				parseRelativeDate(d)
			}

			WordSet("yesterday", "يوم واحد").startsWith(d) -> {
				Calendar.getInstance().apply {
					add(Calendar.DAY_OF_MONTH, -1) // yesterday
					set(Calendar.HOUR_OF_DAY, 0)
					set(Calendar.MINUTE, 0)
					set(Calendar.SECOND, 0)
					set(Calendar.MILLISECOND, 0)
				}.timeInMillis
			}

			WordSet("today").startsWith(d) -> {
				Calendar.getInstance().apply {
					set(Calendar.HOUR_OF_DAY, 0)
					set(Calendar.MINUTE, 0)
					set(Calendar.SECOND, 0)
					set(Calendar.MILLISECOND, 0)
				}.timeInMillis
			}

			WordSet("يومين").startsWith(d) -> {
				Calendar.getInstance().apply {
					add(Calendar.DAY_OF_MONTH, -2) // day before yesterday
					set(Calendar.HOUR_OF_DAY, 0)
					set(Calendar.MINUTE, 0)
					set(Calendar.SECOND, 0)
					set(Calendar.MILLISECOND, 0)
				}.timeInMillis
			}

			date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
				if (it.contains(Regex("""\d\D\D"""))) {
					it.replace(Regex("""\D"""), "")
				} else {
					it
				}
			}.let { dateFormat.parseSafe(it.joinToString(" ")) }

			else -> dateFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			WordSet("detik", "segundo", "second", "ثوان")
				.anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis

			WordSet("menit", "dakika", "min", "minute", "minutes", "minuto", "mins", "phút", "минут", "دقيقة")
				.anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis

			WordSet("jam", "saat", "heure", "hora", "horas", "hour", "hours", "h", "ساعات", "ساعة")
				.anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis

			WordSet("hari", "gün", "jour", "día", "dia", "day", "días", "days", "d", "день")
				.anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis

			WordSet("month", "months", "أشهر", "mois", "meses", "mes")
				.anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis

			WordSet("year")
				.anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis

			else -> 0
		}
	}

	private companion object {

		val RATING_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
		val STEM_TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
		val WP_MEDIA_DATE_PATH_REGEX = Regex("""/wp-content/uploads/\d{4}/\d{2}/""")

		val IGNORED_CONTAINER_CLASSES = setOf(
			"ad",
			"ads",
			"ad-banner",
			"ad-container",
			"advertisement",
			"adsbygoogle",
			"sponsor",
			"sponsored",
			"footer-banner",
			"chapter-footer",
			"reading-footer",
			"discord-banner",
			"patreon-banner",
			"donate-banner",
		)

		val AD_HOSTS = setOf(
			"doubleclick.net",
			"googlesyndication.com",
			"googleadservices.com",
			"googletagmanager.com",
			"google-analytics.com",
			"adnxs.com",
			"popads.net",
			"popcash.net",
			"exoclick.com",
			"juicyads.com",
			"trafficjunky.com",
			"trafficjunky.net",
			"mc.yandex.ru",
			"statcounter.com",
			"scorecardresearch.com",
			"histats.com",
			"amung.us",
		)

		val PROMO_LINK_HOSTS = setOf(
			"discord.gg",
			"discord.com",
			"discordapp.com",
			"patreon.com",
			"ko-fi.com",
			"buymeacoffee.com",
			"t.me",
		)

		val PLACEHOLDER_STEM_TOKENS = setOf(
			"placeholder",
			"loading",
			"loader",
			"spinner",
			"blank",
			"empty",
			"pixel",
			"spacer",
			"1x1",
			"transparent",
			"noimage",
			"nopage",
			"lazyload",
			"favicon",
		)

		val PROMO_STEM_TOKENS = setOf(
			"reklam",
			"banner",
			"discord",
			"patreon",
			"kofi",
			"paypal",
			"sponsor",
			"doubleclick",
			"adsense",
			"ads",
			"advert",
			"advertisement",
			"footer",
			"logo",
			"bagis",
			"destek",
			"duyuru",
		)

		val TRAILING_PROMO_DIR_TOKENS = arrayOf(
			"/banner",
			"/ads/",
			"/reklam/",
			"/footer",
			"/promo",
			"/assets/",
		)

		val IMAGE_EXTENSIONS = arrayOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif")

		private fun createRequestTemplate() =
			("action=madara_load_more&page=0&template=madara-core%2Fcontent%2Fcontent-search&vars%5Bs%5D=&vars%5Bpaged%5D=1&vars%5Btemplate%5D=search&vars%5Bmeta_query%5D%5B0%5D%5Brelation%5D=AND&vars%5Bmeta_query%5D%5Brelation%5D=AND&vars%5Bpost_type%5D=wp-manga&vars%5Bpost_status%5D=publish&vars%5Bmanga_archives_item_layout%5D=default").split(
				'&',
			).map {
				val pos = it.indexOf('=')
				it.substring(0, pos) to it.substring(pos + 1)
			}.toMutableMap()

		fun String.decodeHex(): ByteArray {
			check(length % 2 == 0) { "Must have an even length" }

			return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
		}
	}
}
