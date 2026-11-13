package org.koitharu.kotatsu.parsers.site.community

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.textOrNull
import java.util.Locale

internal class MerlinCommentsHelper(
	private val context: MangaLoaderContext,
	private val source: MangaSource,
	private val domainProvider: () -> String,
	private val requestHeadersProvider: () -> Headers = { Headers.Builder().build() },
	private val localeProvider: () -> Locale = { Locale.ROOT },
) {

	internal data class MerlinConfig(
		val postId: String,
		val ajaxUrl: String,
		val nonce: String?,
		val currentUserId: String?,
		val isLoggedIn: Boolean,
	)

	fun isMerlinPresent(doc: Document, pageUrl: String = doc.location()): Boolean {
		return extractConfig(doc, pageUrl) != null
	}

	fun extractTotalCount(doc: Document): Int? {
		return doc.selectFirst("#mcp-total-count")?.textOrNull()?.let { parseCompactCount(it)?.toInt() }
	}

	fun extractConfig(doc: Document, pageUrl: String = doc.location()): MerlinConfig? {
		val section = doc.selectFirst("#merlin-comments-section[data-post-id]")
		val scriptsText = collectScriptsText(doc)
		val obj = extractBalancedJsonObjectAfter(scriptsText, "MerlinComments =")?.toJSONObjectOrNull()
			?: extractBalancedJsonObjectAfter(scriptsText, "MerlinComments=")?.toJSONObjectOrNull()
		if (section == null && obj == null) return null

		val postId = section?.attrOrNull("data-post-id")
			?: obj?.getStringOrNull("post_id")?.takeIf { it.isNotBlank() && it != "0" }
			?: return null
		val resolvedOrigin = pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
			?: "https://${domainProvider()}"
		val ajaxUrl = obj?.getStringOrNull("ajaxurl")?.nullIfEmpty()
			?: "$resolvedOrigin/wp-admin/admin-ajax.php"
		val nonce = obj?.getStringOrNull("nonce")?.nullIfEmpty()
		val userId = obj?.getStringOrNull("user_id")?.takeUnless { it.isBlank() || it == "0" }
		val isLoggedIn = obj?.getStringOrNull("is_logged_in")?.let {
			it == "1" || it.equals("true", ignoreCase = true)
		} ?: (userId != null)
		return MerlinConfig(
			postId = postId,
			ajaxUrl = ajaxUrl,
			nonce = nonce,
			currentUserId = userId,
			isLoggedIn = isLoggedIn,
		)
	}

	suspend fun getComments(
		doc: Document,
		pageUrl: String,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
	): SourceCommentPage {
		val merlin = extractConfig(doc, pageUrl)
			?: throw ParseException("Merlin comments configuration not found", pageUrl)
		val sortParam = when (sort) {
			SourceCommentSort.OLDEST -> "oldest"
			SourceCommentSort.TOP -> "top"
			SourceCommentSort.NEWEST -> "newest"
		}
		val form = FormBody.Builder()
			.add("action", "mcp_load_comments")
			.add("post_id", merlin.postId)
			.add("page", page.coerceAtLeast(1).toString())
			.add("sort", sortParam)
			.apply { merlin.nonce?.let { add("nonce", it) } }
			.build()

		val json = postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
		val data = json.optJSONObject("data") ?: JSONObject()
		val userVotes = data.optJSONObject("user_votes")
		val commentsArray = data.optJSONArray("comments") ?: JSONArray()
		val comments = parseCommentsArray(commentsArray, merlin, userVotes)
		val total = data.opt("total")?.toString()?.toIntOrNull()
		val totalPages = data.opt("pages")?.toString()?.toIntOrNull() ?: 1
		val currentPage = data.opt("current_page")?.toString()?.toIntOrNull() ?: page

		return SourceCommentPage(
			comments = comments,
			page = currentPage,
			hasNextPage = currentPage < totalPages,
			totalCount = total,
			engine = SourceCommentEngine.NATIVE,
		)
	}

	suspend fun postComment(
		doc: Document,
		pageUrl: String,
		text: String,
		parentComment: SourceComment? = null,
		isSpoiler: Boolean = false,
	): SourceComment {
		val merlin = extractConfig(doc, pageUrl)
			?: throw ParseException("Merlin comments configuration not found", pageUrl)
		val form = FormBody.Builder()
			.add("action", "mcp_submit_comment")
			.add("post_id", merlin.postId)
			.add("parent_id", parentComment?.id ?: "0")
			.add("content", text)
			.add("has_spoiler", if (isSpoiler) "1" else "0")
			.apply { merlin.nonce?.let { add("nonce", it) } }
			.build()

		val json = postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
		val dataObj = json.optJSONObject("data")
		val commentObj = dataObj?.optJSONObject("comment")
		if (commentObj != null) {
			return parseComment(commentObj, merlin, null)
		}
		return SourceComment(
			id = dataObj?.opt("comment_id")?.toString()?.nullIfEmpty() ?: "mcp-${System.currentTimeMillis()}",
			authorName = "You",
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

	suspend fun editComment(
		doc: Document,
		pageUrl: String,
		comment: SourceComment,
		newText: String,
	): SourceComment {
		val merlin = extractConfig(doc, pageUrl)
			?: throw ParseException("Merlin comments configuration not found", pageUrl)
		val form = FormBody.Builder()
			.add("action", "mcp_edit_comment")
			.add("comment_id", comment.id)
			.add("content", newText)
			.apply { merlin.nonce?.let { add("nonce", it) } }
			.build()

		val json = postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
		val html = json.optJSONObject("data")?.getStringOrNull("content_html")
		val body = html?.let { Jsoup.parseBodyFragment(it).text().nullIfEmpty() } ?: newText
		return comment.copy(body = body, bodyHtml = html, isEdited = true)
	}

	suspend fun deleteComment(
		doc: Document,
		pageUrl: String,
		comment: SourceComment,
	) {
		val merlin = extractConfig(doc, pageUrl)
			?: throw ParseException("Merlin comments configuration not found", pageUrl)
		val form = FormBody.Builder()
			.add("action", "mcp_delete_comment")
			.add("comment_id", comment.id)
			.apply { merlin.nonce?.let { add("nonce", it) } }
			.build()
		postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
	}

	suspend fun toggleCommentLike(
		doc: Document,
		pageUrl: String,
		comment: SourceComment,
		isLike: Boolean = true,
	): SourceComment {
		val merlin = extractConfig(doc, pageUrl)
			?: throw ParseException("Merlin comments configuration not found", pageUrl)
		val form = FormBody.Builder()
			.add("action", "mcp_vote")
			.add("comment_id", comment.id)
			.add("vote_type", if (isLike) "like" else "dislike")
			.apply { merlin.nonce?.let { add("nonce", it) } }
			.build()

		val json = postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
		val dataObj = json.optJSONObject("data")
		val likes = dataObj?.opt("likes_count")?.toString()?.toIntOrNull()
			?: (comment.likesCount + if (isLike && comment.userVote != 1) 1 else if (isLike && comment.userVote == 1) -1 else 0)
				.coerceAtLeast(0)
		val dislikes = dataObj?.opt("dislikes_count")?.toString()?.toIntOrNull()
			?: (comment.dislikesCount + if (!isLike && comment.userVote != -1) 1 else if (!isLike && comment.userVote == -1) -1 else 0)
				.coerceAtLeast(0)
		val newVote = if (isLike) {
			if (comment.userVote == 1) 0 else 1
		} else {
			if (comment.userVote == -1) 0 else -1
		}
		return comment.copy(
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = newVote,
		)
	}

	private suspend fun postMerlinAjax(ajaxUrl: String, form: FormBody, referer: String): JSONObject {
		val request = Request.Builder()
			.post(form)
			.url(ajaxUrl)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json, */*")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", referer)
			.tag(MangaSource::class.java, source)
			.build()

		val json = context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val raw = response.body.string()
			if (!response.isSuccessful) {
				throw ParseException("Merlin comments AJAX HTTP ${response.code}", ajaxUrl)
			}
			raw.toJSONObjectOrNull() ?: throw ParseException("Invalid Merlin comments response", ajaxUrl)
		}
		if (!json.getBooleanOrDefault("success", false)) {
			val msg = json.optJSONObject("data")?.getStringOrNull("message")
				?: json.getStringOrNull("data")
				?: "Merlin comments request failed"
			val lower = msg.lowercase(localeProvider())
			if ("giriş" in lower || "login" in lower || "oturum" in lower) {
				throw AuthRequiredException(source)
			}
			throw ParseException(msg, ajaxUrl)
		}
		return json
	}

	private fun parseCommentsArray(
		array: JSONArray,
		merlin: MerlinConfig,
		userVotes: JSONObject?,
	): List<SourceComment> {
		val list = ArrayList<SourceComment>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			list.add(parseComment(jo, merlin, userVotes))
		}
		return list
	}

	private fun parseComment(
		jo: JSONObject,
		merlin: MerlinConfig,
		userVotes: JSONObject?,
	): SourceComment {
		val id = jo.opt("id")?.toString()?.nullIfEmpty() ?: "0"
		val userId = jo.opt("user_id")?.toString()?.takeUnless { it == "0" }?.nullIfEmpty()
		val parentId = jo.opt("parent_id")?.toString()?.takeUnless { it == "0" }?.nullIfEmpty()
		val authorName = jo.getStringOrNull("display_name")
			?: jo.getStringOrNull("user_display_name")
			?: jo.getStringOrNull("guest_name")
			?: "Misafir"
		val avatarUrl = jo.getStringOrNull("avatar")?.nullIfEmpty()
		val contentHtml = jo.getStringOrNull("content_html")
		val rawContent = jo.getStringOrNull("content")
		val body = contentHtml?.let { Jsoup.parseBodyFragment(it).text().nullIfEmpty() }
			?: rawContent
			?: ""
		val createdAt = jo.getStringOrNull("created_at")
		val timeAgo = jo.getStringOrNull("time_ago")
		val isPinned = jo.opt("is_pinned")?.toString() == "1" || jo.getBooleanOrDefault("is_pinned", false)
		val isSpoiler = jo.opt("has_spoiler")?.toString() == "1" || jo.getBooleanOrDefault("has_spoiler", false)
		val isEdited = jo.getBooleanOrDefault("is_edited", false)
		val likes = jo.opt("likes_count")?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		val dislikes = jo.opt("dislikes_count")?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		val userVote = when (userVotes?.optString(id)?.lowercase(Locale.ROOT)) {
			"like", "1" -> 1
			"dislike", "-1" -> -1
			else -> 0
		}
		val repliesArray = jo.optJSONArray("replies")
		val replies = if (repliesArray != null && repliesArray.length() > 0) {
			parseCommentsArray(repliesArray, merlin, userVotes)
		} else {
			emptyList()
		}
		val repliesCount = maxOf(jo.opt("replies_count")?.toString()?.toIntOrNull() ?: 0, replies.size)
		val isOwn = merlin.currentUserId != null && userId == merlin.currentUserId

		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			body = body,
			bodyHtml = contentHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAt),
			createdAtText = timeAgo ?: createdAt,
			isEdited = isEdited,
			isSpoiler = isSpoiler,
			isPinned = isPinned,
			likesCount = likes,
			dislikesCount = dislikes,
			userVote = userVote,
			repliesCount = repliesCount,
			parentId = parentId,
			replies = replies,
			isOwnComment = isOwn,
			canReply = merlin.isLoggedIn,
			canLike = merlin.isLoggedIn,
			canDislike = merlin.isLoggedIn,
			canEdit = isOwn,
			canDelete = isOwn,
		)
	}
}
