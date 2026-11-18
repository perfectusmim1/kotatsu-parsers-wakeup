package org.koitharu.kotatsu.parsers.site.community

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.EMPTY
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getDoubleOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class NaverCommunityHelper(
	private val context: MangaLoaderContext,
	private val webClient: WebClient,
	private val source: MangaParserSource,
	private val serviceTicketId: String, // "comic_webtoon" or "comic_challenge"
	private val pagePrefix: String,      // "webtoon" or "challenge"
	private val pathSegmentProvider: () -> String, // "webtoon", "bestChallenge", or "challenge"
	private val mobileDomainProvider: () -> String, // e.g. "m.comic.naver.com"
	private val apiDomainProvider: () -> String,    // e.g. "comic.naver.com"
	private val mobileHeadersProvider: () -> Headers,
	private val apiHeadersProvider: () -> Headers,
) {

	val authUrl: String = "https://nid.naver.com/nidlogin.login?mode=form&url=https%3A%2F%2Fm.comic.naver.com%2F"

	val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = false,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.TOP,
		),
	)

	private val defaultEpisodeCache = ConcurrentHashMap<Long, Int>()
	private val userMutex = Mutex()

	@Volatile
	private var cachedUser: CachedUser? = null

	private data class CachedUser(
		val cuid: String?,
		val userId: String?,
		val displayName: String,
		val avatarUrl: String?,
		val nidAutSnapshot: String?,
		val fetchedAtMs: Long = System.currentTimeMillis(),
	)

	suspend fun isAuthorized(): Boolean {
		if (!hasNaverAuthCookies()) {
			cachedUser = null
			return false
		}
		val mobileDomain = mobileDomainProvider()
		val url = "https://$mobileDomain/api/loginStatus"
		return runCatchingCancellable {
			val request = Request.Builder()
				.post(FormBody.Builder().build())
				.url(url)
				.headers(mobileHeadersProvider())
				.header("Accept", "application/json")
				.header("Referer", "https://$mobileDomain/")
				.header("Origin", "https://$mobileDomain")
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) {
					return@use false
				}
				val json = response.body.string().toJSONObjectOrNull() ?: return@use false
				val result = json.optJSONObject("result") ?: json
				result.getBooleanOrDefault("login", false) ||
					result.getBooleanOrDefault("naverLogin", false)
			}
		}.getOrDefault(false)
	}

	suspend fun getUsername(): String {
		return fetchCurrentUser(requireAuth = true)?.displayName
			?: throw AuthRequiredException(source)
	}

	suspend fun logout() {
		cachedUser = null
		runCatchingCancellable {
			val mobileDomain = mobileDomainProvider()
			val request = Request.Builder()
				.get()
				.url("https://nid.naver.com/nidlogin.logout")
				.headers(mobileHeadersProvider())
				.header("Referer", "https://$mobileDomain/")
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(request).await().close()
		}
		val hosts = linkedSetOf(
			"nid.naver.com",
			"naver.com",
			mobileDomainProvider(),
			apiDomainProvider(),
		)
		for (host in hosts) {
			for (name in NAVER_AUTH_COOKIE_NAMES) {
				context.cookieJar.insertCookies(
					host,
					"$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
					"$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.naver.com",
					"$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.naver.com; Secure",
				)
			}
		}
		context.clearAuthValues(source)
	}

	suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val titleId = extractTitleId(manga)
		val explicitEpisodeNo = extractEpisodeNo(chapter)
		val apiDomain = apiDomainProvider()
		val mobileDomain = mobileDomainProvider()

		return coroutineScope {
			val infoDeferred = async {
				runCatchingCancellable {
					fetchApiJson("https://$apiDomain/api/article/list/info?titleId=$titleId")
				}.getOrNull()
			}
			val listDeferred = async {
				runCatchingCancellable {
					fetchApiJson("https://$apiDomain/api/article/list?titleId=$titleId&page=1&sort=DESC")
				}.getOrNull()
			}
			val userDeferred = async {
				if (hasNaverAuthCookies()) {
					runCatchingCancellable { resolveCurrentUser(titleId, explicitEpisodeNo) }.getOrNull()
				} else {
					cachedUser = null
					null
				}
			}
			val explicitCountDeferred = explicitEpisodeNo?.let { epNo ->
				val pageId = buildPageId(titleId, epNo)
				async {
					runCatchingCancellable {
						fetchActiveRootPostCount(mobileDomain, titleId, epNo, pageId)
					}.getOrNull()
				}
			}

			val infoJson = infoDeferred.await()
			val listJson = listDeferred.await()
			val currentUser = userDeferred.await()

			val episodeNo = explicitEpisodeNo ?: pickDefaultEpisodeNo(listJson, infoJson).also { resolved ->
				if (resolved.fromApi) {
					cacheDefaultEpisode(titleId, resolved.episodeNo)
				}
			}.episodeNo

			val pageId = buildPageId(titleId, episodeNo)
			val activeRootPostCount = if (explicitCountDeferred != null) {
				explicitCountDeferred.await()
			} else {
				runCatchingCancellable {
					fetchActiveRootPostCount(mobileDomain, titleId, episodeNo, pageId)
				}.getOrNull()
			}

			val followCount = infoJson?.opt("favoriteCount")
				?.takeUnless { it === JSONObject.NULL }
				?.toString()
				?.toLongOrNull()
				?.takeIf { it >= 0L }

			val rawRating = computeRawRating(manga, listJson, explicitEpisodeNo)
			val averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) }

			SourceMangaCommunityInfo(
				averageRating = averageRating,
				rawRating = rawRating,
				rawScale = 10,
				followCount = followCount,
				commentCount = if (chapter == null) activeRootPostCount else null,
				chapterCommentCount = if (chapter != null) activeRootPostCount else null,
				canCurrentUserRate = false,
				canCurrentUserComment = currentUser != null,
				currentUsername = currentUser?.displayName,
			)
		}
	}

	suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter? = null,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
		cursor: String? = null,
	): SourceCommentPage {
		if (page > 1 && cursor.isNullOrBlank()) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}
		if (sort == SourceCommentSort.TOP && page > 1) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}

		val titleId = extractTitleId(manga)
		val episodeNo = resolveEpisodeNo(titleId, chapter)
		val pageId = buildPageId(titleId, episodeNo)
		val mobileDomain = mobileDomainProvider()
		val referer = buildWccReferer(mobileDomain, titleId, episodeNo)

		val url = if (sort == SourceCommentSort.TOP) {
			"https://$mobileDomain/comment/api/community/v1/page/${pageId.urlEncoded()}/top-recent-posts" +
				"?topCount=$DEFAULT_PAGE_SIZE&recentTopCount=0&pinRepresentation=distinct"
		} else {
			buildString {
				append("https://").append(mobileDomain)
				append("/comment/api/community/v2/posts?pageId=").append(pageId.urlEncoded())
				append("&pinRepresentation=none&displayBlindCommentAsService=false")
				append("&prevSize=0&nextSize=").append(DEFAULT_PAGE_SIZE)
				if (!cursor.isNullOrBlank()) {
					append("&cursor=").append(cursor.urlEncoded())
				}
			}
		}

		return coroutineScope {
			val userDeferred = async {
				if (hasNaverAuthCookies()) {
					runCatchingCancellable { resolveCurrentUser(titleId, episodeNo) }.getOrNull()
				} else {
					cachedUser = null
					null
				}
			}
			val jsonDeferred = async {
				executeWccRequest(
					method = "GET",
					url = url,
					referer = referer,
					mobileDomain = mobileDomain,
					payload = null,
				)
			}

			val json = jsonDeferred.await()
			val currentUser = userDeferred.await()
			val result = json.optJSONObject("result")

			if (sort == SourceCommentSort.TOP) {
				val seen = HashSet<String>()
				val comments = ArrayList<SourceComment>()
				fun appendArray(arr: JSONArray?, forcePinned: Boolean, forceTop: Boolean) {
					if (arr == null) return
					for (i in 0 until arr.length()) {
						val jo = arr.optJSONObject(i) ?: continue
						val parsed = parseWccPost(
							post = jo,
							currentUser = currentUser,
							pageId = pageId,
							forcePinned = forcePinned,
							forceTop = forceTop,
						) ?: continue
						if (seen.add(parsed.id)) {
							comments.add(parsed)
						}
					}
				}
				appendArray(result?.optJSONArray("pins"), forcePinned = true, forceTop = false)
				appendArray(result?.optJSONArray("tops"), forcePinned = false, forceTop = true)
				val total = result?.opt("activeRootPostCount")
					?.takeUnless { it === JSONObject.NULL }
					?.toString()
					?.toIntOrNull()
				SourceCommentPage(
					comments = comments,
					page = page,
					hasNextPage = false,
					totalCount = total,
					engine = SourceCommentEngine.NATIVE,
				)
			} else {
				val posts = result?.optJSONArray("posts") ?: JSONArray()
				val comments = ArrayList<SourceComment>(posts.length())
				for (i in 0 until posts.length()) {
					val jo = posts.optJSONObject(i) ?: continue
					parseWccPost(jo, currentUser, pageId)?.let(comments::add)
				}
				val nextCursor = result?.optJSONObject("pagination")?.getStringOrNull("next")
				val total = result?.opt("activeRootPostCount")
					?.takeUnless { it === JSONObject.NULL }
					?.toString()
					?.toIntOrNull()
				SourceCommentPage(
					comments = comments,
					page = page,
					hasNextPage = nextCursor != null,
					totalCount = total,
					engine = SourceCommentEngine.NATIVE,
					nextCursor = nextCursor,
				)
			}
		}
	}

	suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
		page: Int = 1,
		cursor: String? = null,
	): SourceCommentPage {
		if (page > 1 && cursor.isNullOrBlank()) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}

		val target = resolveCommentPageTarget(manga, comment, chapter)
		val mobileDomain = mobileDomainProvider()
		val referer = buildWccReferer(mobileDomain, target.titleId, target.episodeNo)
		val url = buildString {
			append("https://").append(mobileDomain)
			append("/comment/api/community/v2/post/").append(comment.id.urlEncoded())
			append("/child-posts?sort=oldest&displayBlindCommentAsService=false")
			append("&prevSize=0&nextSize=").append(DEFAULT_PAGE_SIZE)
			if (!cursor.isNullOrBlank()) {
				append("&cursor=").append(cursor.urlEncoded())
			}
		}

		return coroutineScope {
			val userDeferred = async {
				if (hasNaverAuthCookies()) {
					runCatchingCancellable { resolveCurrentUser(target.titleId, target.episodeNo) }.getOrNull()
				} else {
					cachedUser = null
					null
				}
			}
			val jsonDeferred = async {
				executeWccRequest(
					method = "GET",
					url = url,
					referer = referer,
					mobileDomain = mobileDomain,
					payload = null,
				)
			}

			val json = jsonDeferred.await()
			val currentUser = userDeferred.await()
			val result = json.optJSONObject("result")
			val posts = result?.optJSONArray("posts") ?: JSONArray()
			val replies = ArrayList<SourceComment>(posts.length())
			for (i in 0 until posts.length()) {
				val jo = posts.optJSONObject(i) ?: continue
				parseWccPost(
					post = jo,
					currentUser = currentUser,
					pageId = target.pageId,
					fallbackParentId = comment.id,
				)?.let(replies::add)
			}
			val nextCursor = result?.optJSONObject("pagination")?.getStringOrNull("next")
			val total = result?.opt("activeChildPostCount")
				?.takeUnless { it === JSONObject.NULL }
				?.toString()
				?.toIntOrNull()

			SourceCommentPage(
				comments = replies,
				page = page,
				hasNextPage = nextCursor != null,
				totalCount = total,
				engine = SourceCommentEngine.NATIVE,
				nextCursor = nextCursor,
			)
		}
	}

	suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter? = null,
		parentComment: SourceComment? = null,
		isSpoiler: Boolean = false,
	): SourceComment {
		if (!hasNaverAuthCookies()) {
			throw AuthRequiredException(source)
		}
		val titleId = extractTitleId(manga)
		val episodeNo = resolveEpisodeNo(titleId, chapter)
		val pageId = buildPageId(titleId, episodeNo)
		val mobileDomain = mobileDomainProvider()
		val referer = buildWccReferer(mobileDomain, titleId, episodeNo)
		val url = "https://$mobileDomain/comment/api/community/v2/post"

		val settings = JSONObject().apply {
			put("reply", if (parentComment != null) "OFF" else "ON")
			put("reaction", "ON")
			put("spoilerFilter", if (isSpoiler) "ON" else "OFF")
		}
		val payload = JSONObject().apply {
			put("pageId", pageId)
			if (parentComment != null) {
				put("parentId", parentComment.id)
			}
			put("settings", settings)
			put("title", "")
			put("body", text)
		}

		val json = executeWccRequest(
			method = "POST",
			url = url,
			referer = referer,
			mobileDomain = mobileDomain,
			payload = payload,
		)
		val result = json.optJSONObject("result")
		val postObj = result?.optJSONObject("post") ?: result
		val existingUser = cachedUser ?: if (postObj?.optJSONObject("createdBy")?.getStringOrNull("name") == null) {
			runCatchingCancellable { resolveCurrentUser(titleId, episodeNo) }.getOrNull()
		} else {
			null
		}

		if (postObj != null && postObj.getStringOrNull("id") != null) {
			parseWccPost(
				post = postObj,
				currentUser = existingUser,
				pageId = pageId,
				fallbackParentId = parentComment?.id,
				forceOwn = true,
				forceSpoiler = isSpoiler,
			)?.let { return it }
		}

		return SourceComment(
			id = postObj?.getStringOrNull("id") ?: "naver-${System.currentTimeMillis()}",
			authorName = existingUser?.displayName ?: "You",
			authorAvatarUrl = existingUser?.avatarUrl,
			body = text,
			createdAtEpochMs = System.currentTimeMillis(),
			isSpoiler = isSpoiler,
			parentId = parentComment?.id,
			isOwnComment = true,
			canReply = parentComment == null,
			canLike = true,
			canDislike = true,
			canEdit = false,
			canDelete = true,
			rawMeta = mapOf("pageId" to pageId),
		)
	}

	suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	) {
		if (!hasNaverAuthCookies()) {
			throw AuthRequiredException(source)
		}
		val target = resolveCommentPageTarget(manga, comment, chapter)
		val mobileDomain = mobileDomainProvider()
		val referer = buildWccReferer(mobileDomain, target.titleId, target.episodeNo)
		val url = "https://$mobileDomain/comment/api/community/v2/post/${comment.id.urlEncoded()}"

		executeWccRequest(
			method = "DELETE",
			url = url,
			referer = referer,
			mobileDomain = mobileDomain,
			payload = null,
		)
	}

	suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean = true,
		chapter: MangaChapter? = null,
	): SourceComment {
		if (!hasNaverAuthCookies()) {
			throw AuthRequiredException(source)
		}
		val target = resolveCommentPageTarget(manga, comment, chapter)
		val mobileDomain = mobileDomainProvider()
		val referer = buildWccReferer(mobileDomain, target.titleId, target.episodeNo)

		val emotion = if (isLike) "like" else "dislike"
		val oppositeEmotion = if (isLike) "dislike" else "like"
		val wasLiked = comment.userVote > 0
		val wasDisliked = comment.userVote < 0
		val wasActive = if (isLike) wasLiked else wasDisliked
		val hadOpposite = if (isLike) wasDisliked else wasLiked
		val method = if (wasActive) "DELETE" else "PUT"
		val encodedCommentId = comment.id.urlEncoded()
		val encodedPageId = target.pageId.urlEncoded()
		val url = "https://$mobileDomain/comment/api/community/v2/reaction/post_like" +
			"/channel/$encodedPageId/content/$encodedCommentId/emotion/$emotion"

		val json = try {
			executeWccRequest(
				method = method,
				url = url,
				referer = referer,
				mobileDomain = mobileDomain,
				payload = null,
				sendEmptyBodyForWrite = true,
			)
		} catch (e: ParseException) {
			if (wasActive || !hadOpposite) {
				throw e
			}
			val oppositeUrl = "https://$mobileDomain/comment/api/community/v2/reaction/post_like" +
				"/channel/$encodedPageId/content/$encodedCommentId/emotion/$oppositeEmotion"
			executeWccRequest(
				method = "DELETE",
				url = oppositeUrl,
				referer = referer,
				mobileDomain = mobileDomain,
				payload = null,
			)
			executeWccRequest(
				method = "PUT",
				url = url,
				referer = referer,
				mobileDomain = mobileDomain,
				payload = null,
				sendEmptyBodyForWrite = true,
			)
		}

		val result = json.optJSONObject("result")
		val reactionsArr = result?.optJSONArray("reactions")
		val emotionsArr = result?.optJSONArray("emotions")
		if (reactionsArr != null || emotionsArr != null) {
			val reactionSummary = if (reactionsArr != null) {
				extractReactionSummary(reactionsArr)
			} else {
				extractEmotionsSummary(emotionsArr)
			}
			if (reactionSummary != null) {
				return comment.copy(
					likesCount = reactionSummary.likesCount,
					dislikesCount = reactionSummary.dislikesCount,
					userVote = reactionSummary.userVote,
				)
			}
		}

		val serverCount = result?.opt("count")
			?.takeUnless { it === JSONObject.NULL }
			?.toString()
			?.toIntOrNull()
			?.coerceAtLeast(0)

		val newLikes: Int
		val newDislikes: Int
		val newVote: Int
		if (isLike) {
			if (wasLiked) {
				newLikes = serverCount ?: (comment.likesCount - 1).coerceAtLeast(0)
				newDislikes = comment.dislikesCount
				newVote = 0
			} else {
				newLikes = serverCount ?: (comment.likesCount + 1)
				newDislikes = if (wasDisliked) (comment.dislikesCount - 1).coerceAtLeast(0) else comment.dislikesCount
				newVote = 1
			}
		} else {
			if (wasDisliked) {
				newLikes = comment.likesCount
				newDislikes = serverCount ?: (comment.dislikesCount - 1).coerceAtLeast(0)
				newVote = 0
			} else {
				newLikes = if (wasLiked) (comment.likesCount - 1).coerceAtLeast(0) else comment.likesCount
				newDislikes = serverCount ?: (comment.dislikesCount + 1)
				newVote = -1
			}
		}

		return comment.copy(
			likesCount = newLikes,
			dislikesCount = newDislikes,
			userVote = newVote,
		)
	}

	suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceWidgetContext? {
		val titleId = extractTitleIdOrNull(manga) ?: return null
		val episodeNo = resolveEpisodeNo(titleId, chapter)
		val mobileDomain = mobileDomainProvider()
		val pathSeg = pathSegmentProvider()
		val pageId = buildPageId(titleId, episodeNo)
		val widgetUrl = "https://$mobileDomain/scrolltoon/comment?titleId=$titleId&no=$episodeNo"
		val pageUrl = "https://$mobileDomain/$pathSeg/detail?titleId=$titleId&no=$episodeNo"

		return SourceWidgetContext(
			engine = SourceCommentEngine.CUSTOM_WIDGET,
			pageUrl = pageUrl,
			widgetUrl = widgetUrl,
			identifier = pageId,
			shortname = serviceTicketId,
			title = chapter?.title?.takeIf { it.isNotBlank() } ?: manga.title,
			baseUrl = "https://$mobileDomain/",
			extraHeaders = mapOf("Referer" to pageUrl),
			allowedHostSuffixes = setOf("naver.com", "pstatic.net"),
		)
	}

	private suspend fun resolveCurrentUser(titleId: Long?, episodeNo: Int?): CachedUser? {
		val nidAut = getCookieValue("NID_AUT")
		val nidSes = getCookieValue("NID_SES")
		if (nidAut.isNullOrBlank() || nidSes.isNullOrBlank()) {
			cachedUser = null
			return null
		}
		cachedUser?.let { existing ->
			val age = System.currentTimeMillis() - existing.fetchedAtMs
			if (existing.nidAutSnapshot == nidAut && age in 0L..USER_CACHE_TTL_MS) {
				return existing
			}
		}
		return userMutex.withLock {
			val latestNidAut = getCookieValue("NID_AUT")
			val latestNidSes = getCookieValue("NID_SES")
			if (latestNidAut.isNullOrBlank() || latestNidSes.isNullOrBlank()) {
				cachedUser = null
				return@withLock null
			}
			cachedUser?.let { existing ->
				val age = System.currentTimeMillis() - existing.fetchedAtMs
				if (existing.nidAutSnapshot == latestNidAut && age in 0L..USER_CACHE_TTL_MS) {
					return@withLock existing
				}
			}
			fetchCurrentUser(requireAuth = false, titleId = titleId, episodeNo = episodeNo)
		}
	}

	private suspend fun fetchCurrentUser(
		requireAuth: Boolean,
		titleId: Long? = null,
		episodeNo: Int? = null,
	): CachedUser? {
		val mobileDomain = mobileDomainProvider()
		val url = "https://$mobileDomain/comment/api/community/v1/users/my"
		val referer = if (titleId != null && episodeNo != null) {
			buildWccReferer(mobileDomain, titleId, episodeNo)
		} else {
			"https://$mobileDomain/"
		}
		val request = Request.Builder()
			.get()
			.url(url)
			.headers(mobileHeadersProvider())
			.header("Service-Ticket-Id", serviceTicketId)
			.header("Referer", referer)
			.header("Origin", "https://$mobileDomain")
			.header("Accept", "application/json")
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				cachedUser = null
				if (requireAuth) throw AuthRequiredException(source)
				return null
			}
			val raw = response.body.string()
			if (!response.isSuccessful) {
				cachedUser = null
				if (requireAuth) throw AuthRequiredException(source)
				return null
			}
			raw.toJSONObjectOrNull()
		}

		val status = json?.getStringOrNull("status")
		val result = json?.optJSONObject("result")
		if (!status.equals("success", ignoreCase = true) || result == null) {
			cachedUser = null
			if (requireAuth) throw AuthRequiredException(source)
			return null
		}

		val userObj = result.optJSONObject("user") ?: result
		val cuid = userObj.getStringOrNull("cuid") ?: userObj.getStringOrNull("id")
		val userId = userObj.getStringOrNull("userId") ?: userObj.getStringOrNull("idNo")
		val displayName = userObj.getStringOrNull("name")
			?: userObj.getStringOrNull("nickname")
			?: userObj.getStringOrNull("maskedUserId")
			?: cuid
			?: userId
		if (displayName.isNullOrBlank()) {
			cachedUser = null
			if (requireAuth) throw AuthRequiredException(source)
			return null
		}

		val avatarUrl = extractAvatarUrl(userObj)
		val user = CachedUser(
			cuid = cuid,
			userId = userId,
			displayName = displayName,
			avatarUrl = avatarUrl,
			nidAutSnapshot = getCookieValue("NID_AUT"),
		)
		cachedUser = user
		return user
	}

	private suspend fun fetchApiJson(url: String): JSONObject {
		return webClient.httpGet(url, apiHeadersProvider()).parseJson()
	}

	private suspend fun fetchActiveRootPostCount(
		mobileDomain: String,
		titleId: Long,
		episodeNo: Int,
		pageId: String,
	): Int? {
		val url = "https://$mobileDomain/comment/api/community/v1/pages/activity/count/?pageIds=${pageId.urlEncoded()}"
		val referer = buildWccReferer(mobileDomain, titleId, episodeNo)
		val json = executeWccRequest(
			method = "GET",
			url = url,
			referer = referer,
			mobileDomain = mobileDomain,
			payload = null,
		)
		val countList = json.optJSONObject("result")?.optJSONArray("countList") ?: return null
		for (i in 0 until countList.length()) {
			val item = countList.optJSONObject(i) ?: continue
			if (item.getStringOrNull("pageId") == pageId || i == 0) {
				val active = item.opt("activeRootPostCount")
					?.takeUnless { it === JSONObject.NULL }
					?.toString()
					?.toIntOrNull()
				if (active != null && active >= 0) return active
				val root = item.opt("rootPostCount")
					?.takeUnless { it === JSONObject.NULL }
					?.toString()
					?.toIntOrNull()
				if (root != null && root >= 0) return root
			}
		}
		return null
	}

	private suspend fun executeWccRequest(
		method: String,
		url: String,
		referer: String,
		mobileDomain: String,
		payload: JSONObject?,
		sendEmptyBodyForWrite: Boolean = false,
	): JSONObject {
		val requestBody = when {
			payload != null -> payload.toString().toRequestBody(JSON_MEDIA_TYPE)
			method == "PUT" || (sendEmptyBodyForWrite && method != "GET" && method != "DELETE") -> EMPTY
			else -> null
		}
		val builder = Request.Builder()
			.method(method, requestBody)
			.url(url)
			.headers(mobileHeadersProvider())
			.header("Service-Ticket-Id", serviceTicketId)
			.header("Referer", referer)
			.header("Origin", "https://$mobileDomain")
			.header("Accept", "application/json")
			.tag(MangaSource::class.java, source)

		if (payload != null) {
			builder.header("Content-Type", "application/json; charset=utf-8")
		}

		return context.httpClient.newCall(builder.build()).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				cachedUser = null
				throw AuthRequiredException(source)
			}
			val rawBody = response.body.string()
			val json = rawBody.toJSONObjectOrNull()
			val status = json?.getStringOrNull("status")
			val isFailureStatus = status != null && !status.equals("success", ignoreCase = true)

			if (!response.isSuccessful || isFailureStatus) {
				val errorObj = json?.optJSONObject("error")
				val errCode = errorObj?.getStringOrNull("code")
					?: json?.getStringOrNull("errorCode")
					?: json?.getStringOrNull("code")
				val errMsg = errorObj?.getStringOrNull("message")
					?: json?.getStringOrNull("message")
					?: json?.getStringOrNull("error")
				if (isAuthFailure(errCode, errMsg)) {
					cachedUser = null
					throw AuthRequiredException(source)
				}
				throw ParseException(
					errMsg ?: "Naver WCC request failed (HTTP ${response.code})",
					url,
				)
			}
			json ?: JSONObject()
		}
	}

	private fun parseWccPost(
		post: JSONObject,
		currentUser: CachedUser?,
		pageId: String,
		fallbackParentId: String? = null,
		forcePinned: Boolean = false,
		forceTop: Boolean = false,
		forceOwn: Boolean = false,
		forceSpoiler: Boolean = false,
	): SourceComment? {
		val id = post.getStringOrNull("id") ?: return null
		val parentId = post.getStringOrNull("parentId")?.takeUnless { it == id }
			?: fallbackParentId?.takeUnless { it == id }
		val status = post.getStringOrNull("status")
		val isService = status == null || status.equals("SERVICE", ignoreCase = true)

		val rawBody = post.getStringOrNull("body").orEmpty().replace("\r\n", "\n").trim()
		val body = when {
			isService -> rawBody
			status.equals("DELETE", ignoreCase = true) || status.equals("DELETED", ignoreCase = true) ->
				"[삭제된 댓글입니다]"
			else -> "[블라인드 처리된 댓글입니다]"
		}

		val settingsObj = post.optJSONObject("settings")
		val isSpoiler = isService && (
			forceSpoiler ||
				post.getBooleanOrDefault("isSpoiler", false) ||
				settingsObj?.getStringOrNull("spoilerFilter").equals("ON", ignoreCase = true)
			)

		val createdAtEpochMs = parseWccEpochMs(post.opt("createdAt"))

		val createdBy = post.optJSONObject("createdBy")
		val authorName = createdBy?.getStringOrNull("name")
			?: createdBy?.getStringOrNull("nickname")
			?: createdBy?.getStringOrNull("maskedUserId")
			?: if (forceOwn) currentUser?.displayName ?: "You" else "익명"
		val authorAvatarUrl = createdBy?.let(::extractAvatarUrl)
			?: if (forceOwn) currentUser?.avatarUrl else null

		val isPinned = post.getBooleanOrDefault("isPinned", false) || forcePinned
		val isTop = post.getBooleanOrDefault("isTop", false) || forceTop
		val isCreator = post.getBooleanOrDefault("isCreator", false) ||
			createdBy?.getBooleanOrDefault("isCreator", false) == true
		val authorRole = when {
			isCreator && isTop -> "Creator · BEST"
			isCreator -> "Creator"
			isTop -> "BEST"
			else -> null
		}

		val repliesCount = post.opt("activeChildPostCount")
			?.takeUnless { it === JSONObject.NULL }
			?.toString()
			?.toIntOrNull()
			?.coerceAtLeast(0)
			?: post.getIntOrDefault("childPostCount", 0).coerceAtLeast(0)

		val reactionSummary = post.optJSONArray("reactions")?.let(::extractReactionSummary)
			?: ReactionSummary(0, 0, 0)

		val postCuid = createdBy?.getStringOrNull("cuid") ?: createdBy?.getStringOrNull("id")
		val postUserId = createdBy?.getStringOrNull("userId") ?: createdBy?.getStringOrNull("idNo")
		val isOwn = forceOwn ||
			post.getBooleanOrDefault("isOwner", false) ||
			(currentUser?.cuid != null && postCuid == currentUser.cuid) ||
			(currentUser?.userId != null && postUserId == currentUser.userId)
		val isLoggedIn = forceOwn || currentUser != null
		val resolvedPostPageId = post.getStringOrNull("pageId") ?: pageId

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = authorAvatarUrl,
			authorRole = authorRole,
			body = body,
			createdAtEpochMs = createdAtEpochMs,
			isSpoiler = isSpoiler,
			isPinned = isPinned,
			likesCount = reactionSummary.likesCount,
			dislikesCount = reactionSummary.dislikesCount,
			userVote = reactionSummary.userVote,
			repliesCount = repliesCount,
			parentId = parentId,
			isOwnComment = isOwn,
			canReply = isLoggedIn && isService && parentId == null,
			canLike = isLoggedIn && isService,
			canDislike = isLoggedIn && isService,
			canEdit = false,
			canDelete = isOwn && isService,
			rawMeta = mapOf("pageId" to resolvedPostPageId),
		)
	}

	private data class ReactionSummary(
		val likesCount: Int,
		val dislikesCount: Int,
		val userVote: Int,
	)

	private fun extractReactionSummary(reactions: JSONArray): ReactionSummary? {
		for (i in 0 until reactions.length()) {
			val group = reactions.optJSONObject(i) ?: continue
			val reactionId = group.getStringOrNull("reactionId")
			if (reactionId != null && reactionId != "post_like") continue
			val emotions = group.optJSONArray("emotions") ?: continue
			extractEmotionsSummary(emotions)?.let { return it }
		}
		return null
	}

	private fun extractEmotionsSummary(emotions: JSONArray?): ReactionSummary? {
		if (emotions == null) return null
		var likesCount = 0
		var dislikesCount = 0
		var liked = false
		var disliked = false
		var foundAny = false
		for (i in 0 until emotions.length()) {
			val em = emotions.optJSONObject(i) ?: continue
			val emotionId = em.getStringOrNull("emotionId")
				?: em.getStringOrNull("id")
				?: em.getStringOrNull("emotion")
				?: em.getStringOrNull("type")
				?: continue
			val count = em.getIntOrDefault("count", 0).coerceAtLeast(0)
			val reacted = em.getBooleanOrDefault("reacted", false) ||
				em.getBooleanOrDefault("isReacted", false)
			when (emotionId.lowercase(Locale.ROOT)) {
				"like" -> {
					foundAny = true
					likesCount = count
					if (reacted) liked = true
				}
				"dislike" -> {
					foundAny = true
					dislikesCount = count
					if (reacted) disliked = true
				}
			}
		}
		if (!foundAny) return null
		val userVote = when {
			liked -> 1
			disliked -> -1
			else -> 0
		}
		return ReactionSummary(likesCount, dislikesCount, userVote)
	}

	private fun extractAvatarUrl(userObj: JSONObject): String? {
		userObj.getStringOrNull("profileImageUrl")?.let { return it }
		val profileImage = userObj.optJSONObject("profileImage") ?: return null
		val domain = profileImage.getStringOrNull("domain")?.trimEnd('/')
		val path = profileImage.getStringOrNull("path")
		return when {
			domain != null && path != null -> {
				val base = if (domain.startsWith("http", ignoreCase = true)) domain else "https://$domain"
				if (path.startsWith("/")) "$base$path" else "$base/$path"
			}
			path != null && path.startsWith("http", ignoreCase = true) -> path
			else -> null
		}
	}

	private fun parseWccEpochMs(raw: Any?): Long? {
		if (raw == null || raw === JSONObject.NULL) return null
		return when (raw) {
			is Number -> raw.toLong().let { if (it in 1L..9_999_999_999L) it * 1000L else it }
			is String -> raw.trim().nullIfEmpty()?.let { s ->
				s.toLongOrNull()?.let { if (it in 1L..9_999_999_999L) it * 1000L else it }
					?: parseIsoTimestamp(s)
			}
			else -> null
		}
	}

	private data class EpisodeResolution(
		val episodeNo: Int,
		val fromApi: Boolean,
	)

	private suspend fun resolveEpisodeNo(titleId: Long, chapter: MangaChapter?): Int {
		extractEpisodeNo(chapter)?.let { return it }
		defaultEpisodeCache[titleId]?.let { return it }

		val apiDomain = apiDomainProvider()
		val listJson = runCatchingCancellable {
			fetchApiJson("https://$apiDomain/api/article/list?titleId=$titleId&page=1&sort=DESC")
		}.getOrNull()
		val fromList = pickEpisodeNoFromList(listJson)
		if (fromList != null) {
			cacheDefaultEpisode(titleId, fromList)
			return fromList
		}

		val infoJson = runCatchingCancellable {
			fetchApiJson("https://$apiDomain/api/article/list/info?titleId=$titleId")
		}.getOrNull()
		val fromInfo = infoJson?.optJSONObject("firstArticle")?.optInt("no", 0)?.takeIf { it > 0 }
		if (fromInfo != null) {
			cacheDefaultEpisode(titleId, fromInfo)
			return fromInfo
		}

		return 1
	}

	private fun pickDefaultEpisodeNo(listJson: JSONObject?, infoJson: JSONObject?): EpisodeResolution {
		pickEpisodeNoFromList(listJson)?.let { return EpisodeResolution(it, fromApi = true) }
		val fromInfo = infoJson?.optJSONObject("firstArticle")?.optInt("no", 0)?.takeIf { it > 0 }
		if (fromInfo != null) {
			return EpisodeResolution(fromInfo, fromApi = true)
		}
		return EpisodeResolution(1, fromApi = false)
	}

	private fun pickEpisodeNoFromList(listJson: JSONObject?): Int? {
		val articleList = listJson?.optJSONArray("articleList") ?: return null
		var firstValidNo: Int? = null
		for (i in 0 until articleList.length()) {
			val item = articleList.optJSONObject(i) ?: continue
			val no = item.optInt("no", 0).takeIf { it > 0 } ?: continue
			if (firstValidNo == null) {
				firstValidNo = no
			}
			if (!item.optBoolean("charge", false)) {
				return no
			}
		}
		return firstValidNo
	}

	private fun cacheDefaultEpisode(titleId: Long, episodeNo: Int) {
		if (episodeNo <= 0) return
		if (defaultEpisodeCache.size >= MAX_EPISODE_CACHE_SIZE && !defaultEpisodeCache.containsKey(titleId)) {
			defaultEpisodeCache.clear()
		}
		defaultEpisodeCache[titleId] = episodeNo
	}

	private fun computeRawRating(
		manga: Manga,
		listJson: JSONObject?,
		explicitEpisodeNo: Int?,
	): Float? {
		val articleList = listJson?.optJSONArray("articleList")
		if (articleList != null && articleList.length() > 0) {
			var sum = 0.0
			var count = 0
			for (i in 0 until articleList.length()) {
				val item = articleList.optJSONObject(i) ?: continue
				val score = item.getDoubleOrDefault("starScore", 0.0)
				if (score > 0.0) {
					if (explicitEpisodeNo != null && item.optInt("no", -1) == explicitEpisodeNo) {
						return score.toFloat().coerceIn(0f, 10f)
					}
					sum += score
					count++
				}
			}
			if (count > 0) {
				return (sum / count).toFloat().coerceIn(0f, 10f)
			}
		}
		return manga.rating.takeIf { it >= 0f }?.let { (it * 10f).coerceIn(0f, 10f) }
	}

	private data class CommentPageTarget(
		val titleId: Long,
		val episodeNo: Int,
		val pageId: String,
	)

	private suspend fun resolveCommentPageTarget(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	): CommentPageTarget {
		val cachedPageId = comment.rawMeta["pageId"]?.trim()?.nullIfEmpty()
			?: REGEX_PAGE_ID_FROM_COMMENT.find(comment.id)?.groupValues?.getOrNull(1)
		if (cachedPageId != null) {
			val match = REGEX_PAGE_ID_PARTS.matchEntire(cachedPageId)
			val tid = match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: extractTitleIdOrNull(manga) ?: 0L
			val ep = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: extractEpisodeNo(chapter) ?: 1
			return CommentPageTarget(tid, ep, cachedPageId)
		}
		val titleId = extractTitleId(manga)
		val episodeNo = resolveEpisodeNo(titleId, chapter)
		return CommentPageTarget(titleId, episodeNo, buildPageId(titleId, episodeNo))
	}

	private fun extractTitleId(manga: Manga): Long {
		return extractTitleIdOrNull(manga)
			?: throw ParseException("Missing Naver titleId", manga.url)
	}

	private fun extractTitleIdOrNull(manga: Manga): Long? {
		return extractLongParam(manga.url, "titleId", REGEX_TITLE_ID)
			?: extractLongParam(manga.publicUrl, "titleId", REGEX_TITLE_ID)
	}

	private fun extractEpisodeNo(chapter: MangaChapter?): Int? {
		if (chapter == null) return null
		return extractLongParam(chapter.url, "no", REGEX_EPISODE_NO)
			?.takeIf { it <= Int.MAX_VALUE }
			?.toInt()
			?: chapter.number.toInt().takeIf { it > 0 }
	}

	private fun extractLongParam(rawUrl: String?, paramName: String, regex: Regex): Long? {
		val value = rawUrl?.trim()?.nullIfEmpty() ?: return null
		val parsed = value.toAbsoluteUrl(mobileDomainProvider())
			.toHttpUrlOrNull()
			?.queryParameter(paramName)
			?.toLongOrNull()
		if (parsed != null && parsed > 0L) {
			return parsed
		}
		return regex.find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
	}

	private fun buildPageId(titleId: Long, episodeNo: Int): String = "${pagePrefix}_${titleId}_$episodeNo"

	private fun buildWccReferer(mobileDomain: String, titleId: Long, episodeNo: Int): String {
		return "https://$mobileDomain/scrolltoon/comment?titleId=$titleId&no=$episodeNo"
	}

	private fun hasNaverAuthCookies(): Boolean {
		return !getCookieValue("NID_AUT").isNullOrBlank() &&
			!getCookieValue("NID_SES").isNullOrBlank()
	}

	private fun getCookieValue(name: String): String? {
		val hosts = linkedSetOf(
			mobileDomainProvider(),
			"nid.naver.com",
			apiDomainProvider(),
		)
		for (host in hosts) {
			val value = context.cookieJar.getCookies(host)
				.firstOrNull { it.name == name && it.value.isNotBlank() }
				?.value
			if (value != null) {
				return value
			}
		}
		return null
	}

	private fun isAuthFailure(errorCode: String?, errorMessage: String?): Boolean {
		val combined = listOfNotNull(errorCode, errorMessage)
			.joinToString(" ")
			.lowercase(Locale.ROOT)
		if (combined.isBlank()) return false
		return "unauth" in combined ||
			"login" in combined ||
			"session" in combined ||
			"forbidden" in combined ||
			"401" in combined ||
			"403" in combined ||
			"로그인" in combined
	}

	private companion object {
		const val DEFAULT_PAGE_SIZE = 20
		const val MAX_EPISODE_CACHE_SIZE = 256
		const val USER_CACHE_TTL_MS = 180_000L
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val NAVER_AUTH_COOKIE_NAMES = arrayOf("NID_AUT", "NID_SES", "NID_JKL")
		val REGEX_TITLE_ID = Regex("""[?&]titleId=(\d+)""")
		val REGEX_EPISODE_NO = Regex("""[?&]no=(\d+)""")
		val REGEX_PAGE_ID_FROM_COMMENT = Regex("""(?:^|-)((?:webtoon|challenge)_\d+_\d+)(?:-|$)""")
		val REGEX_PAGE_ID_PARTS = Regex("""^(?:webtoon|challenge)_(\d+)_(\d+)$""")
	}
}
