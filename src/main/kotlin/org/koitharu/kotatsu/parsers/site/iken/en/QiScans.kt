package org.koitharu.kotatsu.parsers.site.iken.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.EMPTY
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("QISCANS", "Qi Scans", "en")
internal class QiScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.QISCANS, 20),
	MangaSourceCommunityProvider,
	MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("qimanhwa.com")

	private val apiUrl: String
		get() = "https://api.$domain/api/v1"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			"$apiUrl/series/search?q=${filter.query.urlEncoded()}&page=$page&perPage=$pageSize"
		} else {
			val sort = when (order) {
				SortOrder.POPULARITY -> "popular"
				SortOrder.NEWEST -> "newest"
				else -> "latest"
			}
			"$apiUrl/series?page=$page&perPage=$pageSize&sort=$sort"
		}
		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONArray("data")
		return (0 until data.length()).map { parseMangaFromList(data.getJSONObject(it)) }
	}

	private fun parseMangaFromList(json: JSONObject): Manga {
		val slug = json.getString("slug")
		val altTitles = json.optString("alternativeTitles", "").nullIfEmpty()
			?.split(" • ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
		val avgRating = json.optDouble("avgRating", -1.0)

		return Manga(
			id = generateUid(slug),
			url = slug,
			publicUrl = "https://qimanhwa.com/series/$slug",
			coverUrl = json.optString("cover", "").nullIfEmpty(),
			title = json.getString("title"),
			altTitles = altTitles,
			rating = if (avgRating > 0) (avgRating / 5.0).toFloat() else RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = parseState(json.optString("status", "")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val response = webClient.httpGet("$apiUrl/series/$slug").parseJson()

		val altTitles = response.optString("alternativeTitles", "").nullIfEmpty()
			?.split(" • ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
		val author = response.optString("author", "").nullIfEmpty()
		val artist = response.optString("artist", "").nullIfEmpty()
		val authors = setOfNotNull(author, artist)

		// Fetch all chapters with pagination (max 30 per page)
		val allChapters = mutableListOf<JSONObject>()
		var page = 1
		while (true) {
			val chaptersResponse = webClient.httpGet(
				"$apiUrl/series/$slug/chapters?page=$page&perPage=30&sort=desc"
			).parseJson()
			val data = chaptersResponse.getJSONArray("data")
			if (data.length() == 0) break
			for (i in 0 until data.length()) {
				allChapters.add(data.getJSONObject(i))
			}
			if (page >= chaptersResponse.optInt("totalPages", 1)) break
			page++
		}

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
		val chapters = allChapters.map { ch ->
			val chapterSlug = ch.getString("slug")
			val number = ch.optDouble("number", 0.0).toFloat()
			val chTitle = ch.optString("title", "").nullIfEmpty()
			val title = buildString {
				append("Chapter ")
				append(number.toString().substringBefore(".0"))
				if (!chTitle.isNullOrBlank()) {
					append(" - ")
					append(chTitle)
				}
			}
			MangaChapter(
				id = ch.getLong("id"),
				title = title,
				number = number,
				volume = 0,
				url = JSONObject().apply {
					put("slug", slug)
					put("chapterSlug", chapterSlug)
				}.toString(),
				uploadDate = ch.optString("createdAt", "").nullIfEmpty()
					?.let { dateFormat.parseSafe(it.substringBefore("T")) } ?: 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed()

		return manga.copy(
			title = response.getString("title"),
			coverUrl = response.optString("cover", "").nullIfEmpty() ?: manga.coverUrl,
			description = response.optString("description", "").nullIfEmpty(),
			altTitles = altTitles,
			state = parseState(response.optString("status", "")),
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val slug = chapterData.getString("slug")
		val chapterSlug = chapterData.getString("chapterSlug")

		val response = webClient.httpGet("$apiUrl/series/$slug/chapters/$chapterSlug").parseJson()
		val images = response.getJSONArray("images")

		return (0 until images.length()).map { i ->
			val img = images.getJSONObject(i)
			MangaPage(
				id = generateUid(img.getString("url")),
				url = img.getString("url"),
				preview = null,
				source = source,
			)
		}
	}

	override val authUrl: String
		get() = "https://$domain/login"

	override val signUpUrl: String
		get() = "https://$domain/register"

	override suspend fun isAuthorized(): Boolean {
		if (!hasNonCloudflareCookie()) return false
		return runCatchingCancellable { fetchCurrentUser() != null }.getOrDefault(false)
	}

	override suspend fun getUsername(): String {
		val user = fetchCurrentUser() ?: throw AuthRequiredException(source)
		return user.getStringOrNull("displayName")
			?: user.getStringOrNull("username")
			?: user.getStringOrNull("email")
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatchingCancellable {
			executeJsonRequest("POST", "$apiUrl/auth/logout", JSONObject())
		}
		context.clearAuthValues(source)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(
			SourceCommentSort.TOP,
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
		),
	)

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo = coroutineScope {
		val slug = extractSeriesSlug(manga)
		val encodedSlug = slug.urlEncoded()
		val hasCookies = hasNonCloudflareCookie()

		val seriesDeferred = async {
			executeJsonRequest("GET", "$apiUrl/series/$encodedSlug")
		}
		val userStateDeferred = if (hasCookies) {
			async {
				runCatchingCancellable {
					executeJsonRequest("GET", "$apiUrl/series/$encodedSlug/user-state")
				}.getOrNull()
			}
		} else {
			null
		}
		val userDeferred = if (hasCookies) {
			async { runCatchingCancellable { fetchCurrentUser() }.getOrNull() }
		} else {
			null
		}
		val chapterCommentsDeferred = if (chapter != null && chapter.id > 0L) {
			async {
				runCatchingCancellable {
					executeJsonRequest("GET", "$apiUrl/chapters/${chapter.id}/comments?page=1&perPage=1")
				}.getOrNull()
			}
		} else {
			null
		}

		val series = seriesDeferred.await()
		val userState = userStateDeferred?.await()
		val user = userDeferred?.await()
		val chapterComments = chapterCommentsDeferred?.await()

		val stats = series.optJSONObject("stats")
		val rawRating = stats?.optDouble("averageRating", Double.NaN)
			?.takeIf { !it.isNaN() && it > 0.0 }
			?.toFloat()
		val ratingCount = stats?.optInt("reviewCount", -1)?.takeIf { it >= 0 }
		val commentCount = stats?.optInt("commentCount", -1)?.takeIf { it >= 0 }
		val viewCount = series.optLong("views", -1L).takeIf { it >= 0L }
			?: stats?.optLong("views", -1L)?.takeIf { it >= 0L }
		val followCount = series.optLong("bookmarks", -1L).takeIf { it >= 0L }
			?: stats?.optLong("bookmarks", -1L)?.takeIf { it >= 0L }
			?: series.optLong("followersCount", -1L).takeIf { it >= 0L }
		val chapterCommentCount = chapterComments?.let {
			it.optInt("totalComments", -1).takeIf { n -> n >= 0 }
				?: it.optInt("totalItems", -1).takeIf { n -> n >= 0 }
		}

		val userRating = userState?.let { state ->
			state.optInt("userRating", -1).takeIf { it in 1..5 }
				?: state.optJSONObject("userReview")?.optInt("rating", -1)?.takeIf { it in 1..5 }
		}
		val currentUsername = user?.let {
			it.getStringOrNull("displayName") ?: it.getStringOrNull("username")
		}
		val isAuthed = user != null

		SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 5f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 5,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = commentCount,
			chapterCommentCount = chapterCommentCount,
			canCurrentUserRate = isAuthed,
			canCurrentUserComment = isAuthed,
			currentUsername = currentUsername,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val slug = extractSeriesSlug(manga).urlEncoded()
		val clamped = score.coerceIn(1, 5)
		val payload = JSONObject().apply {
			put("rating", clamped)
			put("recommend", clamped >= 3)
		}
		executeJsonRequest("POST", "$apiUrl/series/$slug/reviews", payload)
		return getMangaCommunityInfo(manga, chapter).copy(userRating = clamped)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = coroutineScope {
		val basePath = resolveCommentsBasePath(manga, chapter)
		val sortParam = when (sort) {
			SourceCommentSort.TOP -> "top"
			SourceCommentSort.NEWEST -> "newest"
			SourceCommentSort.OLDEST -> "oldest"
		}
		val userDeferred = if (hasNonCloudflareCookie()) {
			async { runCatchingCancellable { fetchCurrentUser() }.getOrNull() }
		} else {
			null
		}
		val jsonResult = runCatchingCancellable {
			executeJsonRequest("GET", "$basePath/comments?page=$page&perPage=$COMMENTS_PAGE_SIZE&sort=$sortParam")
		}
		val currentUser = userDeferred?.await()
		val currentUserId = currentUser?.let {
			it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString()
		}
		val isAuthed = currentUser != null

		val json = jsonResult.getOrElse { err ->
			if (page == 1) {
				val fallbackWidget = runCatchingCancellable { resolveWidgetContext(manga, chapter) }.getOrNull()
				if (fallbackWidget != null) {
					return@coroutineScope SourceCommentPage(
						comments = emptyList(),
						page = page,
						hasNextPage = false,
						widgetContext = fallbackWidget,
						engine = fallbackWidget.engine,
					)
				}
			}
			throw err
		}

		val dataArray = json.optJSONArray("data")?.asTypedList<JSONObject>().orEmpty()
		val comments = dataArray.map { parseQiComment(it, currentUserId, isAuthed) }
		val total = json.optInt("totalComments", -1).takeIf { it >= 0 }
			?: json.optInt("totalItems", -1).takeIf { it >= 0 }
		val currentPage = json.optInt("current", page)
		val totalPages = json.optInt("totalPages", currentPage)
		val hasNextPage = json.optInt("next", -1) > currentPage || currentPage < totalPages

		if (comments.isEmpty() && page == 1) {
			val fallbackWidget = runCatchingCancellable { resolveWidgetContext(manga, chapter) }.getOrNull()
			if (fallbackWidget != null) {
				return@coroutineScope SourceCommentPage(
					comments = emptyList(),
					page = page,
					hasNextPage = false,
					totalCount = total,
					widgetContext = fallbackWidget,
					engine = fallbackWidget.engine,
				)
			}
		}

		SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasNextPage,
			totalCount = total,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage = coroutineScope {
		val userDeferred = if (hasNonCloudflareCookie()) {
			async { runCatchingCancellable { fetchCurrentUser() }.getOrNull() }
		} else {
			null
		}
		val json = executeJsonRequest(
			"GET",
			"$apiUrl/comments/${comment.id}/replies?page=$page&perPage=$COMMENTS_PAGE_SIZE&sort=oldest",
		)
		val currentUser = userDeferred?.await()
		val currentUserId = currentUser?.let {
			it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString()
		}
		val isAuthed = currentUser != null
		val dataArray = json.optJSONArray("data")?.asTypedList<JSONObject>().orEmpty()
		val replies = dataArray.map {
			parseQiComment(it, currentUserId, isAuthed, fallbackParentId = comment.id)
		}
		val currentPage = json.optInt("current", page)
		val totalPages = json.optInt("totalPages", currentPage)
		val total = json.optInt("totalItems", -1).takeIf { it >= 0 }
		val hasNextPage = json.optInt("next", -1) > currentPage || currentPage < totalPages
		SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = hasNextPage,
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
		val url = if (parentComment != null) {
			"$apiUrl/comments/${parentComment.id}/replies"
		} else {
			"${resolveCommentsBasePath(manga, chapter)}/comments"
		}
		val payload = JSONObject().put("content", text)
		val json = executeJsonRequest("POST", url, payload)
		val obj = json.optJSONObject("data") ?: json.optJSONObject("comment") ?: json
		return if (obj.has("id")) {
			parseQiComment(obj, currentUserId = null, isAuthed = true, fallbackParentId = parentComment?.id)
				.copy(isOwnComment = true, canEdit = true, canDelete = true)
		} else {
			SourceComment(
				id = System.currentTimeMillis().toString(),
				authorName = runCatchingCancellable { getUsername() }.getOrNull().orEmpty(),
				body = text,
				createdAtEpochMs = System.currentTimeMillis(),
				parentId = parentComment?.id,
				isOwnComment = true,
				canReply = true,
				canLike = true,
				canDislike = true,
				canEdit = true,
				canDelete = true,
			)
		}
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		val payload = JSONObject().put("content", newText)
		val json = executeJsonRequest("PATCH", "$apiUrl/comments/${comment.id}", payload)
		val obj = json.optJSONObject("data") ?: json.optJSONObject("comment") ?: json
		return if (obj.has("id")) {
			parseQiComment(obj, currentUserId = null, isAuthed = true, fallbackParentId = comment.parentId)
				.copy(isOwnComment = true, canEdit = true, canDelete = true, isEdited = true)
		} else {
			comment.copy(
				body = newText,
				bodyHtml = null,
				isEdited = true,
			)
		}
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		executeJsonRequest("DELETE", "$apiUrl/comments/${comment.id}")
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val desiredVote = if (isLike) 1 else -1
		val removingVote = comment.userVote == desiredVote
		val json = if (removingVote) {
			executeJsonRequest("DELETE", "$apiUrl/comments/${comment.id}/vote")
		} else {
			val payload = JSONObject().put("type", if (isLike) "UPVOTE" else "DOWNVOTE")
			executeJsonRequest("POST", "$apiUrl/comments/${comment.id}/vote", payload)
		}
		val data = json.optJSONObject("data") ?: json
		if (data.has("upvotes") || data.has("downvotes")) {
			val nextVote = when (data.getStringOrNull("userVote")?.uppercase(Locale.ROOT)) {
				"UPVOTE" -> 1
				"DOWNVOTE" -> -1
				else -> if (removingVote) 0 else desiredVote
			}
			return comment.copy(
				likesCount = data.optInt("upvotes", comment.likesCount),
				dislikesCount = data.optInt("downvotes", comment.dislikesCount),
				userVote = nextVote,
			)
		}
		var likes = comment.likesCount
		var dislikes = comment.dislikesCount
		when (comment.userVote) {
			1 -> likes = maxOf(0, likes - 1)
			-1 -> dislikes = maxOf(0, dislikes - 1)
		}
		val nextVote = if (removingVote) {
			0
		} else {
			if (isLike) likes++ else dislikes++
			desiredVote
		}
		return comment.copy(
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = nextVote,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = if (chapter != null) {
			val chapterData = runCatching { JSONObject(chapter.url) }.getOrNull()
			val chSeriesSlug = chapterData?.getStringOrNull("slug") ?: extractSeriesSlug(manga)
			val chSlug = chapterData?.getStringOrNull("chapterSlug")
			if (!chSlug.isNullOrBlank()) {
				"https://$domain/series/$chSeriesSlug/$chSlug"
			} else {
				"https://$domain/series/$chSeriesSlug"
			}
		} else {
			"https://$domain/series/${extractSeriesSlug(manga)}"
		}
		val doc = runCatchingCancellable { webClient.httpGet(pageUrl).parseHtml() }.getOrNull() ?: return null
		return WidgetDetectors.detect(doc, pageUrl)
	}

	private fun hasNonCloudflareCookie(): Boolean {
		val cookies = context.cookieJar.getCookies(domain) + context.cookieJar.getCookies("api.$domain")
		return cookies.any { it.name !in CF_COOKIE_NAMES && it.value.isNotBlank() }
	}

	private suspend fun fetchCurrentUser(): JSONObject? {
		val json = executeJsonRequest("GET", "$apiUrl/users/me")
		val user = json.optJSONObject("user") ?: json.optJSONObject("data") ?: json
		return user.takeIf {
			it.getStringOrNull("id") != null ||
				it.getStringOrNull("username") != null ||
				it.getStringOrNull("displayName") != null
		}
	}

	private fun extractSeriesSlug(manga: Manga): String {
		return manga.url.trim()
			.substringBefore('?')
			.substringBefore('#')
			.trimEnd('/')
			.substringAfterLast('/')
	}

	private fun resolveCommentsBasePath(manga: Manga, chapter: MangaChapter?): String {
		return if (chapter != null && chapter.id > 0L) {
			"$apiUrl/chapters/${chapter.id}"
		} else {
			"$apiUrl/series/${extractSeriesSlug(manga).urlEncoded()}"
		}
	}

	private fun parseQiComment(
		obj: JSONObject,
		currentUserId: String?,
		isAuthed: Boolean,
		fallbackParentId: String? = null,
	): SourceComment {
		val id = obj.getStringOrNull("id") ?: obj.optLong("id", 0L).toString()
		val authorObj = obj.optJSONObject("author") ?: obj.optJSONObject("user")
		val authorId = obj.getStringOrNull("authorId")
			?: authorObj?.let { it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString() }
		val authorName = authorObj?.let {
			it.getStringOrNull("displayName") ?: it.getStringOrNull("username")
		} ?: "Anonymous"
		val avatarUrl = authorObj?.getStringOrNull("avatar")?.toAbsoluteUrl(domain)
		val authorRole = authorObj?.getStringOrNull("role")?.takeUnless { it.equals("USER", ignoreCase = true) }

		val rawContent = obj.getStringOrNull("content").orEmpty().trim()
		val hasHtml = rawContent.contains('<') && rawContent.contains('>')
		val plainText = if (hasHtml) {
			Jsoup.parseBodyFragment(rawContent.replace(LINE_BREAK_TAG_REGEX, "\n"))
				.wholeText()
				.replace(Regex("\n{3,}"), "\n\n")
				.trim()
				.ifEmpty { rawContent }
		} else {
			rawContent
		}

		val parentId = obj.getStringOrNull("parentId")
			?: obj.optLong("parentId", -1L).takeIf { it > 0L }?.toString()
			?: fallbackParentId
		val userVote = when (obj.getStringOrNull("userVote")?.uppercase(Locale.ROOT)) {
			"UPVOTE" -> 1
			"DOWNVOTE" -> -1
			else -> 0
		}
		val nestedReplies = obj.optJSONArray("replies")?.asTypedList<JSONObject>().orEmpty().map { reply ->
			parseQiComment(reply, currentUserId, isAuthed, fallbackParentId = id)
		}
		val replyCount = maxOf(obj.optInt("replyCount", 0), nestedReplies.size)
		val isOwn = currentUserId != null && authorId != null && currentUserId == authorId
		val createdAtRaw = obj.getStringOrNull("createdAt")

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = authorRole,
			body = plainText,
			bodyHtml = rawContent.takeIf { hasHtml },
			createdAtEpochMs = parseIsoTimestamp(createdAtRaw),
			createdAtText = createdAtRaw,
			isEdited = obj.getBooleanOrDefault("isEdited", false),
			likesCount = obj.optInt("upvotes", 0),
			dislikesCount = obj.optInt("downvotes", 0),
			userVote = userVote,
			repliesCount = replyCount,
			parentId = parentId,
			replies = nestedReplies,
			isOwnComment = isOwn,
			canReply = isAuthed,
			canLike = isAuthed,
			canDislike = isAuthed,
			canEdit = isOwn,
			canDelete = isOwn,
		)
	}

	private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
		.set("Accept", "application/json, text/plain, */*")
		.set("Origin", "https://$domain")
		.set("Referer", "https://$domain/")
		.build()

	private suspend fun executeJsonRequest(
		method: String,
		url: String,
		body: JSONObject? = null,
	): JSONObject {
		val requestBody = when {
			method == "GET" || method == "HEAD" -> null
			method == "DELETE" && body == null -> null
			body != null -> body.toString().toRequestBody(JSON_MEDIA_TYPE)
			else -> EMPTY
		}
		val request = Request.Builder()
			.method(method, requestBody)
			.url(url)
			.headers(getApiHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		return context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val rawBody = response.body.string()
			val json = rawBody.toJSONObjectOrNull()
			if (!response.isSuccessful) {
				val message = json?.getStringOrNull("message")
					?: json?.getStringOrNull("error")
					?: response.message
				throw HttpStatusException(message, response.code, url)
			}
			json ?: JSONObject()
		}
	}

	private fun parseState(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"MASS_RELEASED" -> MangaState.ONGOING
		"DROPPED", "CANCELLED" -> MangaState.ABANDONED
		"COMING_SOON" -> MangaState.UPCOMING
		else -> null
	}

	private companion object {

		private const val COMMENTS_PAGE_SIZE = 20
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		private val LINE_BREAK_TAG_REGEX = Regex("""<(?:br\s*/?|/p|/div)>""", RegexOption.IGNORE_CASE)
		private val CF_COOKIE_NAMES = setOf("cf_clearance", "__cf_bm", "__cflb", "_cfuvid")
	}
}
