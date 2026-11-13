package org.koitharu.kotatsu.parsers.site.community

import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.EMPTY
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class OkutoonCommunityHelper(
	private val context: MangaLoaderContext,
	private val webClient: WebClient,
	private val source: MangaSource,
	private val domainProvider: () -> String,
	private val requestHeadersProvider: () -> Headers = { Headers.Builder().build() },
) {

	private val cachedPageContexts = ConcurrentHashMap<String, PageContext>()

	val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = false,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = false,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(SourceCommentSort.NEWEST),
	)

	val authUrl: String
		get() = "https://${domainProvider()}/login"

	val signUpUrl: String
		get() = "https://${domainProvider()}/register"

	internal data class PageContext(
		val pageUrl: String,
		val commentAction: String?,
		val commentLoadUrl: String?,
		val likeUrlTemplate: String?,
		val deleteUrlTemplate: String?,
		val editUrlTemplate: String?,
		val repliesUrlTemplate: String?,
		val threadUrlTemplate: String?,
		val currentUserId: String?,
		val currentUsername: String?,
		val batchSize: Int,
		val commentKind: String?,
		val csrfToken: String?,
		val extraFormFields: Map<String, String>,
		val isAuthenticated: Boolean,
		val fetchedAtMs: Long = System.currentTimeMillis(),
	)

	private fun rememberPageContext(pageCtx: PageContext) {
		if (pageCtx.pageUrl.isBlank() || !pageCtx.isAuthenticated || pageCtx.commentAction == null) return
		if (cachedPageContexts.size >= MAX_CACHED_CONTEXTS && !cachedPageContexts.containsKey(pageCtx.pageUrl)) {
			val oldestKey = cachedPageContexts.entries.minByOrNull { it.value.fetchedAtMs }?.key
			if (oldestKey != null) cachedPageContexts.remove(oldestKey)
		}
		cachedPageContexts[pageCtx.pageUrl] = pageCtx
	}

	private fun consumeCachedPageContext(pageUrl: String): PageContext? {
		val cached = cachedPageContexts.remove(pageUrl) ?: return null
		val age = System.currentTimeMillis() - cached.fetchedAtMs
		return if (age in 0L..PAGE_CONTEXT_CACHE_TTL_MS) cached else null
	}

	private suspend fun loadPageContext(pageUrl: String, forceRefresh: Boolean = false): PageContext {
		if (!forceRefresh) {
			consumeCachedPageContext(pageUrl)?.let { return it }
		}
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return extractPageContext(doc, pageUrl)
	}

	suspend fun isAuthorized(): Boolean {
		val domain = domainProvider()
		val homeAuthorized = runCatching {
			val doc = webClient.httpGet("https://$domain/").parseHtml()
			extractUsernameFromDoc(doc) != null || extractCurrentUserId(doc) != null
		}.getOrDefault(false)
		if (homeAuthorized) {
			return true
		}
		val loginUrl = authUrl
		return runCatching {
			val request = Request.Builder()
				.get()
				.url(loginUrl)
				.headers(requestHeadersProvider())
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().use { response ->
				if (response.code == 401 || response.code == 403) return@use false
				val finalUrl = response.request.url.toString()
				val html = response.body.string()
				val doc = Jsoup.parse(html, finalUrl)
				val hasProfile = extractUsernameFromDoc(doc) != null || extractCurrentUserId(doc) != null
				if (hasProfile) return@use true
				val redirectedAwayFromLogin = !finalUrl.contains("/login", ignoreCase = true) &&
					finalUrl.contains(domain, ignoreCase = true)
				val hasLoginForm = doc.selectFirst("form[action*=\"/login\"], input[name=password]") != null
				redirectedAwayFromLogin && !hasLoginForm
			}
		}.getOrDefault(false)
	}

	suspend fun getUsername(): String {
		val domain = domainProvider()
		val homeUrl = "https://$domain/"
		val doc = runCatching { webClient.httpGet(homeUrl).parseHtml() }.getOrElse {
			throw AuthRequiredException(source, it)
		}
		return extractUsernameFromDoc(doc) ?: throw AuthRequiredException(source)
	}

	suspend fun logout() {
		val domain = domainProvider()
		val csrf = runCatching {
			webClient.httpGet("https://$domain/").parseHtml()
				.selectFirst("meta[name=csrf-token]")?.attrOrNull("content")
		}.getOrNull()
		runCatching {
			val requestBuilder = Request.Builder()
				.post(EMPTY)
				.url("https://$domain/logout")
				.headers(requestHeadersProvider())
				.tag(MangaSource::class.java, source)
			if (csrf != null) {
				requestBuilder.header("X-CSRF-Token", csrf)
			}
			context.httpClient.newCall(requestBuilder.build()).await().close()
		}
		context.clearAuthValues(source)
	}

	suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return parseCommunityInfo(doc)
	}

	internal fun parseCommunityInfo(doc: Document): SourceMangaCommunityInfo {
		val pageCtx = extractPageContext(doc, doc.location())
		val jsonLd = extractAggregateRatingFromJsonLd(doc)

		val ratingStr = doc.selectFirst(".series-stat--rating strong")?.textOrNull()
		val rawRating = parseFloatRating(ratingStr) ?: jsonLd?.ratingValue
		val scale = jsonLd?.bestRating?.takeIf { it > 0 } ?: 5
		val averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) }

		val ratingSubText = doc.selectFirst(".series-stat--rating .series-stat-sub")?.textOrNull()
		val ratingCount = parseRatingSubCount(ratingSubText) ?: jsonLd?.ratingCount

		val viewEl = doc.selectFirst("[data-series-view-count]")
		val viewCount = viewEl?.attrOrNull("data-series-view-count")?.let(::parseCompactCount)
			?: viewEl?.textOrNull()?.let(::parseCompactCount)
			?: doc.selectFirst(".series-stat--views strong")?.textOrNull()?.let(::parseCompactCount)
			?: jsonLd?.viewCount

		val followCount = doc.selectFirst("[data-series-bookmark-count], [data-series-follow-count]")?.let { el ->
			(el.attrOrNull("data-series-bookmark-count")
				?: el.attrOrNull("data-series-follow-count")
				?: el.textOrNull())?.let(::parseCompactCount)
		} ?: doc.selectFirst(".series-stat--bookmarks strong, .series-stat--follows strong")
			?.textOrNull()?.let(::parseCompactCount)

		val commentCount = doc.selectFirst("div.comments-section[data-comment-total], [data-comment-total]")
			?.attrOrNull("data-comment-total")?.toIntOrNull()
			?: doc.selectFirst(".comments-section .comments-count, .comments-section-count")
				?.textOrNull()?.let { parseCompactCount(it)?.toInt() }

		val userRating = doc.selectFirst("[data-current-user-rating], [data-user-rating]")?.let { el ->
			(el.attrOrNull("data-current-user-rating") ?: el.attrOrNull("data-user-rating"))?.toIntOrNull()
		} ?: doc.selectFirst("div.comments-section input[name=score][checked]")?.attrOrNull("value")?.toIntOrNull()

		val canInteract = pageCtx.isAuthenticated && pageCtx.commentAction != null
		rememberPageContext(pageCtx)

		return SourceMangaCommunityInfo(
			averageRating = averageRating,
			rawRating = rawRating,
			rawScale = scale,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = commentCount,
			canCurrentUserRate = canInteract,
			canCurrentUserComment = canInteract,
			currentUsername = pageCtx.currentUsername,
		)
	}

	suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter? = null,
		page: Int = 1,
		cursor: String? = null,
	): SourceCommentPage {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val pageCtx = extractPageContext(doc, pageUrl)
		rememberPageContext(pageCtx)
		val loadUrl = pageCtx.commentLoadUrl
			?: return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)

		val limit = pageCtx.batchSize
		val offset = cursor?.toIntOrNull() ?: ((page - 1).coerceAtLeast(0) * limit)
		val requestUrl = loadUrl.toHttpUrlOrNull()?.newBuilder()
			?.setQueryParameter("offset", offset.toString())
			?.setQueryParameter("limit", limit.toString())
			?.build()
			?: throw ParseException("Invalid Okutoon comment load URL", loadUrl)

		val request = Request.Builder()
			.get()
			.url(requestUrl)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json")
			.header("Referer", pageUrl)
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw ParseException("Failed to load comments (${response.code})", requestUrl.toString())
			}
			JSONObject(response.body.string())
		}

		val commentsArray = json.optJSONArray("comments")
			?: json.optJSONObject("data")?.optJSONArray("comments")
			?: JSONArray()
		val comments = parseCommentsArray(commentsArray, pageCtx)
		val total = json.opt("total").asNullableInt()
			?: json.opt("total_count").asNullableInt()
			?: json.optJSONObject("data")?.opt("total").asNullableInt()
		val explicitHasMore = json.opt("has_more") as? Boolean
			?: json.opt("has_next") as? Boolean
			?: json.optJSONObject("data")?.opt("has_more") as? Boolean
		val hasNext = explicitHasMore ?: when {
			total != null -> offset + comments.size < total
			else -> comments.size >= limit
		}
		val nextCursor = if (hasNext) (offset + comments.size).toString() else null

		return SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasNext,
			totalCount = total,
			engine = SourceCommentEngine.NATIVE,
			nextCursor = nextCursor,
		)
	}

	suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
		page: Int = 1,
		cursor: String? = null,
	): SourceCommentPage {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val pageCtx = extractPageContext(doc, pageUrl)
		val template = pageCtx.repliesUrlTemplate ?: pageCtx.threadUrlTemplate
			?: return SourceCommentPage(
				comments = comment.replies,
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)

		val rawRepliesUrl = template.replace("__ID__", comment.id)
		val limit = REPLIES_BATCH_SIZE
		val offset = cursor?.toIntOrNull() ?: ((page - 1).coerceAtLeast(0) * limit)
		val requestUrl = rawRepliesUrl.toHttpUrlOrNull()?.newBuilder()
			?.setQueryParameter("offset", offset.toString())
			?.setQueryParameter("limit", limit.toString())
			?.build()
			?: throw ParseException("Invalid Okutoon replies URL", rawRepliesUrl)

		val request = Request.Builder()
			.get()
			.url(requestUrl)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json")
			.header("Referer", pageUrl)
			.tag(MangaSource::class.java, source)
			.build()

		val bodyStr = context.httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw ParseException("Failed to load replies (${response.code})", requestUrl.toString())
			}
			response.body.string()
		}

		val rootObj = bodyStr.toJSONObjectOrNull()
		val repliesArray = rootObj?.optJSONArray("replies")
			?: rootObj?.optJSONArray("comments")
			?: rootObj?.optJSONObject("data")?.optJSONArray("replies")
			?: rootObj?.optJSONObject("data")?.optJSONArray("comments")
			?: bodyStr.toJSONArrayOrNull()
			?: JSONArray()
		val replies = parseCommentsArray(repliesArray, pageCtx)
		val total = rootObj?.opt("total").asNullableInt()
			?: rootObj?.opt("reply_total").asNullableInt()
		val explicitHasMore = rootObj?.opt("has_more") as? Boolean
		val hasNext = explicitHasMore ?: when {
			total != null -> offset + replies.size < total
			else -> replies.size >= limit
		}

		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = hasNext,
			totalCount = total,
			engine = SourceCommentEngine.NATIVE,
			nextCursor = if (hasNext) (offset + replies.size).toString() else null,
		)
	}

	suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter? = null,
		parentComment: SourceComment? = null,
		isSpoiler: Boolean = false,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val pageCtx = loadPageContext(pageUrl)
		val bodyText = if (isSpoiler) {
			"<blockquote class=\"spoiler\">${escapeHtml(text)}</blockquote>"
		} else {
			text
		}
		return submitCommentOrReview(
			pageCtx = pageCtx,
			bodyText = bodyText,
			parentComment = parentComment,
			score = null,
		).comment
	}

	suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val clampedScore = score.coerceIn(1, 5)
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val pageCtx = extractPageContext(doc, pageUrl)
		val submitResult = submitCommentOrReview(
			pageCtx = pageCtx,
			bodyText = "",
			parentComment = null,
			score = clampedScore,
		)
		val baseInfo = parseCommunityInfo(doc)
		val avg = submitResult.updatedAverageRating ?: baseInfo.rawRating
		val count = submitResult.updatedRatingCount ?: baseInfo.ratingCount
		return baseInfo.copy(
			rawRating = avg,
			averageRating = avg?.let { (it / baseInfo.rawScale.toFloat()).coerceIn(0f, 1f) },
			userRating = clampedScore,
			ratingCount = count,
		)
	}

	suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter? = null,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val pageCtx = loadPageContext(pageUrl)
		if (!pageCtx.isAuthenticated) {
			throw AuthRequiredException(source)
		}
		val editUrl = pageCtx.editUrlTemplate?.replace("__ID__", comment.id)
			?: throw ParseException("Comment edit URL template is missing", pageUrl)

		awaitHoneypotDwell(pageCtx)
		val payload = JSONObject().apply {
			put("body", newText)
			put("content_policy_accepted", true)
			for ((k, v) in pageCtx.extraFormFields) {
				if (!has(k)) put(k, v)
			}
			if (!has("website_url")) put("website_url", "")
		}

		val json = executeJsonMutation(
			method = "POST",
			url = editUrl,
			payload = payload,
			pageCtx = pageCtx,
		)
		val dataObj = json.optJSONObject("data") ?: json
		val updatedObj = dataObj.optJSONObject("comment") ?: json.optJSONObject("comment")
		return if (updatedObj != null) {
			parseCommentObject(updatedObj, pageCtx)
		} else {
			comment.copy(
				body = newText,
				bodyHtml = null,
				isEdited = true,
			)
		}
	}

	suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val pageCtx = extractPageContext(doc, pageUrl)
		if (!pageCtx.isAuthenticated) {
			throw AuthRequiredException(source)
		}
		val deleteUrl = pageCtx.deleteUrlTemplate?.replace("__ID__", comment.id)
			?: throw ParseException("Comment delete URL template is missing", pageUrl)

		executeJsonMutation(
			method = "DELETE",
			url = deleteUrl,
			payload = null,
			pageCtx = pageCtx,
		)
	}

	suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val pageCtx = extractPageContext(doc, pageUrl)
		if (!pageCtx.isAuthenticated) {
			throw AuthRequiredException(source)
		}
		val likeUrl = pageCtx.likeUrlTemplate?.replace("__ID__", comment.id)
			?: throw ParseException("Comment like URL template is missing", pageUrl)

		val json = executeJsonMutation(
			method = "POST",
			url = likeUrl,
			payload = JSONObject(),
			pageCtx = pageCtx,
		)
		val dataObj = json.optJSONObject("data") ?: json
		val updatedObj = dataObj.optJSONObject("comment") ?: json.optJSONObject("comment")
		if (updatedObj != null) {
			return parseCommentObject(updatedObj, pageCtx)
		}

		val liked = when {
			dataObj.has("liked_by_current_user") -> dataObj.getBooleanOrDefault("liked_by_current_user", false)
			dataObj.has("liked") -> dataObj.getBooleanOrDefault("liked", false)
			else -> comment.userVote != 1
		}
		val likeCount = dataObj.opt("like_count").asNullableInt()
			?: dataObj.opt("likes_count").asNullableInt()
			?: (comment.likesCount + if (liked && comment.userVote != 1) 1 else if (!liked && comment.userVote == 1) -1 else 0)
				.coerceAtLeast(0)

		return comment.copy(
			likesCount = likeCount,
			userVote = if (liked) 1 else 0,
		)
	}

	internal data class SubmitResult(
		val comment: SourceComment,
		val updatedAverageRating: Float? = null,
		val updatedRatingCount: Int? = null,
	)

	private suspend fun submitCommentOrReview(
		pageCtx: PageContext,
		bodyText: String,
		parentComment: SourceComment?,
		score: Int?,
	): SubmitResult {
		if (!pageCtx.isAuthenticated) {
			throw AuthRequiredException(source)
		}
		val actionUrl = pageCtx.commentAction
			?: throw ParseException("Comment action URL is missing", pageCtx.pageUrl)

		fun buildPayload(ctx: PageContext): JSONObject = JSONObject().apply {
			put("body", bodyText)
			if (parentComment != null) {
				put("parent_id", parentComment.id.toLongOrNull() ?: parentComment.id)
			}
			if (score != null) {
				put("score", score)
			}
			put("content_policy_accepted", true)
			for ((k, v) in ctx.extraFormFields) {
				if (!has(k)) put(k, v)
			}
			if (!has("website_url")) {
				put("website_url", "")
			}
		}

		awaitHoneypotDwell(pageCtx)
		val json = try {
			executeJsonMutation(
				method = "POST",
				url = actionUrl,
				payload = buildPayload(pageCtx),
				pageCtx = pageCtx,
			)
		} catch (e: ParseException) {
			if (!isHoneypotRetryableError(e.shortMessage)) throw e
			val freshCtx = loadPageContext(pageCtx.pageUrl, forceRefresh = true)
			if (!freshCtx.isAuthenticated) {
				throw AuthRequiredException(source)
			}
			val freshActionUrl = freshCtx.commentAction ?: actionUrl
			awaitHoneypotDwell(freshCtx, forceMinimum = true)
			executeJsonMutation(
				method = "POST",
				url = freshActionUrl,
				payload = buildPayload(freshCtx),
				pageCtx = freshCtx,
			)
		}

		val dataObj = json.optJSONObject("data") ?: json
		val commentObj = dataObj.optJSONObject("comment") ?: json.optJSONObject("comment")
		val message = dataObj.getStringOrNull("message") ?: json.getStringOrNull("message")
		val isHeld = json.getBooleanOrDefault("held_for_review", false) ||
			dataObj.getBooleanOrDefault("held_for_review", false) ||
			json.getStringOrNull("status").equals("held_for_review", ignoreCase = true) ||
			dataObj.getStringOrNull("status").equals("held_for_review", ignoreCase = true)

		val comment = if (commentObj != null) {
			parseCommentObject(commentObj, pageCtx)
		} else {
			val meta = buildMap {
				if (isHeld) put("held_for_review", "true")
				if (message != null) put("message", message)
				if (score != null) put("review_score", score.toString())
			}
			SourceComment(
				id = "pending-${System.currentTimeMillis()}",
				authorName = pageCtx.currentUsername ?: "You",
				body = message ?: bodyText,
				createdAtEpochMs = System.currentTimeMillis(),
				parentId = parentComment?.id,
				isOwnComment = true,
				rawMeta = meta,
			)
		}

		val updatedAvg = (dataObj.opt("average_rating") ?: dataObj.opt("rating"))?.toString()?.toFloatOrNull()
		val updatedCount = (dataObj.opt("rating_count") ?: dataObj.opt("votes")).asNullableInt()

		return SubmitResult(
			comment = comment,
			updatedAverageRating = updatedAvg,
			updatedRatingCount = updatedCount,
		)
	}

	private suspend fun executeJsonMutation(
		method: String,
		url: String,
		payload: JSONObject?,
		pageCtx: PageContext,
	): JSONObject {
		val domain = domainProvider()
		val requestBody = when {
			method == "DELETE" && payload == null -> null
			payload != null -> payload.toString().toRequestBody(JSON_MEDIA_TYPE)
			else -> EMPTY
		}
		val requestBuilder = Request.Builder()
			.method(method, requestBody)
			.url(url)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", pageCtx.pageUrl)
			.header("Origin", "https://$domain")
			.tag(MangaSource::class.java, source)

		if (payload != null) {
			requestBuilder.header("Content-Type", "application/json; charset=utf-8")
		}
		pageCtx.csrfToken?.let { token ->
			requestBuilder.header("X-CSRF-Token", token)
		}

		return context.httpClient.newCall(requestBuilder.build()).await().use { response ->
			if (response.code == 401) {
				throw AuthRequiredException(source)
			}
			val rawBody = response.body.string()
			val json = rawBody.toJSONObjectOrNull()
			if (response.code == 403) {
				val errMsg = json?.optJSONObject("data")?.getStringOrNull("message")
					?: json?.getStringOrNull("message")
					?: json?.getStringOrNull("error")
				if (errMsg.isNullOrBlank() || isAuthFailureMessage(errMsg)) {
					throw AuthRequiredException(source)
				}
				throw ParseException(errMsg, url)
			}
			if (response.code == 422) {
				val dataObj = json?.optJSONObject("data") ?: json
				val errCode = dataObj?.getStringOrNull("code")
					?: dataObj?.getStringOrNull("error_code")
					?: json?.getStringOrNull("code")
					?: json?.getStringOrNull("error")
				val msg = dataObj?.getStringOrNull("message")
					?: json?.getStringOrNull("message")
					?: json?.getStringOrNull("error")
				val isProfanity = errCode.equals("profanity_warning", ignoreCase = true) ||
					dataObj?.getBooleanOrDefault("profanity_warning", false) == true ||
					json?.getBooleanOrDefault("profanity_warning", false) == true
				val finalMsg = when {
					msg != null -> msg
					isProfanity -> "Profanity warning: please revise your comment"
					else -> "Validation failed (HTTP 422)"
				}
				throw ParseException(finalMsg, url)
			}
			if (!response.isSuccessful) {
				val errMsg = json?.optJSONObject("data")?.getStringOrNull("message")
					?: json?.getStringOrNull("message")
					?: json?.getStringOrNull("error")
					?: "Request failed with HTTP ${response.code}"
				throw ParseException(errMsg, url)
			}
			json ?: JSONObject()
		}
	}

	internal fun extractPageContext(doc: Document, pageUrl: String): PageContext {
		val domain = domainProvider()
		val section = doc.selectFirst("div.comments-section, .comments-section, #comments-section")

		fun resolveAttr(attr: String): String? =
			section?.attrOrNull(attr)?.let { raw ->
				if (raw.contains("__ID__")) {
					raw.toAbsoluteUrl(domain)
				} else {
					raw.toAbsoluteUrl(domain)
				}
			}

		val rawUserId = section?.attrOrNull("data-current-user-id")
			?.takeUnless { it == "0" || it.equals("null", ignoreCase = true) || it.equals("false", ignoreCase = true) }
		val username = extractUsernameFromDoc(doc)
		val isAuthenticated = rawUserId != null || username != null

		val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attrOrNull("content")
			?: section?.selectFirst("input[name=csrf_token], input[name=_csrf], input[name=_token]")?.attrOrNull("value")

		val extraFields = LinkedHashMap<String, String>()
		if (section != null) {
			val primaryForm = section.selectFirst("form.comment-form") ?: section.selectFirst("form")
			val inputs = (primaryForm?.select("input") ?: emptyList()) +
				section.select("input[type=hidden], input[name*=_hp], input[name*=honeypot], input[name=website_url], input[name=content_policy]")
			for (input in inputs) {
				val name = input.attrOrNull("name") ?: continue
				if (name in RESERVED_COMMENT_FIELDS) continue
				if (extraFields.containsKey(name)) continue
				val type = input.attr("type").lowercase(Locale.ROOT)
				val isHoneypot = "_hp" in name ||
					"honeypot" in name.lowercase(Locale.ROOT) ||
					name.equals("website_url", ignoreCase = true) ||
					input.parents().any { it.hasClass("auth-honeypot") }
				val isPolicy = name == "content_policy"
				if (type == "hidden" || isHoneypot || isPolicy) {
					val value = if (type == "checkbox" && isPolicy) {
						input.attrOrNull("value") ?: "1"
					} else {
						input.attr("value")
					}
					extraFields[name] = value
				}
			}
		}

		val batchSize = section?.attrOrNull("data-comment-batch-size")?.toIntOrNull()?.takeIf { it > 0 }
			?: DEFAULT_BATCH_SIZE

		return PageContext(
			pageUrl = pageUrl,
			commentAction = resolveAttr("data-comment-action"),
			commentLoadUrl = resolveAttr("data-comment-load-url"),
			likeUrlTemplate = resolveAttr("data-comment-like-url-template"),
			deleteUrlTemplate = resolveAttr("data-comment-delete-url-template"),
			editUrlTemplate = resolveAttr("data-comment-edit-url-template"),
			repliesUrlTemplate = resolveAttr("data-comment-replies-url-template"),
			threadUrlTemplate = resolveAttr("data-comment-thread-url-template"),
			currentUserId = rawUserId,
			currentUsername = username,
			batchSize = batchSize,
			commentKind = section?.attrOrNull("data-comment-kind"),
			csrfToken = csrfToken,
			extraFormFields = extraFields,
			isAuthenticated = isAuthenticated,
		)
	}

	private fun parseCommentsArray(array: JSONArray, pageCtx: PageContext): List<SourceComment> {
		val list = ArrayList<SourceComment>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			list.add(parseCommentObject(jo, pageCtx))
		}
		return list
	}

	private fun parseCommentObject(jo: JSONObject, pageCtx: PageContext): SourceComment {
		val domain = domainProvider()
		val id = jo.getStringOrNull("id") ?: "0"
		val userId = jo.getStringOrNull("user_id")
		val parentId = jo.getStringOrNull("parent_id")?.takeUnless { it == "0" }
		val username = jo.getStringOrNull("username") ?: jo.getStringOrNull("author_name") ?: "Anonim"
		val avatarUrl = jo.getStringOrNull("avatar_url")?.toAbsoluteUrl(domain)
		val bodyHtml = jo.getStringOrNull("body_html")
		val rawBody = jo.getStringOrNull("body")
		val body = rawBody ?: bodyHtml?.let { Jsoup.parseBodyFragment(it).text().nullIfEmpty() } ?: ""

		val reviewScore = jo.opt("review_score").asNullableInt()
		val isRatingOnly = jo.getBooleanOrDefault("is_rating_only", false)
		val isReview = jo.getBooleanOrDefault("is_review", false)
		val isPinned = jo.getBooleanOrDefault("is_pinned", false)
		val isEdited = jo.getBooleanOrDefault("edited", false) || jo.getBooleanOrDefault("is_edited", false)
		val isSpoiler = jo.getBooleanOrDefault("is_spoiler", false) ||
			rawBody?.contains("class=\"spoiler\"", ignoreCase = true) == true ||
			bodyHtml?.contains("class=\"spoiler\"", ignoreCase = true) == true
		val createdAt = jo.getStringOrNull("created_at")
		val likeCount = jo.getIntOrDefault("like_count", 0)
		val likedByCurrentUser = jo.getBooleanOrDefault("liked_by_current_user", false)
		val canDelete = jo.getBooleanOrDefault("can_delete", false)
		val canEdit = jo.getBooleanOrDefault("can_edit", false)

		val repliesArray = jo.optJSONArray("replies")
		val replies = if (repliesArray != null && repliesArray.length() > 0) {
			parseCommentsArray(repliesArray, pageCtx)
		} else {
			emptyList()
		}
		val replyTotal = maxOf(
			jo.getIntOrDefault("reply_total", 0),
			jo.getIntOrDefault("reply_count", 0),
			jo.getIntOrDefault("thread_reply_total", 0),
			replies.size,
		)
		val threadReplyTotal = jo.getIntOrDefault("thread_reply_total", 0)

		val isOwn = (pageCtx.currentUserId != null && userId == pageCtx.currentUserId) || canEdit || canDelete
		val meta = buildMap {
			if (userId != null) put("user_id", userId)
			if (reviewScore != null && reviewScore > 0) put("review_score", reviewScore.toString())
			if (isRatingOnly) put("is_rating_only", "true")
			if (isReview) put("is_review", "true")
			if (threadReplyTotal > 0) put("thread_reply_total", threadReplyTotal.toString())
		}

		return SourceComment(
			id = id,
			authorName = username,
			authorAvatarUrl = avatarUrl,
			body = body,
			bodyHtml = bodyHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isEdited = isEdited,
			isSpoiler = isSpoiler,
			isPinned = isPinned,
			likesCount = likeCount,
			userVote = if (likedByCurrentUser) 1 else 0,
			repliesCount = replyTotal,
			parentId = parentId,
			replies = replies,
			isOwnComment = isOwn,
			canReply = pageCtx.isAuthenticated,
			canLike = pageCtx.isAuthenticated && pageCtx.likeUrlTemplate != null,
			canEdit = canEdit,
			canDelete = canDelete,
			rawMeta = meta,
		)
	}

	private fun extractUsernameFromDoc(doc: Document): String? {
		doc.selectFirst("[data-current-username]")?.let { el ->
			return el.attr("data-current-username").trim().nullIfEmpty()
		}
		val link = doc.selectFirst("a.site-profile-link")
			?: doc.select("a[href^=\"/@\"]").firstOrNull { el ->
				!el.hasClass("comment-author") &&
					!el.hasClass("feed-user") &&
					!el.hasClass("feed-avatar-link") &&
					!el.hasClass("feed-user-name") &&
					el.parents().none { p -> p.hasClass("comments-section") || p.id() == "comments-section" }
			}
			?: return null
		return link.textOrNull()?.removePrefix("@")?.trim()?.nullIfEmpty()
			?: link.attrOrNull("href")?.substringAfter("/@", "")?.substringBefore("/")?.nullIfEmpty()
	}

	private fun extractCurrentUserId(doc: Document): String? {
		return doc.selectFirst("div.comments-section[data-current-user-id], [data-current-user-id]")
			?.attrOrNull("data-current-user-id")
			?.takeUnless { it == "0" || it.equals("null", ignoreCase = true) || it.equals("false", ignoreCase = true) }
	}

	private fun parseRatingSubCount(subText: String?): Int? {
		val text = subText?.trim()?.nullIfEmpty() ?: return null
		// Avoid grabbing the "/ 5" scale number if present
		val cleaned = REGEX_SLASH_SCALE.replace(text, " ")
		return parseCompactCount(cleaned)?.toInt()
	}

	private data class JsonLdStats(
		val ratingValue: Float?,
		val bestRating: Int?,
		val ratingCount: Int?,
		val viewCount: Long?,
	)

	private fun extractAggregateRatingFromJsonLd(doc: Document): JsonLdStats? {
		for (script in doc.select("script[type=\"application/ld+json\"]")) {
			val raw = script.data().trim()
			if (raw.isEmpty()) continue
			val objects = ArrayList<JSONObject>()
			raw.toJSONObjectOrNull()?.let { root ->
				objects.add(root)
				root.optJSONArray("@graph")?.let { graph ->
					for (i in 0 until graph.length()) {
						graph.optJSONObject(i)?.let(objects::add)
					}
				}
			} ?: raw.toJSONArrayOrNull()?.let { arr ->
				for (i in 0 until arr.length()) {
					arr.optJSONObject(i)?.let(objects::add)
				}
			}
			for (obj in objects) {
				val agg = if (obj.optString("@type").equals("AggregateRating", ignoreCase = true)) {
					obj
				} else {
					obj.optJSONObject("aggregateRating")
				}
				val viewStat = obj.optJSONObject("interactionStatistic")?.let { stat ->
					stat.opt("userInteractionCount")?.toString()?.let(::parseCompactCount)
				}
				if (agg != null) {
					val ratingVal = agg.opt("ratingValue")?.toString()?.replace(',', '.')?.toFloatOrNull()
					val bestVal = agg.opt("bestRating")?.toString()?.toIntOrNull()
					val countVal = (agg.opt("ratingCount") ?: agg.opt("reviewCount"))?.toString()?.toIntOrNull()
					return JsonLdStats(
						ratingValue = ratingVal,
						bestRating = bestVal,
						ratingCount = countVal,
						viewCount = viewStat,
					)
				} else if (viewStat != null) {
					return JsonLdStats(null, null, null, viewStat)
				}
			}
		}
		return null
	}

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val domain = domainProvider()
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}

	private fun Any?.asNullableInt(): Int? = when (this) {
		null, JSONObject.NULL -> null
		is Number -> toInt()
		is String -> toIntOrNull()
		else -> null
	}

	private suspend fun awaitHoneypotDwell(pageCtx: PageContext, forceMinimum: Boolean = false) {
		val hasHoneypotToken = pageCtx.extraFormFields.keys.any { key ->
			key.contains("_hp", ignoreCase = true) || key.contains("honeypot", ignoreCase = true)
		}
		if (!hasHoneypotToken && !forceMinimum) return
		val elapsed = if (forceMinimum) 0L else (System.currentTimeMillis() - pageCtx.fetchedAtMs).coerceAtLeast(0L)
		val remaining = MIN_HONEYPOT_DWELL_MS - elapsed
		if (remaining > 0L) {
			delay(remaining)
		}
	}

	private fun isAuthFailureMessage(message: String): Boolean {
		val lower = message.lowercase(Locale.ROOT)
		return lower.contains("forbidden") ||
			lower.contains("unauthorized") ||
			lower.contains("unauthenticated") ||
			lower.contains("giriş") ||
			lower.contains("oturum") ||
			lower.contains("login") ||
			lower.contains("sign in")
	}

	private fun isHoneypotRetryableError(message: String?): Boolean {
		val lower = message?.lowercase(Locale.ROOT) ?: return false
		if (lower.contains("profanity") || lower.contains("uygunsuz")) return false
		return lower.contains("tekrar deneyin") ||
			lower.contains("bekleyin") ||
			lower.contains("wait") ||
			lower.contains("honeypot") ||
			lower.contains("çok hızlı") ||
			lower.contains("too fast") ||
			lower.contains("spam")
	}

	private fun escapeHtml(text: String): String = buildString(text.length + 16) {
		for (ch in text) {
			when (ch) {
				'&' -> append("&amp;")
				'<' -> append("&lt;")
				'>' -> append("&gt;")
				'"' -> append("&quot;")
				'\'' -> append("&#39;")
				else -> append(ch)
			}
		}
	}

	private companion object {
		const val DEFAULT_BATCH_SIZE = 10
		const val REPLIES_BATCH_SIZE = 10
		const val MIN_HONEYPOT_DWELL_MS = 3300L
		const val PAGE_CONTEXT_CACHE_TTL_MS = 180_000L
		const val MAX_CACHED_CONTEXTS = 24
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val RESERVED_COMMENT_FIELDS = setOf("body", "parent_id", "score", "csrf_token", "_csrf", "_token")
		val REGEX_SLASH_SCALE = Regex("""/\s*\d+\b""")
	}
}
