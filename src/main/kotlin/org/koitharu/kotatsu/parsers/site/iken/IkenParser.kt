package org.koitharu.kotatsu.parsers.site.iken

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.EMPTY
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
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
import org.koitharu.kotatsu.parsers.site.community.DisqusDetector
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class IkenParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 18,
	protected val useAPI: Boolean = false
) : PagedMangaParser(context, source, pageSize),
	MangaSourceCommunityProvider,
	MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected val defaultDomain: String
		get() = if (useAPI) "api.$domain" else domain

	protected open val apiHeaders: Headers by lazy {
		getRequestHeaders().newBuilder()
			.set("Accept", "application/json, text/plain, */*")
			.set("Origin", "https://$domain")
			.set("Referer", "https://$domain/")
			.build()
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHUA,
			ContentType.MANHWA,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(defaultDomain)
			append("/api/query?page=")
			append(page)
			append("&perPage=18&searchTerm=")

			filter.query?.let { query ->
				val normalized = query.normalizeSearchQuery().ifEmpty { query.trim() }
				append(normalized.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			append("&seriesType=")
			filter.types.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						ContentType.OTHER -> "RUSSIAN"
						else -> ""
					},
				)
			}

			append("&seriesStatus=")
			filter.states.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.UPCOMING -> "COMING_SOON"
						MangaState.ABANDONED -> "DROPPED"
						else -> ""
					},
				)
			}
		}
		return parseMangaList(webClient.httpGet(url).parseJson())
	}

	protected open fun parseMangaList(json: JSONObject): List<Manga> {
		return json.getJSONArray("posts").mapJSON {
			val url = "/series/${it.getString("slug")}"
			val isNsfwSource = it.getBooleanOrDefault("hot", false)
			val author = it.getStringOrNull("author")?.nullIfEmpty()
			val description = it.getStringOrNull("postContent")
				?: it.getStringOrNull("description")
				?: ""
			Manga(
				id = it.getLong("id"),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = it.getString("featuredImage"),
				title = it.getString("postTitle"),
				altTitles = setOfNotNull(it.getStringOrNull("alternativeTitles")),
				description = description,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOfNotNull(author),
				state = when (it.getString("seriesStatus")) {
					"ONGOING" -> MangaState.ONGOING
					"COMPLETED" -> MangaState.FINISHED
					"DROPPED", "CANCELLED" -> MangaState.ABANDONED
					"COMING_SOON" -> MangaState.UPCOMING
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}


	protected open val datePattern = "yyyy-MM-dd"

	override suspend fun getDetails(manga: Manga): Manga {
		val seriesId = manga.id
		val url = "https://$defaultDomain/api/chapters?postId=$seriesId&skip=0&take=900&order=desc&userid="
		val json = webClient.httpGet(url).parseJson().getJSONObject("post")
		val slug = json.getStringOrNull("slug")
		val data = json.getJSONArray("chapters").asTypedList<JSONObject>()
		val dateFormat = SimpleDateFormat(datePattern, Locale.ENGLISH)
		return manga.copy(
			chapters = data.mapChapters(reversed = true) { i, it ->
				val slugName = if (slug.isNullOrEmpty()) {
					it.getJSONObject("mangaPost").getString("slug")
				} else {
					slug
				}
				val chapterUrl = "/series/$slugName/${it.getString("slug")}"
				MangaChapter(
					id = it.getLong("id"),
					title = null,
					number = it.getFloatOrDefault("number", 0f),
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(it.getString("createdAt").substringBefore("T")),
					branch = null,
					source = source,
				)
			},
		)
	}

	protected open val selectPages = "main section img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		if (useAPI) {
			val apiPages = runCatching { readChapterImages(chapter.id) }.getOrElse { error ->
				if (error.message?.contains("unlock", ignoreCase = true) == true) {
					throw error
				}
				emptyList()
			}
			if (apiPages.isNotEmpty()) {
				return apiPages
			}
		}

		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		if (doc.selectFirst("svg.lucide-lock") != null) {
            throw Exception("Need to unlock chapter!")
        }

		val imagesJson = doc.getNextJson("images")
		val images = parseImagesJson(imagesJson)

		return images.map { p ->
			MangaPage(
				id = generateUid(p),
				url = p,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun readChapterImages(chapterId: Long): List<MangaPage> {
		if (chapterId <= 0L) return emptyList()
		val json = webClient.httpGet(
			"https://$defaultDomain/api/chapter?chapterId=$chapterId",
			apiHeaders,
		).parseJson()
		val chapterJson = json.optJSONObject("chapter") ?: return emptyList()
		if (chapterJson.optBoolean("isLocked", false) || chapterJson.opt("isAccessible") == false) {
			throw Exception("Need to unlock chapter!")
		}
		val images = chapterJson.optJSONArray("images") ?: return emptyList()
		val pages = (0 until images.length()).mapNotNull { index ->
			val item = images.optJSONObject(index) ?: return@mapNotNull null
			val url = item.getStringOrNull("url")
				?: item.getStringOrNull("src")
				?: item.getStringOrNull("image")
				?: return@mapNotNull null
			PageImage(
				order = item.opt("order")?.toString()?.toIntOrNull() ?: Int.MAX_VALUE,
				url = url.replace("/public//", "/public/"),
			)
		}.sortedBy { it.order }

		return pages.map { image ->
			MangaPage(
				id = generateUid(image.url),
				url = image.url,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series").parseHtml()
		return doc.selectLastOrThrow("select").select("option[value]").mapNotNullToSet {
			val key = it.attrOrNull("value") ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
                title = it.text().ifBlank { key }.toTitleCase(sourceLocale),
                source = source,
			)
		}
	}

	protected fun Document.getNextJson(key: String): String {
		val scripts = select("script")
		val scriptData = scripts.find { script ->
            script.data().contains(key)
		}?.data() ?: throw Exception("Unable to retrieve NEXT data")

		val keyIndex = scriptData.indexOf(key)
		if (keyIndex == -1) throw Exception("Key $key not found in script data")

		val start = scriptData.indexOf('[', keyIndex)
		if (start == -1) {
			val objStart = scriptData.indexOf('{', keyIndex)
			if (objStart == -1) throw Exception("No JSON data found after key")

			var depth = 1
			var i = objStart + 1
			while (i < scriptData.length && depth > 0) {
				when (scriptData[i]) {
					'{' -> depth++
					'}' -> depth--
				}
				i++
			}
			return scriptData.substring(objStart, i)
		}

		var depth = 1
		var i = start + 1
		while (i < scriptData.length && depth > 0) {
			when (scriptData[i]) {
				'[' -> depth++
				']' -> depth--
			}
			i++
		}

		val jsonStr = scriptData.substring(start, i)
		return jsonStr.replace("\\/", "/").replace("\\\"", "\"")
	}

	private fun parseImagesJson(json: String): List<String> {
		val jsonArray = JSONArray(json)
		return List(jsonArray.length()) { index ->
			val item = jsonArray.getJSONObject(index)
			item.getString("url")
		}
	}

	override val authUrl: String
		get() = "https://$domain/auth/signin"

	override val signUpUrl: String
		get() = "https://$domain/auth/signup"

	override suspend fun isAuthorized(): Boolean = hasSessionCookie()

	override suspend fun getUsername(): String {
		val user = fetchCurrentUser() ?: throw AuthRequiredException(source)
		return user.getStringOrNull("username")
			?: user.getStringOrNull("name")
			?: user.getStringOrNull("email")
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatchingCancellable {
			executeJsonRequest("POST", "https://$domain/api/auth/signout", JSONObject())
		}
		context.clearAuthValues(source)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = false,
		supportsCommentEditing = true,
		supportsCommentDeleting = false,
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
		val slug = extractPostSlug(manga)
		val post = runCatchingCancellable { fetchPostJson(slug) }.getOrNull()
		val postId = post?.optLong("id")?.takeIf { it > 0L } ?: manga.id

		val viewerDeferred = async {
			runCatchingCancellable {
				executeJsonRequest("GET", "https://$defaultDomain/api/post/viewer?postId=$postId")
			}.getOrNull()
		}
		val seriesCommentsDeferred = async {
			runCatchingCancellable {
				fetchCommentsPageJson("POST", postId, page = 1, pageSize = 1, sort = SourceCommentSort.NEWEST)
			}.getOrNull()
		}
		val chapterCommentsDeferred = if (chapter != null && chapter.id > 0L) {
			async {
				runCatchingCancellable {
					fetchCommentsPageJson("CHAPTER", chapter.id, page = 1, pageSize = 1, sort = SourceCommentSort.NEWEST)
				}.getOrNull()
			}
		} else {
			null
		}

		val viewer = viewerDeferred.await()
		val seriesComments = seriesCommentsDeferred.await()
		val chapterComments = chapterCommentsDeferred?.await()

		val rawRating = post?.optDouble("averageRating", Double.NaN)
			?.takeIf { !it.isNaN() && it > 0.0 }
			?.toFloat()
		val counts = post?.optJSONObject("_count")
		val ratingCount = post?.optInt("totalRatings", -1)?.takeIf { it >= 0 }
			?: counts?.optInt("ratings", -1)?.takeIf { it >= 0 }
		val viewCount = post?.optLong("totalViews", -1L)?.takeIf { it >= 0L }
		val followCount = counts?.optLong("bookmarks", -1L)?.takeIf { it >= 0L }
		val commentsEnabled = post?.getBooleanOrDefault("commentsEnabled", true) ?: true

		val viewerUser = viewer?.optJSONObject("user")
		val currentUsername = viewerUser?.let {
			it.getStringOrNull("username") ?: it.getStringOrNull("name")
		}
		val userRating = parseIkenUserRating(viewer?.opt("userRating"))
		val isAuthed = viewerUser != null || hasSessionCookie()

		SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = extractCommentsTotal(seriesComments),
			chapterCommentCount = extractCommentsTotal(chapterComments),
			canCurrentUserRate = isAuthed,
			canCurrentUserComment = isAuthed && commentsEnabled,
			currentUsername = currentUsername,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val postId = resolvePostId(manga)
		val clamped = score.coerceIn(1, 10)
		val payload = JSONObject().apply {
			put("postID", postId)
			put("value", clamped)
			put("content", "")
		}
		executeJsonRequest("POST", "https://$defaultDomain/api/posts/ratings", payload)
		return getMangaCommunityInfo(manga, chapter).copy(userRating = clamped)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage = coroutineScope {
		val (targetType, targetId) = resolveCommentTarget(manga, chapter)
		val userDeferred = if (hasSessionCookie()) {
			async { runCatchingCancellable { fetchCurrentUser() }.getOrNull() }
		} else {
			null
		}
		val jsonResult = runCatchingCancellable {
			fetchCommentsPageJson(targetType, targetId, page = page, pageSize = COMMENTS_PAGE_SIZE, sort = sort)
		}
		val currentUserId = userDeferred?.await()?.let { it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString() }
		val isAuthed = currentUserId != null || hasSessionCookie()

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

		val items = json.optJSONArray("items")?.asTypedList<JSONObject>().orEmpty()
		val comments = items.map { parseIkenComment(it, currentUserId, isAuthed) }
		val total = json.optInt("total", -1).takeIf { it >= 0 }
		val totalWithReplies = extractCommentsTotal(json) ?: total
		val respPageSize = json.optInt("pageSize", COMMENTS_PAGE_SIZE).coerceAtLeast(1)
		val hasNextPage = if (total != null) {
			page * respPageSize < total
		} else {
			comments.size >= respPageSize
		}

		if (comments.isEmpty() && page == 1) {
			val fallbackWidget = runCatchingCancellable { resolveWidgetContext(manga, chapter) }.getOrNull()
			if (fallbackWidget != null) {
				return@coroutineScope SourceCommentPage(
					comments = emptyList(),
					page = page,
					hasNextPage = false,
					totalCount = totalWithReplies,
					widgetContext = fallbackWidget,
					engine = fallbackWidget.engine,
				)
			}
		}

		SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasNextPage,
			totalCount = totalWithReplies,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage {
		val url = buildString {
			append("https://")
			append(defaultDomain)
			append("/api/comments/")
			append(comment.id)
			append("/replies?limit=")
			append(COMMENTS_PAGE_SIZE)
			if (!cursor.isNullOrBlank()) {
				append("&cursor=")
				append(cursor.urlEncoded())
			}
		}
		val json = executeJsonRequest("GET", url)
		val currentUserId = if (hasSessionCookie()) {
			runCatchingCancellable { fetchCurrentUser() }.getOrNull()
				?.let { it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString() }
		} else {
			null
		}
		val isAuthed = currentUserId != null || hasSessionCookie()
		val items = json.optJSONArray("items")?.asTypedList<JSONObject>().orEmpty()
		val replies = items.map { parseIkenComment(it, currentUserId, isAuthed, fallbackParentId = comment.id) }
		val nextCursor = json.getStringOrNull("nextCursor") ?: json.getStringOrNull("repliesNextCursor")
		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = !nextCursor.isNullOrBlank(),
			engine = SourceCommentEngine.NATIVE,
			nextCursor = nextCursor,
		)
	}

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
		val (targetType, targetId) = resolveCommentTarget(manga, chapter)
		val payload = JSONObject().apply {
			put("targetType", targetType)
			put("targetId", targetId)
			put("content", text)
			put("uploadIds", JSONArray())
			if (parentComment != null) {
				val parentNumId = parentComment.id.toLongOrNull()
				val rootNumId = parentComment.parentId?.toLongOrNull() ?: parentNumId
				if (rootNumId != null) put("parentId", rootNumId)
				if (parentNumId != null) put("replyToCommentId", parentNumId)
			}
		}
		val json = executeJsonRequest("POST", "https://$defaultDomain/api/comments/create", payload)
		val item = json.optJSONObject("item") ?: json.optJSONObject("comment") ?: json
		return if (item.has("id")) {
			parseIkenComment(item, currentUserId = null, isAuthed = true, fallbackParentId = parentComment?.id)
				.copy(isOwnComment = true, canEdit = true)
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
				canEdit = true,
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
		val json = executeJsonRequest("PATCH", "https://$defaultDomain/api/comments/${comment.id}", payload)
		val item = json.optJSONObject("item") ?: json.optJSONObject("comment") ?: json
		return if (item.has("id")) {
			parseIkenComment(item, currentUserId = null, isAuthed = true, fallbackParentId = comment.parentId)
				.copy(isOwnComment = true, canEdit = true, isEdited = true)
		} else {
			comment.copy(
				body = newText,
				bodyHtml = null,
				isEdited = true,
			)
		}
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val commentIdNum = comment.id.toLongOrNull()
		val payload = JSONObject().apply {
			put("targetType", "COMMENT")
			put("targetId", commentIdNum ?: comment.id)
			put("kind", "LIKE")
		}
		val json = executeJsonRequest("POST", "https://$defaultDomain/api/comments/reactions/toggle", payload)
		val reactions = json.optJSONObject("reactions")
			?: json.optJSONObject("item")?.optJSONObject("reactions")
		if (reactions != null) {
			val counts = reactions.optJSONObject("counts")
			val likes = counts?.optInt("LIKE", comment.likesCount) ?: comment.likesCount
			val liked = reactions.optJSONObject("mine")?.getBooleanOrDefault("LIKE", false)
				?: (reactions.getStringOrNull("mine")?.equals("LIKE", ignoreCase = true) == true)
			return comment.copy(
				likesCount = likes,
				userVote = if (liked) 1 else 0,
			)
		}
		val nextLiked = if (json.has("active")) {
			json.optBoolean("active")
		} else {
			comment.userVote != 1
		}
		val nextCount = json.optInt("count", -1).takeIf { it >= 0 }
			?: if (nextLiked) comment.likesCount + 1 else maxOf(0, comment.likesCount - 1)
		return comment.copy(
			likesCount = nextCount,
			userVote = if (nextLiked) 1 else 0,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
		val doc = runCatchingCancellable { webClient.httpGet(pageUrl).parseHtml() }.getOrNull() ?: return null
		WidgetDetectors.detect(doc, pageUrl)?.let { return it }
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		if (!IKEN_DISQUS_ENABLED_REGEX.containsMatchIn(scriptsText)) {
			return null
		}
		val shortname = IKEN_DISQUS_SHORTNAME_REGEX.find(scriptsText)
			?.groupValues?.getOrNull(1)
			?.trim()
			?.nullIfEmpty()
			?: return null
		val pageTitle = doc.title().nullIfEmpty() ?: manga.title
		val identifier = extractPostSlug(manga)
		return SourceWidgetContext(
			engine = SourceCommentEngine.DISQUS,
			pageUrl = pageUrl,
			widgetUrl = "https://$shortname.disqus.com/embed.js",
			identifier = identifier,
			shortname = shortname,
			title = pageTitle,
			inlineHtml = DisqusDetector.buildInlineHtml(
				shortname = shortname,
				pageUrl = pageUrl,
				identifier = identifier,
				title = pageTitle,
			),
			baseUrl = pageUrl,
			allowedHostSuffixes = DisqusDetector.ALLOWED_HOST_SUFFIXES,
		)
	}

	private fun hasSessionCookie(): Boolean {
		val cookies = context.cookieJar.getCookies(domain) + context.cookieJar.getCookies(defaultDomain)
		return cookies.any { cookie ->
			val normalized = cookie.name
				.removePrefix("__Secure-")
				.removePrefix("__Host-")
			normalized in SESSION_COOKIE_NAMES && cookie.value.isNotBlank()
		}
	}

	private suspend fun fetchCurrentUser(): JSONObject? {
		val primary = runCatchingCancellable {
			executeJsonRequest("GET", "https://$domain/api/me")
		}.getOrElse { err ->
			if (err is HttpStatusException && err.statusCode == 404 && useAPI) {
				executeJsonRequest("GET", "https://$defaultDomain/api/me")
			} else {
				throw err
			}
		}
		return primary.optJSONObject("user") ?: primary.takeIf {
			it.getStringOrNull("id") != null || it.getStringOrNull("username") != null || it.getStringOrNull("name") != null
		}
	}

	private fun extractPostSlug(manga: Manga): String {
		return manga.url.trim()
			.substringBefore('?')
			.substringBefore('#')
			.trimEnd('/')
			.substringAfterLast('/')
	}

	private suspend fun fetchPostJson(slug: String): JSONObject? {
		if (slug.isBlank()) return null
		val json = executeJsonRequest("GET", "https://$defaultDomain/api/post?postSlug=${slug.urlEncoded()}")
		return json.optJSONObject("post") ?: json.takeIf { it.has("id") }
	}

	private suspend fun resolvePostId(manga: Manga): Long {
		val slug = extractPostSlug(manga)
		return runCatchingCancellable { fetchPostJson(slug)?.optLong("id") }
			.getOrNull()
			?.takeIf { it > 0L }
			?: manga.id
	}

	private suspend fun resolveCommentTarget(manga: Manga, chapter: MangaChapter?): Pair<String, Long> {
		return if (chapter != null && chapter.id > 0L) {
			"CHAPTER" to chapter.id
		} else {
			"POST" to resolvePostId(manga)
		}
	}

	private suspend fun fetchCommentsPageJson(
		targetType: String,
		targetId: Long,
		page: Int,
		pageSize: Int,
		sort: SourceCommentSort,
	): JSONObject {
		val sortParam = when (sort) {
			SourceCommentSort.TOP -> "top"
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.NEWEST -> "recent"
		}
		val url = "https://$defaultDomain/api/comments/list" +
			"?targetType=$targetType&targetId=$targetId&page=$page&pageSize=$pageSize&sort=$sortParam"
		return executeJsonRequest("GET", url)
	}

	private fun extractCommentsTotal(json: JSONObject?): Int? {
		if (json == null) return null
		return json.optInt("totalWithReplies", -1).takeIf { it >= 0 }
			?: json.optInt("total", -1).takeIf { it >= 0 }
	}

	private fun parseIkenUserRating(raw: Any?): Int? = when (raw) {
		is Number -> raw.toInt().takeIf { it in 1..10 }
		is JSONObject -> raw.optInt("value", -1).takeIf { it in 1..10 }
			?: raw.optInt("rating", -1).takeIf { it in 1..10 }
			?: raw.optInt("score", -1).takeIf { it in 1..10 }
		else -> null
	}

	private fun parseIkenComment(
		obj: JSONObject,
		currentUserId: String?,
		isAuthed: Boolean,
		fallbackParentId: String? = null,
	): SourceComment {
		val id = obj.getStringOrNull("id") ?: obj.optLong("id", 0L).toString()
		val userObj = obj.optJSONObject("user")
		val authorId = obj.getStringOrNull("userId")
			?: userObj?.let { it.getStringOrNull("id") ?: it.optLong("id", -1L).takeIf { n -> n > 0L }?.toString() }
		val authorName = userObj?.let {
			it.getStringOrNull("username") ?: it.getStringOrNull("name")
		} ?: obj.getStringOrNull("authorName") ?: "Anonymous"
		val avatarUrl = userObj?.getStringOrNull("image")?.toAbsoluteUrl(domain)
		val authorRole = userObj?.getStringOrNull("role")?.takeUnless { it.equals("USER", ignoreCase = true) }

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

		val reactionsObj = obj.optJSONObject("reactions")
		val likesCount = reactionsObj?.optJSONObject("counts")?.optInt("LIKE", 0)
			?: obj.optInt("likesCount", 0)
		val isLiked = reactionsObj?.optJSONObject("mine")?.getBooleanOrDefault("LIKE", false) == true ||
			reactionsObj?.getStringOrNull("mine")?.equals("LIKE", ignoreCase = true) == true

		val nestedReplies = obj.optJSONArray("replies")?.asTypedList<JSONObject>().orEmpty().map { reply ->
			parseIkenComment(reply, currentUserId, isAuthed, fallbackParentId = id)
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
			isSpoiler = obj.getBooleanOrDefault("isSpoiler", false),
			isPinned = obj.getBooleanOrDefault("isPinned", false),
			likesCount = likesCount,
			userVote = if (isLiked) 1 else 0,
			repliesCount = replyCount,
			parentId = parentId,
			replies = nestedReplies,
			isOwnComment = isOwn,
			canReply = isAuthed,
			canLike = isAuthed,
			canEdit = isOwn,
			canDelete = false,
		)
	}

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
			.headers(apiHeaders)
			.tag(MangaSource::class.java, source)
			.build()
		return context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val rawBody = response.body.string()
			val json = rawBody.toJSONObjectOrNull()
			if (!response.isSuccessful) {
				val message = json?.getStringOrNull("error")
					?: json?.getStringOrNull("message")
					?: response.message
				throw HttpStatusException(message, response.code, url)
			}
			json ?: JSONObject()
		}
	}

	private data class PageImage(
		val order: Int,
		val url: String,
	)

	private companion object {

		private const val COMMENTS_PAGE_SIZE = 20
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		private val LINE_BREAK_TAG_REGEX = Regex("""<(?:br\s*/?|/p|/div)>""", RegexOption.IGNORE_CASE)
		private val IKEN_DISQUS_ENABLED_REGEX = Regex("""\\?"enableDisqusComments\\?"\s*:\s*true""")
		private val IKEN_DISQUS_SHORTNAME_REGEX = Regex("""\\?"disqusShortname\\?"\s*:\s*\\?"([a-zA-Z0-9-]+)\\?"""")
		private val SESSION_COOKIE_NAMES = setOf(
			"vthemeauth.session_token",
			"vthemeauth_session_token",
			"better-auth.session_token",
			"better-auth_session_token",
		)
	}
}
