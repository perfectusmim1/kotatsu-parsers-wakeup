package org.koitharu.kotatsu.parsers.site.heancms.en

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.site.heancms.HeanCms
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.Locale

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
	HeanCms(context, MangaParserSource.OMEGASCANS, "omegascans.org"),
	MangaSourceCommunityProvider {

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = false,
		supportsReplies = true,
		supportsCommentLikes = false,
		supportsCommentDislikes = false,
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
	): SourceMangaCommunityInfo {
		val slug = manga.publicUrl.trimEnd('/').substringAfterLast('/').nullIfEmpty()
		val seriesJo = slug?.let {
			runCatching { webClient.httpGet("https://$apiPath/series/$it").parseJson() }.getOrNull()
		}
		val seriesId = manga.url.toLongOrNull()
			?: seriesJo?.getLongOrDefault("id", 0L)?.takeIf { it > 0L }
		val rawRating = seriesJo?.getFloatOrDefault("rating", -1f)?.takeIf { it > 0f }
			?: manga.rating.takeIf { it > 0f }?.times(5f)
		val viewCount = seriesJo?.getLongOrDefault("total_views", -1L)?.takeIf { it >= 0L }
		val commentCount = if (seriesId != null && chapter == null) {
			runCatching {
				webClient.httpGet("https://$apiPath/series/$seriesId/comments?limit=1")
					.parseJson()
					.optJSONObject("meta")
					?.getIntOrDefault("total", -1)
					?.takeIf { it >= 0 }
			}.getOrNull()
		} else {
			null
		}

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 5f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 5,
			viewCount = viewCount,
			commentCount = commentCount,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val seriesId = resolveSeriesId(manga)
		val sortKey = when (sort) {
			SourceCommentSort.TOP -> "top"
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.NEWEST -> "newest"
		}
		val chapterId = if (chapter != null && seriesId != null) {
			resolveChapterId(seriesId, chapter)
		} else {
			null
		}

		val endpoint = when {
			chapter != null && chapterId != null -> buildString {
				append("https://").append(apiPath).append("/chapter/").append(chapterId)
				append("/comments?sort=").append(sortKey).append("&limit=20")
				if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor.urlEncoded())
			}
			seriesId != null -> buildString {
				append("https://").append(apiPath).append("/series/").append(seriesId)
				append("/comments?sort=").append(sortKey).append("&limit=20")
				if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor.urlEncoded())
			}
			else -> null
		}

		if (endpoint != null) {
			val response = runCatching { webClient.httpGet(endpoint).parseJson() }.getOrNull()
			val data = response?.optJSONArray("data")
			if (data != null) {
				val isChapterScope = chapter != null && chapterId != null
				val comments = (0 until data.length()).mapNotNull { i ->
					data.optJSONObject(i)?.let { parseOmegaComment(it, isChapterScope = isChapterScope) }
				}
				val meta = response.optJSONObject("meta")
				val nextCursor = meta?.getStringOrNull("next_cursor")
				val hasNext = meta?.getBooleanOrDefault("has_more", false) == true || nextCursor != null
				val total = meta?.getIntOrDefault("total", -1)?.takeIf { it >= 0 }
				return SourceCommentPage(
					comments = comments,
					page = page,
					hasNextPage = hasNext,
					totalCount = total,
					engine = SourceCommentEngine.NATIVE,
					nextCursor = nextCursor,
				)
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

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage {
		val isChapterScope = comment.rawMeta["scope"] == "chapter" || chapter != null
		val prefix = if (isChapterScope) "chapter" else "series"
		val endpoint = buildString {
			append("https://").append(apiPath).append("/").append(prefix)
			append("/comments/").append(comment.id).append("/replies?limit=20")
			if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor.urlEncoded())
		}
		val response = runCatching { webClient.httpGet(endpoint).parseJson() }.getOrNull()
		val data = response?.optJSONArray("data")
		val replies = if (data != null) {
			(0 until data.length()).mapNotNull { i ->
				data.optJSONObject(i)?.let {
					parseOmegaComment(it, parentId = comment.id, isChapterScope = isChapterScope)
				}
			}
		} else {
			emptyList()
		}
		val meta = response?.optJSONObject("meta")
		val nextCursor = meta?.getStringOrNull("next_cursor")
		val hasNext = meta?.getBooleanOrDefault("has_more", false) == true || nextCursor != null
		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = hasNext,
			engine = SourceCommentEngine.NATIVE,
			nextCursor = nextCursor,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = chapter?.url?.toAbsoluteUrl(domain)
			?: manga.publicUrl.ifBlank { "https://$domain/series/${manga.url}" }
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

	private suspend fun resolveSeriesId(manga: Manga): Long? {
		manga.url.toLongOrNull()?.let { return it }
		val slug = manga.publicUrl.trimEnd('/').substringAfterLast('/').nullIfEmpty() ?: return null
		return runCatching {
			webClient.httpGet("https://$apiPath/series/$slug").parseJson().getLongOrDefault("id", 0L)
		}.getOrNull()?.takeIf { it > 0L }
	}

	private suspend fun resolveChapterId(seriesId: Long, chapter: MangaChapter): Long? {
		val chapSlug = chapter.url.trimEnd('/').substringAfterLast('/').nullIfEmpty() ?: return null
		val arr = runCatching {
			webClient.httpGet(reqUrl(seriesId)).parseJson().optJSONArray("data")
		}.getOrNull() ?: return null
		for (i in 0 until arr.length()) {
			val jo = arr.optJSONObject(i) ?: continue
			if (jo.getStringOrNull("chapter_slug") == chapSlug) {
				return jo.getLongOrDefault("id", 0L).takeIf { it > 0L }
			}
		}
		return null
	}

	private fun parseOmegaComment(
		jo: JSONObject,
		parentId: String? = null,
		isChapterScope: Boolean = false,
	): SourceComment? {
		val id = jo.getStringOrNull("id") ?: return null
		val user = jo.optJSONObject("user")
		val authorName = user?.getStringOrNull("username") ?: "Anonymous"
		val avatarUrl = user?.getStringOrNull("profile_picture")
		val role = user?.getStringOrNull("role")?.takeUnless { it.equals("Reader", ignoreCase = true) }
		val rawContent = jo.getStringOrNull("content").orEmpty()
		val decodedHtml = Parser.unescapeEntities(rawContent, false)
		val plainText = Jsoup.parseBodyFragment(decodedHtml).text().trim().ifEmpty { rawContent }
		val createdAt = jo.getStringOrNull("created_at")
		val userVote = when {
			jo.getBooleanOrDefault("viewer_has_liked", false) -> 1
			jo.getBooleanOrDefault("viewer_has_disliked", false) -> -1
			else -> 0
		}
		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = role,
			body = plainText,
			bodyHtml = decodedHtml.nullIfEmpty(),
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = createdAt,
			isEdited = jo.getStringOrNull("edited_at") != null,
			isPinned = jo.getStringOrNull("pinned_at") != null,
			likesCount = jo.getIntOrDefault("like_count", 0),
			dislikesCount = jo.getIntOrDefault("dislike_count", 0),
			userVote = userVote,
			repliesCount = jo.getIntOrDefault("reply_count", 0),
			parentId = parentId ?: jo.getStringOrNull("parent_id"),
			rawMeta = mapOf("scope" to if (isChapterScope) "chapter" else "series"),
		)
	}

	private fun JSONObject.getStringOrNull(name: String): String? =
		opt(name)?.takeUnless { it === JSONObject.NULL }?.toString()?.nullIfEmpty()

	private fun JSONObject.getLongOrDefault(name: String, defaultValue: Long): Long {
		return when (val v = opt(name)) {
			null, JSONObject.NULL -> defaultValue
			is Number -> v.toLong()
			is String -> v.toLongOrNull() ?: defaultValue
			else -> defaultValue
		}
	}

	private fun JSONObject.getIntOrDefault(name: String, defaultValue: Int): Int {
		return when (val v = opt(name)) {
			null, JSONObject.NULL -> defaultValue
			is Number -> v.toInt()
			is String -> v.toIntOrNull() ?: defaultValue
			else -> defaultValue
		}
	}

	private fun JSONObject.getFloatOrDefault(name: String, defaultValue: Float): Float {
		return when (val v = opt(name)) {
			null, JSONObject.NULL -> defaultValue
			is Number -> v.toFloat()
			is String -> v.toFloatOrNull() ?: defaultValue
			else -> defaultValue
		}
	}

	private fun JSONObject.getBooleanOrDefault(name: String, defaultValue: Boolean): Boolean {
		return when (val v = opt(name)) {
			null, JSONObject.NULL -> defaultValue
			is Boolean -> v
			is Number -> v.toInt() != 0
			is String -> v.lowercase(Locale.ROOT).toBooleanStrictOrNull() ?: defaultValue
			else -> defaultValue
		}
	}
}
