package org.koitharu.kotatsu.parsers.site.tr

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.site.community.extractBalancedJsonObjectAfter
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@MangaSourceParser("TRWEBTOON", "TrWebtoon", "tr")
internal class TrWebtoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TRWEBTOON, pageSize = 24),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("trmanga.com")

	override val authUrl: String
		get() = "https://$domain/panel/giris"

	override val signUpUrl: String?
		get() = "https://$domain/panel/kayit"

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = false,
		supportsCommentEditing = false,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = false,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
			SourceCommentSort.TOP,
		),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.addPathSegment("webtoon-listesi")
			.addQueryParameter("page", page.toString())
			.apply {
				filter.query?.trim()?.nullIfEmpty()?.let {
					addQueryParameter("q", it)
				}
				if (filter.tags.isNotEmpty()) {
					addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
				}
				if (filter.tagsExclude.isNotEmpty()) {
					addQueryParameter("haric", filter.tagsExclude.joinToString(",") { it.key })
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "continues"
						MangaState.FINISHED -> "complated"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty()) {
					addQueryParameter("f", "1")
				}
				val (sortKey, sortType) = when (order) {
					SortOrder.UPDATED -> "updated" to "DESC"
					SortOrder.POPULARITY -> "views" to "DESC"
					SortOrder.RATING -> "rating" to "DESC"
					SortOrder.NEWEST -> "released" to "DESC"
					SortOrder.NEWEST_ASC -> "released" to "ASC"
					SortOrder.ALPHABETICAL -> "name" to "ASC"
					SortOrder.ALPHABETICAL_DESC -> "name" to "DESC"
					else -> "views" to "DESC"
				}
				addQueryParameter("sort", sortKey)
				addQueryParameter("short_type", sortType)
			}
			.build()

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("main div.grid a.group[href^='/webtoon/']").map { a ->
			val href = a.attrAsRelativeUrl("href")
			val ratingValue = a.select("span")
				.lastOrNull { it.selectFirst("use[href$=#star]") != null }
				?.text()
				?.trim()
				?.toFloatOrNull()
				?.div(5f)
				?: RATING_UNKNOWN
			val isAdult = a.select("span.badge").any { it.text().contains("16+") }
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = a.selectFirst("img")?.src(),
				title = a.attrOrNull("title")
					?: a.selectFirst("span.line-clamp-2")?.textOrNull()
					?: a.parseFailed("Title not found"),
				altTitles = emptySet(),
				rating = ratingValue,
				tags = emptySet(),
				authors = emptySet(),
				state = parseState(a.selectFirst("span[data-cover-ov]")?.text()),
				source = source,
				contentRating = if (isAdult) ContentRating.ADULT else sourceContentRating,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/webtoon-listesi").parseHtml()
		return doc.requireElementById("wlGenres").select("button[data-slug]").mapNotNullToSet { btn ->
			val key = btn.attrOrNull("data-slug") ?: return@mapNotNullToSet null
			val title = btn.attrOrNull("data-name") ?: btn.textOrNull() ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		var altTitles = manga.altTitles
		var authors = manga.authors
		var state = manga.state
		for ((dt, dd) in doc.select("dl dt").zip(doc.select("dl dd"))) {
			when (dt.text().trim()) {
				"Diğer isimler" -> {
					altTitles = dd.text().split(',')
						.mapNotNullToSet { it.trim().nullIfEmpty() }
				}

				"Yazar" -> {
					val linked = dd.select("a").mapNotNullToSet { it.textOrNull() }
					authors = linked.ifEmpty {
						dd.text().split(',').mapNotNullToSet { it.trim().nullIfEmpty() }
					}
				}

				"Durum" -> {
					state = parseState(dd.text()) ?: state
				}
			}
		}

		val tags = doc.select("section.wt-hero a[href^='/tur/']").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			val title = a.textOrNull() ?: return@mapNotNullToSet null
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = title.toTitleCase(sourceLocale),
				source = source,
			)
		}

		val dotDateFormat = SimpleDateFormat("dd.MM.yyyy", sourceLocale)
		val slashDateFormat = SimpleDateFormat("dd/MM/yyyy", sourceLocale)

		return manga.copy(
			title = doc.selectFirst("h1#wt-title")?.textOrNull() ?: manga.title,
			coverUrl = doc.selectFirst("img.wt-cover")?.src() ?: manga.coverUrl,
			altTitles = altTitles,
			authors = authors,
			tags = tags,
			description = doc.selectFirst("#wtiDesc")?.html(),
			state = state,
			chapters = doc.select("ol#chapters > li.wt-ch").mapChapters(reversed = true) { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val url = a.attrAsRelativeUrl("href")
				val number = li.attrOrNull("data-chapter")?.toFloatOrNull() ?: (i + 1f)
				val chTitle = a.selectFirst(".wt-ch-t")?.textOrNull()
					?.takeUnless { it.equals("Bölüm", ignoreCase = true) }
				val metaEl = li.selectFirst(".wt-ch-m")
				val scanlator = metaEl?.selectFirst("a.fs-chip")?.ownText()?.trim()?.nullIfEmpty()
				val dateText = metaEl?.ownText()?.trim()
				MangaChapter(
					id = generateUid(url),
					title = chTitle,
					number = number,
					volume = 0,
					url = url,
					scanlator = scanlator,
					uploadDate = parseChapterDate(dotDateFormat, slashDateFormat, dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	private fun parseState(text: String?): MangaState? = when (text?.trim()) {
		"Devam Ediyor", "Güncel" -> MangaState.ONGOING
		"Tamamlandı" -> MangaState.FINISHED
		"Bırakıldı" -> MangaState.ABANDONED
		"Durduruldu", "Ara Verildi" -> MangaState.PAUSED
		else -> null
	}

	private fun parseChapterDate(dotFormat: DateFormat, slashFormat: DateFormat, date: String?): Long {
		val d = date?.trim()?.lowercase(sourceLocale) ?: return 0L
		if (d.isEmpty()) return 0L
		return when {
			d.startsWith("saat ") -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			d.endsWith(" önce") -> parseRelativeDate(d)

			else -> dotFormat.parseSafe(date).takeIf { it != 0L } ?: slashFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("saniye").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("dakika").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("saat").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("gün").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("hafta").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("ay").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("yıl").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val imgs = doc.getElementById("images")?.select("img.reader-img, img").orEmpty()
		if (imgs.isEmpty()) {
			if (doc.html().contains("\"locked\":true")) {
				throw AuthRequiredException(source)
			}
			doc.parseFailed("Images not found")
		}
		return imgs.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun isAuthorized(): Boolean {
		val loginUrl = authUrl
		return runCatching {
			val request = Request.Builder()
				.get()
				.url(loginUrl)
				.headers(getRequestHeaders())
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().use { response ->
				if (response.code == 401 || response.code == 403) return@use false
				val finalUrl = response.request.url.toString()
				val doc = Jsoup.parse(response.body.string(), finalUrl)
				val trm = extractTrmObject(doc)
				if (trm?.has("logged") == true) {
					return@use trm.getBooleanOrDefault("logged", false)
				}
				val redirectedAway = !finalUrl.contains("/panel/giris", ignoreCase = true) &&
					finalUrl.contains(domain, ignoreCase = true)
				val hasLoginForm = doc.selectFirst("form.js-login-form, input[name=password]") != null
				redirectedAway && !hasLoginForm
			}
		}.getOrDefault(false)
	}

	override suspend fun getUsername(): String {
		val doc = runCatching { webClient.httpGet("https://$domain/").parseHtml() }.getOrElse {
			throw AuthRequiredException(source, it)
		}
		val trm = extractTrmObject(doc)
		if (trm?.has("logged") == true && !trm.getBooleanOrDefault("logged", false)) {
			throw AuthRequiredException(source)
		}
		return extractUsernameFromDoc(doc) ?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatching {
			val homeDoc = webClient.httpGet("https://$domain/").parseHtml()
			val csrf = extractTrmObject(homeDoc)?.getStringOrNull("token")
			val body = FormBody.Builder().apply {
				if (csrf != null) add("token", csrf)
			}.build()
			val request = Request.Builder()
				.post(body)
				.url("https://$domain/api/user?action=logout")
				.headers(getRequestHeaders())
				.header("X-Requested-With", "XMLHttpRequest")
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().close()
		}
		context.clearAuthValues(source)
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val trm = extractTrmObject(doc)
		val logged = trm?.getBooleanOrDefault("logged", false) == true
		val webtoonObj = trm?.optJSONObject("page")?.optJSONObject("webtoon")

		val rawRating = doc.selectFirst("[data-rv2-avg]")?.textOrNull()?.let(::parseFloatRating)
		val averageRating = rawRating?.let { (it / 5f).coerceIn(0f, 1f) }
		val ratingCount = doc.selectFirst("[data-rv2-count]")?.textOrNull()?.let { parseCompactCount(it)?.toInt() }
			?: doc.selectFirst("[data-rv2-hero-sub]")?.textOrNull()
				?.substringBefore("oy")
				?.let { parseCompactCount(it)?.toInt() }
		val userRating = webtoonObj?.opt("vote")
			?.takeIf { it != JSONObject.NULL }
			?.toString()
			?.toFloatOrNull()
			?.roundToInt()

		var viewCount: Long? = null
		for (stat in doc.select(".wt-stats li.wt-stat")) {
			val label = stat.selectFirst(".wt-stat-l")?.text()?.trim()?.lowercase(sourceLocale) ?: continue
			if ("okunma" in label || "görüntülenme" in label) {
				viewCount = stat.selectFirst(".wt-stat-n")?.textOrNull()?.let(::parseCompactCount)
				break
			}
		}
		val followCount = doc.selectFirst("[data-bm-count]")?.let { el ->
			(el.attrOrNull("data-bm-count") ?: el.textOrNull())?.let(::parseCompactCount)
		}

		val section = doc.selectFirst("#comments-section")
		val targetType = section?.attrOrNull("data-target-type")
		val targetId = section?.attrOrNull("data-target-id")
		val userId = section?.attrOrNull("data-user-id")?.nullIfEmpty()

		val domCommentCount = doc.selectFirst("#comments-count")?.textOrNull()?.toIntOrNull()?.takeIf { it > 0 }
		val fetchedCommentCount = if (domCommentCount == null && targetType != null && targetId != null) {
			runCatching {
				fetchCommentsJson(
					targetType = targetType,
					targetId = targetId,
					page = 1,
					perPage = 1,
					order = "newest",
					referer = pageUrl,
				).optJSONObject("data")?.optJSONObject("pagination")?.opt("total_comments")?.toString()?.toIntOrNull()
			}.getOrNull()
		} else {
			domCommentCount
		}

		val canRate = logged && webtoonObj?.getStringOrNull("slug") != null
		val canComment = (logged || userId != null) && targetType != null && targetId != null

		return SourceMangaCommunityInfo(
			averageRating = averageRating,
			rawRating = rawRating,
			rawScale = 5,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = if (chapter == null) fetchedCommentCount else null,
			chapterCommentCount = if (chapter != null) fetchedCommentCount else null,
			canCurrentUserRate = canRate,
			canCurrentUserComment = canComment,
			currentUsername = if (logged) extractUsernameFromDoc(doc) else null,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val trm = extractTrmObject(doc)
		val logged = trm?.getBooleanOrDefault("logged", false) == true
		if (!logged) {
			throw AuthRequiredException(source)
		}
		val token = trm?.getStringOrNull("token")
			?: throw ParseException("CSRF token is missing", pageUrl)
		val slug = trm.optJSONObject("page")?.optJSONObject("webtoon")?.getStringOrNull("slug")
			?: pageUrl.trimEnd('/').substringAfterLast('/').nullIfEmpty()
			?: throw ParseException("Webtoon slug is missing", pageUrl)

		val clamped = score.coerceIn(1, 5)
		val body = FormBody.Builder()
			.add("slug", slug)
			.add("op", "set")
			.add("rating", clamped.toString())
			.add("token", token)
			.build()
		val endpoint = "https://$domain/api/main?action=ratingV2"
		val request = Request.Builder()
			.post(body)
			.url(endpoint)
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", pageUrl)
			.header("Origin", "https://$domain")
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val raw = response.body.string()
			if (!response.isSuccessful) {
				throw ParseException("Rating failed (HTTP ${response.code})", endpoint)
			}
			raw.toJSONObjectOrNull() ?: throw ParseException("Invalid rating response", endpoint)
		}

		val type = json.getStringOrNull("type")
		if (type != "success") {
			val msg = json.getStringOrNull("text") ?: json.getStringOrNull("message") ?: "Rating failed"
			val lower = msg.lowercase(sourceLocale)
			if ("giriş" in lower || "oturum" in lower || "login" in lower) {
				throw AuthRequiredException(source)
			}
			throw ParseException(msg, endpoint)
		}

		val updatedAvg = json.getStringOrNull("avg_label")?.let(::parseFloatRating)
			?: json.opt("avg")?.toString()?.let(::parseFloatRating)
		val updatedCount = json.opt("count")?.toString()?.let { parseCompactCount(it)?.toInt() }
		val updatedVote = json.opt("vote")?.toString()?.toFloatOrNull()?.roundToInt() ?: clamped

		val base = getMangaCommunityInfo(manga, chapter)
		val finalRaw = updatedAvg ?: base.rawRating
		return base.copy(
			rawRating = finalRaw,
			averageRating = finalRaw?.let { (it / 5f).coerceIn(0f, 1f) },
			userRating = updatedVote,
			ratingCount = updatedCount ?: base.ratingCount,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val section = doc.selectFirst("#comments-section")
		val targetType = section?.attrOrNull("data-target-type")
		val targetId = section?.attrOrNull("data-target-id")
		if (targetType == null || targetId == null) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}

		val currentUserId = section.attrOrNull("data-user-id")?.nullIfEmpty()
		val trmLogged = extractTrmObject(doc)?.getBooleanOrDefault("logged", false) == true
		val isAuth = trmLogged || currentUserId != null
		val orderParam = when (sort) {
			SourceCommentSort.TOP -> "best"
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.NEWEST -> "newest"
		}

		val json = fetchCommentsJson(
			targetType = targetType,
			targetId = targetId,
			page = page.coerceAtLeast(1),
			perPage = COMMENTS_PER_PAGE,
			order = orderParam,
			referer = pageUrl,
		)
		val data = json.optJSONObject("data") ?: JSONObject()
		val commentsArray = data.optJSONArray("comments") ?: JSONArray()
		val comments = parseCommentsArray(commentsArray, currentUserId, isAuth)
		val pagination = data.optJSONObject("pagination")
		val total = pagination?.opt("total_comments")?.toString()?.toIntOrNull()
		val hasNext = pagination?.getBooleanOrDefault("has_next", false) ?: false

		return SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasNext,
			totalCount = total,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val section = doc.selectFirst("#comments-section")
		val targetType = section?.attrOrNull("data-target-type")
			?: throw ParseException("Comment target type not found", pageUrl)
		val targetId = section.attrOrNull("data-target-id")
			?: throw ParseException("Comment target ID not found", pageUrl)
		val currentUserId = section.attrOrNull("data-user-id")?.nullIfEmpty()
		val trmLogged = extractTrmObject(doc)?.getBooleanOrDefault("logged", false) == true
		if (!trmLogged && currentUserId == null) {
			throw AuthRequiredException(source)
		}

		val csrf = extractCommentCsrfToken(doc, pageUrl)
			?: throw ParseException("Comment CSRF token is missing", pageUrl)
		val content = if (isSpoiler && !text.contains("[spoiler]", ignoreCase = true)) {
			"[spoiler]$text[/spoiler]"
		} else {
			text
		}

		val form = FormBody.Builder()
			.add("action", "add")
			.add("target_type", targetType)
			.add("target_id", targetId)
			.add("content", content)
			.add("csrf_token", csrf)
			.apply {
				if (parentComment != null) {
					add("parent_id", parentComment.id)
				}
			}
			.build()

		val json = postCommentsApi(form, pageUrl)
		val dataObj = json.optJSONObject("data")
		val commentObj = dataObj?.optJSONObject("comment")
		if (commentObj != null) {
			return parseCommentObject(commentObj, currentUserId, isAuthenticated = true)
		}

		val msg = json.getStringOrNull("message")
		val isPending = msg?.contains("onay", ignoreCase = true) == true
		return SourceComment(
			id = "trm-${System.currentTimeMillis()}",
			authorName = extractUsernameFromDoc(doc) ?: "You",
			body = content,
			createdAtEpochMs = System.currentTimeMillis(),
			isSpoiler = isSpoiler,
			parentId = parentComment?.id,
			isOwnComment = true,
			canReply = true,
			canLike = true,
			canDelete = true,
			rawMeta = buildMap {
				if (isPending) put("held_for_review", "true")
				if (msg != null) put("message", msg)
			},
		)
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val currentUserId = doc.selectFirst("#comments-section")?.attrOrNull("data-user-id")?.nullIfEmpty()
		val trmLogged = extractTrmObject(doc)?.getBooleanOrDefault("logged", false) == true
		if (!trmLogged && currentUserId == null) {
			throw AuthRequiredException(source)
		}
		val csrf = extractCommentCsrfToken(doc, pageUrl)
			?: throw ParseException("Comment CSRF token is missing", pageUrl)

		val form = FormBody.Builder()
			.add("action", "delete")
			.add("comment_id", comment.id)
			.add("csrf_token", csrf)
			.build()
		postCommentsApi(form, pageUrl)
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val currentUserId = doc.selectFirst("#comments-section")?.attrOrNull("data-user-id")?.nullIfEmpty()
		val trmLogged = extractTrmObject(doc)?.getBooleanOrDefault("logged", false) == true
		if (!trmLogged && currentUserId == null) {
			throw AuthRequiredException(source)
		}
		val csrf = extractCommentCsrfToken(doc, pageUrl)
			?: throw ParseException("Comment CSRF token is missing", pageUrl)

		val form = FormBody.Builder()
			.add("action", "like")
			.add("comment_id", comment.id)
			.add("csrf_token", csrf)
			.build()
		val json = postCommentsApi(form, pageUrl)
		val liked = json.getBooleanOrDefault("liked", comment.userVote != 1)
		val likeCount = json.getIntOrDefault("like_count", comment.likesCount).coerceAtLeast(0)
		return comment.copy(
			likesCount = likeCount,
			userVote = if (liked) 1 else 0,
		)
	}

	private suspend fun fetchCommentsJson(
		targetType: String,
		targetId: String,
		page: Int,
		perPage: Int,
		order: String,
		referer: String,
	): JSONObject {
		val url = "https://$domain/api/comments.php".toHttpUrlOrNull()?.newBuilder()
			?.addQueryParameter("action", "list")
			?.addQueryParameter("target_type", targetType)
			?.addQueryParameter("target_id", targetId)
			?.addQueryParameter("page", page.toString())
			?.addQueryParameter("per_page", perPage.toString())
			?.addQueryParameter("order", order)
			?.build()
			?: throw ParseException("Invalid comments API URL", referer)

		val request = Request.Builder()
			.get()
			.url(url)
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", referer)
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw ParseException("Failed to load comments (HTTP ${response.code})", url.toString())
			}
			response.body.string().toJSONObjectOrNull()
				?: throw ParseException("Invalid comments JSON response", url.toString())
		}
		if (!json.getBooleanOrDefault("success", false)) {
			throw ParseException(json.getStringOrNull("message") ?: "Comments request failed", url.toString())
		}
		return json
	}

	private suspend fun postCommentsApi(form: FormBody, referer: String): JSONObject {
		val endpoint = "https://$domain/api/comments.php"
		val request = Request.Builder()
			.post(form)
			.url(endpoint)
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", referer)
			.header("Origin", "https://$domain")
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val raw = response.body.string()
			if (!response.isSuccessful) {
				throw ParseException("Comment request failed (HTTP ${response.code})", endpoint)
			}
			raw.toJSONObjectOrNull() ?: throw ParseException("Invalid comment response", endpoint)
		}
		if (!json.getBooleanOrDefault("success", false)) {
			val msg = json.getStringOrNull("message") ?: "Comment action failed"
			val redirect = json.getStringOrNull("redirect")
			val lower = msg.lowercase(sourceLocale)
			if (redirect?.contains("/panel/giris", ignoreCase = true) == true ||
				"giriş" in lower || "oturum" in lower || "login" in lower
			) {
				throw AuthRequiredException(source)
			}
			throw ParseException(msg, endpoint)
		}
		return json
	}

	private suspend fun extractCommentCsrfToken(doc: Document, referer: String): String? {
		doc.selectFirst("#comment-csrf-token, input[name=csrf_token]")?.attrOrNull("value")?.let { return it }
		val tokenUrl = "https://$domain/api/comments.php?action=get_token"
		val request = Request.Builder()
			.get()
			.url(tokenUrl)
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", referer)
			.tag(MangaSource::class.java, source)
			.build()
		return runCatching {
			context.httpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return@use null
				response.body.string().toJSONObjectOrNull()
					?.optJSONObject("data")
					?.getStringOrNull("csrf_token")
			}
		}.getOrNull()
	}

	private fun parseCommentsArray(
		array: JSONArray,
		currentUserId: String?,
		isAuthenticated: Boolean,
	): List<SourceComment> {
		val list = ArrayList<SourceComment>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			list.add(parseCommentObject(jo, currentUserId, isAuthenticated))
		}
		return list
	}

	private fun parseCommentObject(
		jo: JSONObject,
		currentUserId: String?,
		isAuthenticated: Boolean,
	): SourceComment {
		val id = jo.opt("id")?.toString()?.nullIfEmpty() ?: "0"
		val userId = jo.opt("user_id")?.toString()?.nullIfEmpty()
		val parentId = jo.opt("parent_id")?.toString()?.takeUnless { it == "0" || it == "null" }?.nullIfEmpty()
		val username = jo.getStringOrNull("username") ?: jo.getStringOrNull("user_name") ?: "Kullanıcı"
		val avatarUrl = jo.getStringOrNull("avatar_url")?.toAbsoluteUrl(domain)
		val roleName = jo.getStringOrNull("role_name")?.takeUnless { it.equals("Üye", ignoreCase = true) }
		val contentHtml = jo.getStringOrNull("content_formatted")
		val rawContent = jo.getStringOrNull("content")
		val body = contentHtml?.let { Jsoup.parseBodyFragment(it).text().nullIfEmpty() }
			?: rawContent
			?: ""
		val createdAt = jo.getStringOrNull("created_at")
		val timeAgo = jo.getStringOrNull("time_ago")
		val isSpoiler = jo.optInt("is_spoiler", 0) == 1 || jo.getBooleanOrDefault("is_spoiler", false)
		val isPinned = jo.optInt("pinned", 0) == 1 || jo.getBooleanOrDefault("pinned", false)
		val likeCount = jo.optInt("like_count", 0).coerceAtLeast(0)
		val userLiked = jo.getBooleanOrDefault("user_liked", false)
		val isOwn = jo.getBooleanOrDefault("is_own_comment", false) ||
			(currentUserId != null && userId == currentUserId)
		val repliesArray = jo.optJSONArray("replies")
		val replies = if (repliesArray != null && repliesArray.length() > 0) {
			parseCommentsArray(repliesArray, currentUserId, isAuthenticated)
		} else {
			emptyList()
		}
		val replyCount = maxOf(jo.optInt("reply_count", 0), replies.size)

		return SourceComment(
			id = id,
			authorName = username,
			authorAvatarUrl = avatarUrl,
			authorRole = roleName,
			body = body,
			bodyHtml = contentHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = timeAgo ?: createdAt,
			isSpoiler = isSpoiler,
			isPinned = isPinned,
			likesCount = likeCount,
			userVote = if (userLiked) 1 else 0,
			repliesCount = replyCount,
			parentId = parentId,
			replies = replies,
			isOwnComment = isOwn,
			canReply = isAuthenticated,
			canLike = isAuthenticated,
			canDelete = isAuthenticated && isOwn,
		)
	}

	private fun extractTrmObject(doc: Document): JSONObject? {
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		return extractBalancedJsonObjectAfter(scriptsText, "window.TRM =")?.toJSONObjectOrNull()
			?: extractBalancedJsonObjectAfter(scriptsText, "window.TRM=")?.toJSONObjectOrNull()
	}

	private fun extractUsernameFromDoc(doc: Document): String? {
		doc.selectFirst("[data-current-username]")?.attrOrNull("data-current-username")?.let { return it }
		val profileLink = doc.selectFirst("a[href^=\"/@\"]") ?: return null
		return profileLink.textOrNull()?.removePrefix("@")?.trim()?.nullIfEmpty()
			?: profileLink.attrOrNull("href")?.substringAfter("/@", "")?.substringBefore("/")?.nullIfEmpty()
	}

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}

	private companion object {
		const val COMMENTS_PER_PAGE = 20
	}
}
