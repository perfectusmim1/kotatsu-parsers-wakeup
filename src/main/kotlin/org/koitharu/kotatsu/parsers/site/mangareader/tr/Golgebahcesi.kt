package org.koitharu.kotatsu.parsers.site.mangareader.tr

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("GOLGEBAHCESI", "GolgeBahcesi", "tr")
internal class Golgebahcesi(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.GOLGEBAHCESI, pageSize = 20),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("golgebahcesi.com")

	override val authUrl: String
		get() = "https://$domain/login"

	override val signUpUrl: String
		get() = "https://$domain/register"

	override suspend fun isAuthorized(): Boolean {
		return fetchCurrentUser() != null
	}

	override suspend fun getUsername(): String {
		val user = fetchCurrentUser() ?: throw AuthRequiredException(source)
		return user.getStringOrNull("displayName")?.nullIfEmpty()
			?: user.getStringOrNull("username")?.nullIfEmpty()
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatching {
			val url = apiUrlBuilder().addPathSegment("auth").addPathSegment("logout").build()
			executeApiRequest("POST", url, JSONObject())
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
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
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

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	@Volatile
	private var cachedTags: Set<MangaTag>? = null

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrlBuilder()
			.addPathSegment("series")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					else -> "latest"
				},
			)
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
					addQueryParameter("search", it)
				}
				filter.tags.oneOrThrowIfMany()?.let {
					addQueryParameter("genre", it.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					when (state) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.PAUSED -> "HIATUS"
						MangaState.ABANDONED -> "DROPPED"
						else -> null
					}?.let { addQueryParameter("status", it) }
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					when (type) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						else -> null
					}?.let { addQueryParameter("type", it) }
				}
			}.build()

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return data.mapJSONNotNull { item ->
			val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val title = item.getStringOrNull("title") ?: return@mapJSONNotNull null
			val relUrl = "/manga/$slug"
			val altTitles = item.optJSONArray("alternativeTitles")
				?.toStringSet()
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				?: emptySet()
			val rating = item.getDoubleOrDefault("rating", -1.0).let {
				if (it in 0.0..10.0) (it / 10.0).toFloat() else RATING_UNKNOWN
			}
			val authors = listOfNotNull(
				item.getStringOrNull("author"),
				item.getStringOrNull("artist"),
			).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }

			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = item.getStringOrNull("coverImage"),
				title = title,
				altTitles = altTitles,
				rating = rating,
				tags = emptySet(),
				authors = authors,
				state = parseState(item.getStringOrNull("status")),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val seriesUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(slug).build()
		val chaptersUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(slug).addPathSegment("chapters").build()

		val detail = webClient.httpGet(seriesUrl).parseJson()
		val chaptersJson = webClient.httpGet(chaptersUrl).parseJsonArray()

		val knownTags = runCatching { fetchAvailableTags() }.getOrDefault(emptySet())
		val tagsByTitle = knownTags.associateBy { it.title.lowercase(sourceLocale) }
		val tags = detail.optJSONArray("genres")?.toStringSet()?.mapNotNullToSet { name ->
			val clean = name.trim().nullIfEmpty() ?: return@mapNotNullToSet null
			tagsByTitle[clean.lowercase(sourceLocale)] ?: MangaTag(
				key = slugify(clean),
				title = clean.toTitleCase(sourceLocale),
				source = source,
			)
		} ?: emptySet()

		val altTitles = detail.optJSONArray("alternativeTitles")
			?.toStringSet()
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			?: manga.altTitles
		val authors = listOfNotNull(
			detail.getStringOrNull("author"),
			detail.getStringOrNull("artist"),
		).flatMap { it.split(',', '/') }.mapNotNullToSet { it.trim().nullIfEmpty() }
		val rating = detail.getDoubleOrDefault("rating", -1.0).let {
			if (it in 0.0..10.0) (it / 10.0).toFloat() else manga.rating
		}

		val chapters = ArrayList<MangaChapter>(chaptersJson.length())
		for (i in chaptersJson.length() - 1 downTo 0) {
			val ch = chaptersJson.optJSONObject(i) ?: continue
			val chSlug = ch.getStringOrNull("slug") ?: continue
			val chUrl = "/manga/$slug/bolum/$chSlug"
			val number = ch.getFloatOrDefault("number", chapters.size + 1f)
			val chTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val dateStr = ch.getStringOrNull("releaseDate") ?: ch.getStringOrNull("createdAt")
			chapters.add(
				MangaChapter(
					id = generateUid(chUrl),
					title = chTitle,
					number = number,
					volume = 0,
					url = chUrl,
					scanlator = null,
					uploadDate = parseIsoDate(dateStr),
					branch = null,
					source = source,
				),
			)
		}

		return manga.copy(
			title = detail.getStringOrNull("title") ?: manga.title,
			altTitles = altTitles,
			coverUrl = detail.getStringOrNull("coverImage") ?: manga.coverUrl,
			description = detail.getStringOrNull("description")?.trim()?.nullIfEmpty(),
			tags = tags,
			authors = authors,
			state = parseState(detail.getStringOrNull("status")) ?: manga.state,
			rating = rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.trim('/').split('/')
		val seriesSlug = segments.getOrNull(1)
			?: throw ParseException("Invalid chapter path", chapter.url)
		val chapterSlug = segments.getOrNull(3)
			?: throw ParseException("Invalid chapter path", chapter.url)

		val url = apiUrlBuilder()
			.addPathSegment("chapters")
			.addPathSegment(seriesSlug)
			.addPathSegment(chapterSlug)
			.build()
		val json = webClient.httpGet(url).parseJson()
		val rawPages = json.optJSONArray("pages")
		val pages = rawPages?.mapJSONNotNull { page ->
			val imgUrl = page.getStringOrNull("url")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		} ?: emptyList()

		if (pages.isEmpty()) {
			if (json.getBooleanOrDefault("isLocked", false)) {
				throw AuthRequiredException(source)
			}
			throw ParseException("Encrypted chapter delivery is not supported", chapter.url)
		}
		return pages
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val seriesSlug = extractSeriesSlug(manga)
		val detailUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(seriesSlug).build()
		val detail = webClient.httpGet(detailUrl).parseJson()
		val seriesId = detail.getStringOrNull("id")?.nullIfEmpty()
			?: throw ParseException("Series id missing", detailUrl.toString())

		val targetType = if (chapter != null) "CHAPTER" else "SERIES"
		val targetId = if (chapter != null) {
			resolveChapterId(chapter) ?: seriesId
		} else {
			seriesId
		}

		val commentsMeta = runCatching {
			val commentsUrl = apiUrlBuilder()
				.addPathSegment("comments")
				.addQueryParameter("targetId", targetId)
				.addQueryParameter("targetType", targetType)
				.addQueryParameter("page", "1")
				.build()
			val json = webClient.httpGet(commentsUrl).parseJson()
			val meta = json.optJSONObject("meta")
			val pagination = json.optJSONObject("pagination")
			meta?.opt("total")?.toString()?.toIntOrNull()
				?: pagination?.opt("totalItems")?.toString()?.toIntOrNull()
		}.getOrNull()

		val currentUser = fetchCurrentUser()
		val currentUsername = currentUser?.let {
			it.getStringOrNull("displayName")?.nullIfEmpty() ?: it.getStringOrNull("username")?.nullIfEmpty()
		}
		val userRating = if (currentUser != null) {
			runCatching {
				val myRatingUrl = apiUrlBuilder()
					.addPathSegment("series")
					.addPathSegment(seriesId)
					.addPathSegment("my-rating")
					.build()
				val myJson = webClient.httpGet(myRatingUrl).parseJson()
				(myJson.opt("userRating") ?: myJson.opt("rating"))?.toString()?.toIntOrNull()
			}.getOrNull()
		} else {
			null
		}

		val rawRating = detail.getDoubleOrDefault("rating", -1.0)
			.takeIf { it in 0.0..10.0 }
			?.toFloat()
		val ratingCount = detail.opt("ratingCount")?.toString()?.toIntOrNull()
		val viewCount = detail.opt("viewCount")?.toString()?.toLongOrNull()
		val followCount = detail.opt("followerCount")?.toString()?.toLongOrNull()

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = if (chapter == null) commentsMeta else null,
			chapterCommentCount = if (chapter != null) commentsMeta else null,
			canCurrentUserRate = currentUser != null,
			canCurrentUserComment = currentUser != null,
			currentUsername = currentUsername,
		)
	}

	override suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val seriesSlug = extractSeriesSlug(manga)
		val detailUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(seriesSlug).build()
		val detail = webClient.httpGet(detailUrl).parseJson()
		val seriesId = detail.getStringOrNull("id")?.nullIfEmpty()
			?: throw ParseException("Series id missing", detailUrl.toString())

		val clamped = score.coerceIn(1, 10)
		val rateUrl = apiUrlBuilder()
			.addPathSegment("series")
			.addPathSegment(seriesId)
			.addPathSegment("rate")
			.build()
		val payload = JSONObject().apply { put("rating", clamped) }
		val res = executeApiRequest("POST", rateUrl, payload)
		val userRating = res.opt("userRating")?.toString()?.toIntOrNull() ?: clamped
		val newRating = res.opt("newRating")?.toString()?.toFloatOrNull()
		val ratingCount = res.opt("ratingCount")?.toString()?.toIntOrNull()
		val existingInfo = runCatching { getMangaCommunityInfo(manga, chapter) }.getOrNull()
		return (existingInfo ?: SourceMangaCommunityInfo(rawScale = 10)).copy(
			averageRating = (newRating ?: existingInfo?.rawRating)?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = newRating ?: existingInfo?.rawRating,
			rawScale = 10,
			userRating = userRating,
			ratingCount = ratingCount ?: existingInfo?.ratingCount,
			canCurrentUserRate = true,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val (targetId, targetType) = resolveCommentTarget(manga, chapter)
		val sortParam = when (sort) {
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.TOP -> "popular"
			SourceCommentSort.NEWEST -> "newest"
		}
		val url = apiUrlBuilder()
			.addPathSegment("comments")
			.addQueryParameter("targetId", targetId)
			.addQueryParameter("targetType", targetType)
			.addQueryParameter("page", page.coerceAtLeast(1).toString())
			.addQueryParameter("sort", sortParam)
			.build()
		val json = executeApiRequest("GET", url, null, throwOnAuthError = false)
		val currentUser = fetchCurrentUser()
		val currentUserId = currentUser?.getStringOrNull("id") ?: currentUser?.getStringOrNull("_id")
		val currentUsername = currentUser?.getStringOrNull("username")

		val data = json.optJSONArray("data")
		val comments = ArrayList<SourceComment>(data?.length() ?: 0)
		if (data != null) {
			for (i in 0 until data.length()) {
				val jo = data.optJSONObject(i) ?: continue
				comments.add(parseApiComment(jo, currentUserId, currentUsername))
			}
		}

		val meta = json.optJSONObject("meta")
		val pagination = json.optJSONObject("pagination")
		val currentPage = meta?.opt("page")?.toString()?.toIntOrNull()
			?: pagination?.opt("currentPage")?.toString()?.toIntOrNull()
			?: page
		val total = meta?.opt("total")?.toString()?.toIntOrNull()
			?: pagination?.opt("totalItems")?.toString()?.toIntOrNull()
		val totalPages = pagination?.opt("totalPages")?.toString()?.toIntOrNull()
		val hasMore = meta?.getBooleanOrDefault("hasMore", false)
			?: totalPages?.let { currentPage < it }
			?: false

		return SourceCommentPage(
			comments = comments,
			page = currentPage,
			hasNextPage = hasMore,
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
	): SourceCommentPage {
		val (targetId, targetType) = resolveCommentTarget(manga, chapter)
		val url = apiUrlBuilder()
			.addPathSegment("comments")
			.addQueryParameter("targetId", targetId)
			.addQueryParameter("targetType", targetType)
			.addQueryParameter("parentId", comment.id)
			.addQueryParameter("page", page.coerceAtLeast(1).toString())
			.build()
		val json = runCatching { executeApiRequest("GET", url, null, throwOnAuthError = false) }.getOrNull()
		val currentUser = fetchCurrentUser()
		val currentUserId = currentUser?.getStringOrNull("id") ?: currentUser?.getStringOrNull("_id")
		val currentUsername = currentUser?.getStringOrNull("username")

		val data = json?.optJSONArray("data")
		val parsed = ArrayList<SourceComment>(data?.length() ?: 0)
		if (data != null) {
			for (i in 0 until data.length()) {
				val jo = data.optJSONObject(i) ?: continue
				parsed.add(parseApiComment(jo, currentUserId, currentUsername))
			}
		}
		if (parsed.isEmpty() && comment.replies.isNotEmpty()) {
			return SourceCommentPage(
				comments = comment.replies,
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}
		val meta = json?.optJSONObject("meta")
		val pagination = json?.optJSONObject("pagination")
		val totalPages = pagination?.opt("totalPages")?.toString()?.toIntOrNull()
		val hasMore = meta?.getBooleanOrDefault("hasMore", false)
			?: totalPages?.let { page < it }
			?: false
		return SourceCommentPage(
			comments = parsed,
			page = page,
			hasNextPage = hasMore,
			totalCount = meta?.opt("total")?.toString()?.toIntOrNull(),
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
		val (targetId, targetType) = resolveCommentTarget(manga, chapter)
		val url = apiUrlBuilder().addPathSegment("comments").build()
		val payload = JSONObject().apply {
			put("targetId", targetId)
			put("targetType", targetType)
			put("content", text)
			put("contentType", "text")
			if (isSpoiler) put("spoiler", true)
			parentComment?.id?.let { put("parentId", it) }
		}
		val res = executeApiRequest("POST", url, payload)
		val obj = res.optJSONObject("data") ?: res.optJSONObject("comment") ?: res
		val currentUser = fetchCurrentUser()
		val currentUserId = currentUser?.getStringOrNull("id") ?: currentUser?.getStringOrNull("_id")
		val currentUsername = currentUser?.getStringOrNull("username")
		if (obj.has("id") || obj.has("_id")) {
			return parseApiComment(obj, currentUserId, currentUsername)
		}
		return SourceComment(
			id = res.opt("id")?.toString()?.nullIfEmpty() ?: "gb-${System.currentTimeMillis()}",
			authorName = currentUser?.getStringOrNull("displayName") ?: currentUsername ?: "You",
			body = text,
			createdAtEpochMs = System.currentTimeMillis(),
			isSpoiler = isSpoiler,
			parentId = parentComment?.id,
			isOwnComment = true,
			canReply = true,
			canLike = true,
			canDislike = true,
			canEdit = true,
			canDelete = true,
		)
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		val url = apiUrlBuilder().addPathSegment("comments").addPathSegment(comment.id).build()
		val payload = JSONObject().apply { put("content", newText) }
		val res = executeApiRequest("PATCH", url, payload)
		val obj = res.optJSONObject("data") ?: res.optJSONObject("comment") ?: res
		val updatedBody = obj.getStringOrNull("content")?.nullIfEmpty() ?: newText
		return comment.copy(
			body = updatedBody,
			isEdited = true,
		)
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		val url = apiUrlBuilder().addPathSegment("comments").addPathSegment(comment.id).build()
		executeApiRequest("DELETE", url, null)
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val url = apiUrlBuilder()
			.addPathSegment("comments")
			.addPathSegment(comment.id)
			.addPathSegment("vote")
			.build()
		val payload = JSONObject().apply { put("voteType", if (isLike) "like" else "dislike") }
		val res = executeApiRequest("POST", url, payload)
		val reactions = res.optJSONObject("reactionCounts")
		val likes = reactions?.opt("like")?.toString()?.toIntOrNull()
			?: res.opt("likes")?.toString()?.toIntOrNull()
			?: res.opt("likesCount")?.toString()?.toIntOrNull()
			?: (comment.likesCount + if (isLike) (if (comment.userVote == 1) -1 else 1) else if (comment.userVote == 1) -1 else 0).coerceAtLeast(0)
		val dislikes = reactions?.opt("dislike")?.toString()?.toIntOrNull()
			?: res.opt("dislikes")?.toString()?.toIntOrNull()
			?: res.opt("dislikesCount")?.toString()?.toIntOrNull()
			?: (comment.dislikesCount + if (!isLike) (if (comment.userVote == -1) -1 else 1) else if (comment.userVote == -1) -1 else 0).coerceAtLeast(0)
		val newVote = when {
			res.has("isLiked") || res.has("isDisliked") -> when {
				res.getBooleanOrDefault("isLiked", false) -> 1
				res.getBooleanOrDefault("isDisliked", false) -> -1
				else -> 0
			}
			else -> if (isLike) {
				if (comment.userVote == 1) 0 else 1
			} else {
				if (comment.userVote == -1) 0 else -1
			}
		}
		return comment.copy(
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = newVote,
		)
	}

	private fun extractSeriesSlug(manga: Manga): String {
		return manga.url.trimEnd('/').substringAfterLast('/').nullIfEmpty() ?: slugify(manga.title)
	}

	private suspend fun resolveChapterId(chapter: MangaChapter): String? {
		val segments = chapter.url.trim('/').split('/')
		val seriesSlug = segments.getOrNull(1) ?: return null
		val chapterSlug = segments.getOrNull(3) ?: return null
		return runCatching {
			val chUrl = apiUrlBuilder()
				.addPathSegment("chapters")
				.addPathSegment(seriesSlug)
				.addPathSegment(chapterSlug)
				.build()
			webClient.httpGet(chUrl).parseJson().getStringOrNull("id")?.nullIfEmpty()
		}.getOrNull()
	}

	private suspend fun resolveCommentTarget(manga: Manga, chapter: MangaChapter?): Pair<String, String> {
		if (chapter != null) {
			resolveChapterId(chapter)?.let { return it to "CHAPTER" }
		}
		val seriesSlug = extractSeriesSlug(manga)
		val detailUrl = apiUrlBuilder().addPathSegment("series").addPathSegment(seriesSlug).build()
		val detail = webClient.httpGet(detailUrl).parseJson()
		val seriesId = detail.getStringOrNull("id")?.nullIfEmpty()
			?: throw ParseException("Series id missing", detailUrl.toString())
		return seriesId to "SERIES"
	}

	private suspend fun fetchCurrentUser(): JSONObject? {
		return runCatching {
			val meUrl = apiUrlBuilder().addPathSegment("auth").addPathSegment("me").build()
			val req = Request.Builder()
				.get()
				.url(meUrl)
				.headers(getRequestHeaders())
				.header("Accept", "application/json")
				.header("Origin", "https://$domain")
				.header("Referer", "https://$domain/")
				.tag(MangaSource::class.java, source)
				.build()
			context.httpClient.newCall(req).await().use { response ->
				if (!response.isSuccessful) return@use null
				val json = response.body.string().toJSONObjectOrNull() ?: return@use null
				val user = json.optJSONObject("user") ?: json
				val hasIdentity = !user.getStringOrNull("id").isNullOrBlank() ||
					!user.getStringOrNull("_id").isNullOrBlank() ||
					!user.getStringOrNull("username").isNullOrBlank()
				if (hasIdentity) user else null
			}
		}.getOrNull()
	}

	private suspend fun executeApiRequest(
		method: String,
		url: HttpUrl,
		payload: JSONObject?,
		throwOnAuthError: Boolean = true,
	): JSONObject {
		val reqBuilder = Request.Builder()
			.url(url)
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("Origin", "https://$domain")
			.header("Referer", "https://$domain/")
			.tag(MangaSource::class.java, source)

		val mediaType = "application/json; charset=utf-8".toMediaType()
		when (method.uppercase(Locale.ROOT)) {
			"GET" -> reqBuilder.get()
			"DELETE" -> reqBuilder.delete(payload?.toString()?.toRequestBody(mediaType))
			"PATCH" -> reqBuilder.patch((payload ?: JSONObject()).toString().toRequestBody(mediaType))
			else -> reqBuilder.post((payload ?: JSONObject()).toString().toRequestBody(mediaType))
		}

		return context.httpClient.newCall(reqBuilder.build()).await().use { response ->
			if (throwOnAuthError && (response.code == 401 || response.code == 403)) {
				throw AuthRequiredException(source)
			}
			val raw = response.body.string()
			val parsed = raw.toJSONObjectOrNull()
			if (!response.isSuccessful) {
				val errMsg = parsed?.getStringOrNull("error")
					?: parsed?.getStringOrNull("message")
					?: "HTTP ${response.code}"
				throw ParseException(errMsg, url.toString())
			}
			parsed ?: JSONObject()
		}
	}

	private fun parseApiComment(
		jo: JSONObject,
		currentUserId: String?,
		currentUsername: String?,
	): SourceComment {
		val id = jo.getStringOrNull("id")?.nullIfEmpty() ?: jo.getStringOrNull("_id") ?: "0"
		val userId = jo.getStringOrNull("userId")?.nullIfEmpty()
		val parentId = jo.getStringOrNull("parentId")?.nullIfEmpty()
		val snapshot = jo.optJSONObject("userSnapshot")
		val username = snapshot?.getStringOrNull("username")?.nullIfEmpty()
		val authorName = snapshot?.getStringOrNull("displayName")?.nullIfEmpty()
			?: username
			?: "Anonim"
		val avatarUrl = snapshot?.getStringOrNull("avatar")?.nullIfEmpty()
		val authorRole = snapshot?.getStringOrNull("role")?.nullIfEmpty()
		val contentType = jo.getStringOrNull("contentType")
		val rawContent = jo.getStringOrNull("content").orEmpty()
		val bodyHtml = if (contentType == "sticker" && rawContent.startsWith("http")) {
			"<img src=\"$rawContent\" alt=\"sticker\"/>"
		} else {
			null
		}
		val isSpoiler = jo.getBooleanOrDefault("spoiler", false)
		val isEdited = jo.getBooleanOrDefault("isEdited", false)
		val reactions = jo.optJSONObject("reactionCounts")
		val likes = reactions?.opt("like")?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		val dislikes = reactions?.opt("dislike")?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		val userVote = when {
			jo.getBooleanOrDefault("isLiked", false) -> 1
			jo.getBooleanOrDefault("isDisliked", false) -> -1
			else -> 0
		}
		val repliesArr = jo.optJSONArray("replies")
		val replies = ArrayList<SourceComment>(repliesArr?.length() ?: 0)
		if (repliesArr != null) {
			for (i in 0 until repliesArr.length()) {
				val rObj = repliesArr.optJSONObject(i) ?: continue
				replies.add(parseApiComment(rObj, currentUserId, currentUsername))
			}
		}
		val repliesCount = maxOf(jo.opt("replyCount")?.toString()?.toIntOrNull() ?: 0, replies.size)
		val createdAt = jo.getStringOrNull("createdAt")
		val isOwn = (currentUserId != null && userId == currentUserId) ||
			(currentUsername != null && username != null && username.equals(currentUsername, ignoreCase = true))
		val isLoggedIn = currentUserId != null || currentUsername != null

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = authorRole,
			body = rawContent,
			bodyHtml = bodyHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isEdited = isEdited,
			isSpoiler = isSpoiler,
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = userVote,
			repliesCount = repliesCount,
			parentId = parentId,
			replies = replies,
			isOwnComment = isOwn,
			canReply = isLoggedIn,
			canLike = isLoggedIn,
			canDislike = isLoggedIn,
			canEdit = isOwn,
			canDelete = isOwn,
		)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		val url = apiUrlBuilder().addPathSegment("genres").build()
		val arr = webClient.httpGet(url).parseJsonArray()
		val tags = arr.mapJSONNotNullToSet { item ->
			val slug = item.getStringOrNull("slug")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			val name = item.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}
		if (tags.isNotEmpty()) {
			cachedTags = tags
		}
		return tags
	}

	private fun apiUrlBuilder(): HttpUrl.Builder = HttpUrl.Builder()
		.scheme("https")
		.host("api.$domain")
		.addPathSegment("api")

	private fun parseState(status: String?): MangaState? = when (status?.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseIsoDate(dateStr: String?): Long {
		val clean = dateStr?.substringBefore('.')?.removeSuffix("Z")?.trim()?.nullIfEmpty() ?: return 0L
		return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}.parseSafe(clean)
	}

	private fun slugify(name: String): String {
		return name.lowercase(sourceLocale)
			.replace('ç', 'c')
			.replace('ğ', 'g')
			.replace('ı', 'i')
			.replace('ö', 'o')
			.replace('ş', 's')
			.replace('ü', 'u')
			.replace(Regex("""[^a-z0-9]+"""), "-")
			.trim('-')
	}
}
