package org.koitharu.kotatsu.parsers.site.tr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.site.uzaymanga.UzayMangaParser
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull

@Broken
@MangaSourceParser("TENSHIMANGA", "Tenshi Manga", "tr")
internal class TenshiManga(context: MangaLoaderContext) :
	UzayMangaParser(
		context = context,
		source = MangaParserSource.TENSHIMANGA,
		domain = "tenshimanga.com",
		cdnUrl = "https://tenshimangacdn4.efsaneler.can.re",
	),
	MangaSourceCommunityProvider {

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = false,
		supportsReplies = true,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
		supportsCommentEditing = false,
		supportsCommentDeleting = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = false,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(
			SourceCommentSort.TOP,
			SourceCommentSort.NEWEST,
		),
	)

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl, extraHeaders = siteRefererHeaders()).parseHtml()
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }

		val rawRating = REGEX_SERIES_POINT.find(scriptsText)?.groupValues?.getOrNull(1)?.toFloatOrNull()
			?: extractJsonLdRating(doc)
		val totalView = REGEX_SERIES_VIEWS.find(scriptsText)?.groupValues?.getOrNull(1)?.toLongOrNull()
		val totalComments = REGEX_COMMENTS_TOTAL.find(scriptsText)?.groupValues?.getOrNull(1)?.toIntOrNull()

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 10f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 10,
			viewCount = totalView,
			commentCount = if (chapter == null) totalComments else null,
			chapterCommentCount = if (chapter != null) totalComments else null,
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
		val doc = webClient.httpGet(pageUrl, extraHeaders = siteRefererHeaders()).parseHtml()
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }

		val (targetType, targetId) = if (chapter != null) {
			"episode" to REGEX_EPISODE_ID.find(scriptsText)?.groupValues?.getOrNull(1)?.toLongOrNull()
		} else {
			"series" to REGEX_SERIES_ID.find(scriptsText)?.groupValues?.getOrNull(1)?.toLongOrNull()
		}

		if (targetId == null) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NATIVE,
			)
		}

		val skip = cursor?.toIntOrNull() ?: ((page - 1).coerceAtLeast(0) * COMMENTS_LIMIT)
		val sortKey = if (sort == SourceCommentSort.NEWEST) "newest" else "popular"
		val requestBody = JSONObject().apply {
			put(
				"json",
				JSONObject().apply {
					put("targetType", targetType)
					put("targetId", targetId)
					put("skip", skip)
					put("limit", COMMENTS_LIMIT)
					put("sort", sortKey)
				},
			)
		}

		val request = Request.Builder()
			.post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
			.url("https://$domain/rpc/comments/getComments")
			.headers(getRequestHeaders())
			.header("Accept", "application/json")
			.header("Referer", pageUrl)
			.header("Origin", "https://$domain")
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				return SourceCommentPage(
					comments = emptyList(),
					page = page,
					hasNextPage = false,
					engine = SourceCommentEngine.NATIVE,
				)
			}
			response.body.string().toJSONObjectOrNull()
		}

		val payload = json?.optJSONObject("json") ?: json
		val commentsArray = payload?.optJSONArray("comments") ?: JSONArray()
		val comments = parseCommentsArray(commentsArray)
		val totalCount = payload?.opt("totalCount")?.toString()?.toIntOrNull()
		val hasMore = payload?.getBooleanOrDefault("hasMore", false) ?: false
		val nextCursor = if (hasMore) (skip + comments.size).toString() else null

		return SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasMore,
			totalCount = totalCount,
			engine = SourceCommentEngine.NATIVE,
			nextCursor = nextCursor,
		)
	}

	private fun parseCommentsArray(array: JSONArray): List<SourceComment> {
		val list = ArrayList<SourceComment>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			list.add(parseCommentObject(jo))
		}
		return list
	}

	private fun parseCommentObject(jo: JSONObject): SourceComment {
		val id = jo.opt("id")?.toString()?.nullIfEmpty() ?: "0"
		val userObj = jo.optJSONObject("user")
		val authorName = userObj?.getStringOrNull("name") ?: "Anonim"
		val rawImage = userObj?.getStringOrNull("image")?.nullIfEmpty()
		val avatarUrl = rawImage?.let { img ->
			if (img.startsWith("http://") || img.startsWith("https://")) {
				img
			} else {
				"https://$domain/${img.removePrefix("/")}"
			}
		}
		val role = userObj?.getStringOrNull("role")?.takeUnless { it.equals("user", ignoreCase = true) }
		val body = jo.getStringOrNull("content") ?: ""
		val createdAt = jo.getStringOrNull("createdAt")
		val isSpoiler = jo.getBooleanOrDefault("isSpoiler", false)
		val isPinned = jo.getBooleanOrDefault("isPin", false)
		val parentId = jo.opt("parentId")?.takeIf { it != JSONObject.NULL }?.toString()?.nullIfEmpty()
		val likesCount = parseLikeSummaryCount(jo.getStringOrNull("likeSummary"))
		val repliesArray = jo.optJSONArray("replies")
		val replies = if (repliesArray != null && repliesArray.length() > 0) {
			parseCommentsArray(repliesArray)
		} else {
			emptyList()
		}

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = role,
			body = body,
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isSpoiler = isSpoiler,
			isPinned = isPinned,
			likesCount = likesCount,
			repliesCount = replies.size,
			parentId = parentId,
			replies = replies,
			canReply = false,
		)
	}

	private fun parseLikeSummaryCount(raw: String?): Int {
		val obj = raw?.toJSONObjectOrNull() ?: return 0
		var sum = 0
		for (key in obj.keys()) {
			sum += obj.optInt(key, 0).coerceAtLeast(0)
		}
		return sum
	}

	private fun extractJsonLdRating(doc: Document): Float? {
		for (script in doc.select("script[type=\"application/ld+json\"]")) {
			val obj = script.data().trim().toJSONObjectOrNull() ?: continue
			val agg = obj.optJSONObject("aggregateRating") ?: continue
			val rating = agg.opt("ratingValue")?.toString()?.replace(',', '.')?.toFloatOrNull()
			if (rating != null) return rating
		}
		return null
	}

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}

	private fun siteRefererHeaders() = getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	private companion object {
		const val COMMENTS_LIMIT = 20
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val REGEX_SERIES_ID = Regex("""\bseries:\{id:(\d+)""")
		val REGEX_EPISODE_ID = Regex("""\bepisode:\{id:(\d+)""")
		val REGEX_SERIES_POINT = Regex("""\bseries:\{[^}]*?\bpoint:(\d+(?:\.\d+)?)""")
		val REGEX_SERIES_VIEWS = Regex("""\bseries:\{[^}]*?\btotalView:(\d+)""")
		val REGEX_COMMENTS_TOTAL = Regex("""\bcommentsData:\{[^}]*?\btotalCount:(\d+)""")
	}
}
