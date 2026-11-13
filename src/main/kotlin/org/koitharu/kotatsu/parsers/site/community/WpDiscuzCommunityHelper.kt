package org.koitharu.kotatsu.parsers.site.community

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.util.Locale

internal class WpDiscuzCommunityHelper(
	private val context: MangaLoaderContext,
	private val webClient: WebClient,
	private val source: MangaSource,
	private val domainProvider: () -> String,
	private val requestHeadersProvider: () -> Headers = { Headers.Builder().build() },
) {

	internal data class WpDiscuzConfig(
		val postId: String,
		val nonce: String?,
		val ajaxUrl: String,
		val lastParentId: String? = null,
		val commentsCount: Int? = null,
	)

	fun isWpDiscuzPresent(doc: Document): Boolean {
		if (doc.selectFirst("#wpdcom, .wpd-thread-list, .wpd-comment") != null) return true
		return doc.select("script").any { s ->
			val data = s.data()
			data.contains("wpdiscuzAjaxObj") || data.contains("wpdiscuz_nonce") || data.contains("wpdNonce")
		}
	}

	fun extractConfig(doc: Document): WpDiscuzConfig? {
		if (!isWpDiscuzPresent(doc)) return null
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		val ajaxObj = extractBalancedJsonObjectAfter(scriptsText, "wpdiscuzAjaxObj =")
			?.toJSONObjectOrNull()
			?: extractBalancedJsonObjectAfter(scriptsText, "wpdiscuzAjaxObj=")
				?.toJSONObjectOrNull()

		val domain = domainProvider()
		val ajaxUrl = ajaxObj?.getStringOrNull("url")?.toAbsoluteUrl(domain)
			?: doc.selectFirst("#wpdcom[data-ajax-url]")?.attrOrNull("data-ajax-url")?.toAbsoluteUrl(domain)
			?: "https://$domain/wp-admin/admin-ajax.php"

		val nonce = ajaxObj?.let { obj ->
			obj.getStringOrNull("wpdiscuz_nonce")
				?: obj.getStringOrNull("wpdNonce")
				?: obj.getStringOrNull("nonce")
		}
			?: doc.selectFirst("#wpdcom [name=wpdiscuz_nonce], #wpdcom [name=wpdNonce], input[name=wpdiscuz_nonce], input[name=wpdNonce]")
				?.attrOrNull("value")
			?: doc.selectFirst("#wpdcom[data-nonce]")?.attrOrNull("data-nonce")
			?: REGEX_WPD_NONCE.find(scriptsText)?.groupValues?.getOrNull(1)?.nullIfEmpty()

		val postId = ajaxObj?.let { obj ->
			obj.getStringOrNull("wc_post_id") ?: obj.getStringOrNull("postId")
		}
			?: doc.selectFirst("#wpdcom [name=wc_post_id], #wpdcom [name=postId], input[name=comment_post_ID]")
				?.attrOrNull("value")
			?: doc.selectFirst("#wpdcom[data-post-id]")?.attrOrNull("data-post-id")
			?: doc.selectFirst("input[name=wpdiscuz_unique_id]")?.attrOrNull("value")?.substringBefore('_')?.nullIfEmpty()
			?: WordPressCommentsHelper(context, webClient, source, domainProvider, requestHeadersProvider)
				.extractPostId(doc)?.toString()
			?: return null

		val lastParentId = doc.selectFirst(".wpd-load-more-submit[data-lastparentid]")?.attrOrNull("data-lastparentid")
			?: doc.selectFirst("#wpdcom[data-last-comment-id]")?.attrOrNull("data-last-comment-id")

		val commentsCount = doc.selectFirst("#wpdcom .wpd-thread-info[data-comments-count]")
			?.attrOrNull("data-comments-count")?.toIntOrNull()
			?: doc.selectFirst("#wpdcom .wpdtc")?.textOrNull()?.let { parseCompactCount(it)?.toInt() }

		return WpDiscuzConfig(
			postId = postId,
			nonce = nonce,
			ajaxUrl = ajaxUrl,
			lastParentId = lastParentId,
			commentsCount = commentsCount,
		)
	}

	fun parseCommentsFromDocument(doc: Document, page: Int = 1): SourceCommentPage {
		val config = extractConfig(doc)
		val root = doc.selectFirst("#wpdcom .wpd-thread-list") ?: doc
		val flat = parseCommentsFromElement(root)
		val nested = nestCommentsByParentId(flat)

		val loadMoreEl = doc.selectFirst(".wpd-load-more-submit")?.takeIf { el ->
			val wrapperStyle = el.parent()?.attr("style")?.replace(" ", "")?.lowercase(Locale.ROOT).orEmpty()
			"display:none" !in wrapperStyle
		}
		val nextCursor = loadMoreEl?.attrOrNull("data-lastparentid") ?: config?.lastParentId
		val hasNext = loadMoreEl != null ||
			doc.selectFirst(".wpd-comments-pagination a.next") != null

		return SourceCommentPage(
			comments = nested,
			page = page,
			hasNextPage = hasNext,
			totalCount = config?.commentsCount,
			engine = SourceCommentEngine.WPDISCUZ,
			nextCursor = if (hasNext) nextCursor else null,
		)
	}

	internal fun parseCommentsFromElement(root: Element): List<SourceComment> {
		val elements = root.select(".wpd-comment")
		if (elements.isEmpty()) return emptyList()

		val seenIds = HashSet<String>(elements.size)
		val result = ArrayList<SourceComment>(elements.size)
		for (el in elements) {
			val comment = parseSingleCommentElement(el) ?: continue
			if (seenIds.add(comment.id)) {
				result.add(comment)
			}
		}
		return result
	}

	private fun parseSingleCommentElement(el: Element): SourceComment? {
		val rawDomId = el.id() // typically wpd-comm-<id>_<parentId>
		var commentId: String? = el.attrOrNull("data-comment-id")
		var parentId: String? = el.attrOrNull("data-parent-id")?.takeUnless { it == "0" }

		if (rawDomId.startsWith("wpd-comm-")) {
			val suffix = rawDomId.removePrefix("wpd-comm-")
			val parts = suffix.split('_', limit = 2)
			if (commentId == null) {
				commentId = parts.getOrNull(0)?.nullIfEmpty()
			}
			if (parentId == null) {
				parentId = parts.getOrNull(1)?.nullIfEmpty()?.takeUnless { it == "0" }
			}
		} else if (commentId == null && rawDomId.startsWith("wpd-comment-")) {
			commentId = rawDomId.removePrefix("wpd-comment-").nullIfEmpty()
		}
		if (commentId == null) return null

		val wrap = el.selectFirst("> .wpd-comment-wrap") ?: el
		val authorName = wrap.selectFirst(".wpd-comment-author a, .wpd-comment-author")?.textOrNull() ?: "Guest"
		val avatarUrl = wrap.selectFirst(".wpd-avatar img")?.src()
		val role = wrap.selectFirst(".wpd-comment-label")?.textOrNull()
		val dateEl = wrap.selectFirst(".wpd-comment-date")
		val createdAtText = dateEl?.textOrNull() ?: dateEl?.attrOrNull("title")
		val createdAtRaw = dateEl?.attrOrNull("data-date") ?: dateEl?.attrOrNull("title")

		val textEl = wrap.selectFirst(".wpd-comment-text")
		val bodyHtml = textEl?.html()?.nullIfEmpty()
		val body = textEl?.textOrNull() ?: ""

		val voteResultText = wrap.selectFirst(".wpd-vote-result")?.textOrNull()
		val voteScore = voteResultText?.toIntOrNull() ?: 0
		val upActive = wrap.selectFirst(".wpd-vote-up.wpd-voted, .wpd-vote-up.wpd-active") != null
		val downActive = wrap.selectFirst(".wpd-vote-down.wpd-voted, .wpd-vote-down.wpd-active") != null
		val userVote = when {
			upActive -> 1
			downActive -> -1
			else -> 0
		}

		val canEdit = wrap.selectFirst(".wpd-comment-edit-button, .wpd_editable_comment") != null
		val canDelete = wrap.selectFirst(".wpd-comment-delete-button") != null
		val canReply = wrap.selectFirst(".wpd-reply-button") != null || true

		return SourceComment(
			id = commentId,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			authorRole = role,
			body = body,
			bodyHtml = bodyHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAtRaw ?: createdAtText),
			createdAtText = createdAtText,
			likesCount = voteScore.coerceAtLeast(0),
			dislikesCount = if (voteScore < 0) -voteScore else 0,
			userVote = userVote,
			parentId = parentId,
			isOwnComment = canEdit || canDelete,
			canReply = canReply,
			canLike = true,
			canDislike = true,
			canEdit = canEdit,
			canDelete = canDelete,
		)
	}

	suspend fun getComments(
		doc: Document?,
		pageUrl: String,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
		cursor: String? = null,
	): SourceCommentPage {
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val config = extractConfig(targetDoc)
			?: return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.WPDISCUZ,
			)

		if (page <= 1 && cursor == null && sort == SourceCommentSort.NEWEST) {
			return parseCommentsFromDocument(targetDoc, page = 1)
		}

		val form = buildMap {
			put("action", "wpdLoadMoreComments")
			put("postId", config.postId)
			put("offset", (page - 1).coerceAtLeast(0).toString())
			put(
				"orderBy",
				when (sort) {
					SourceCommentSort.TOP -> "by_vote"
					SourceCommentSort.OLDEST, SourceCommentSort.NEWEST -> "comment_date_gmt"
				},
			)
			put("order", if (sort == SourceCommentSort.OLDEST) "asc" else "desc")
			put("lastParentId", cursor ?: config.lastParentId ?: "0")
			put("isFirstLoad", if (page <= 1 && cursor == null) "1" else "0")
			config.nonce?.let {
				put("wpdiscuz_nonce", it)
				put("wpdNonce", it)
			}
		}

		val rawResp = postAjax(config.ajaxUrl, form, pageUrl)
		val json = rawResp.toJSONObjectOrNull()
		if (json != null) {
			val success = json.getBooleanOrDefault("success", true)
			val dataObj = json.optJSONObject("data")
			if (!success) {
				val errMsg = json.getStringOrNull("data") ?: dataObj?.getStringOrNull("message")
				checkAuthError(errMsg)
				throw ParseException(errMsg ?: "wpDiscuz load comments failed", config.ajaxUrl)
			}
			val htmlFragment = dataObj?.getStringOrNull("comment_list")
				?: dataObj?.getStringOrNull("message")
				?: json.getStringOrNull("data")
				?: ""
			val parsed = if (htmlFragment.isNotBlank()) {
				val fragDoc = Jsoup.parseBodyFragment(htmlFragment, pageUrl)
				nestCommentsByParentId(parseCommentsFromElement(fragDoc))
			} else {
				emptyList()
			}
			val showMore = dataObj?.getBooleanOrDefault("is_show_load_more", false) ?: false
			val nextParent = dataObj?.getStringOrNull("last_parent_id")
			return SourceCommentPage(
				comments = parsed,
				page = page,
				hasNextPage = showMore,
				totalCount = config.commentsCount,
				engine = SourceCommentEngine.WPDISCUZ,
				nextCursor = if (showMore) nextParent else null,
			)
		}

		val fragDoc = Jsoup.parseBodyFragment(rawResp, pageUrl)
		val comments = nestCommentsByParentId(parseCommentsFromElement(fragDoc))
		return SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = false,
			totalCount = config.commentsCount,
			engine = SourceCommentEngine.WPDISCUZ,
		)
	}

	suspend fun postComment(
		doc: Document?,
		pageUrl: String,
		text: String,
		parentComment: SourceComment? = null,
	): SourceComment {
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val config = extractConfig(targetDoc)
			?: throw ParseException("wpDiscuz configuration not found", pageUrl)
		val parentId = parentComment?.id ?: "0"

		val form = buildMap {
			put("action", "wpdAddComment")
			put("wc_comment", text)
			put("wc_parent_id", parentId)
			put("wc_post_id", config.postId)
			put("postId", config.postId)
			put("wpdiscuz_unique_id", "0_$parentId")
			config.nonce?.let {
				put("wpdiscuz_nonce", it)
				put("wpdNonce", it)
			}
		}

		val rawResp = postAjax(config.ajaxUrl, form, pageUrl)
		val json = rawResp.toJSONObjectOrNull()
		if (json != null) {
			val success = json.getBooleanOrDefault("success", false)
			val dataObj = json.optJSONObject("data")
			if (!success) {
				val errMsg = dataObj?.getStringOrNull("message") ?: json.getStringOrNull("data")
				checkAuthError(errMsg)
				throw ParseException(errMsg ?: "Failed to post wpDiscuz comment", config.ajaxUrl)
			}
			val htmlFragment = dataObj?.getStringOrNull("message") ?: ""
			val parsedComment = if (htmlFragment.isNotBlank()) {
				parseCommentsFromElement(Jsoup.parseBodyFragment(htmlFragment, pageUrl)).firstOrNull()
			} else {
				null
			}
			if (parsedComment != null) return parsedComment

			val newId = dataObj?.getStringOrNull("new_comment_id") ?: "wpd-${System.currentTimeMillis()}"
			val held = dataObj?.getBooleanOrDefault("held_for_review", false) == true
			return SourceComment(
				id = newId,
				authorName = "You",
				body = text,
				createdAtEpochMs = System.currentTimeMillis(),
				parentId = parentComment?.id,
				isOwnComment = true,
				canReply = true,
				rawMeta = if (held) mapOf("held_for_review" to "true") else emptyMap(),
			)
		}

		val fragParsed = parseCommentsFromElement(Jsoup.parseBodyFragment(rawResp, pageUrl)).firstOrNull()
		return fragParsed ?: SourceComment(
			id = "wpd-${System.currentTimeMillis()}",
			authorName = "You",
			body = text,
			createdAtEpochMs = System.currentTimeMillis(),
			parentId = parentComment?.id,
			isOwnComment = true,
			canReply = true,
		)
	}

	suspend fun toggleCommentLike(
		doc: Document?,
		pageUrl: String,
		comment: SourceComment,
		isLike: Boolean = true,
	): SourceComment {
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val config = extractConfig(targetDoc)
			?: throw ParseException("wpDiscuz configuration not found", pageUrl)

		val form = buildMap {
			put("action", "wpdVoteOnComment")
			put("commentId", comment.id)
			put("voteType", if (isLike) "1" else "-1")
			put("postId", config.postId)
			config.nonce?.let {
				put("wpdiscuz_nonce", it)
				put("wpdNonce", it)
			}
		}

		val rawResp = postAjax(config.ajaxUrl, form, pageUrl)
		val json = rawResp.toJSONObjectOrNull()
			?: throw ParseException("Invalid wpDiscuz vote response", config.ajaxUrl)
		val success = json.getBooleanOrDefault("success", false)
		val dataObj = json.optJSONObject("data")
		if (!success) {
			val errMsg = dataObj?.getStringOrNull("message") ?: json.getStringOrNull("data")
			checkAuthError(errMsg)
			throw ParseException(errMsg ?: "Failed to vote on wpDiscuz comment", config.ajaxUrl)
		}

		val votes = dataObj?.getIntOrDefault("votes", Int.MIN_VALUE)
			?.takeIf { it != Int.MIN_VALUE }
		val newVote = if (isLike) {
			if (comment.userVote == 1) 0 else 1
		} else {
			if (comment.userVote == -1) 0 else -1
		}
		val updatedLikes = if (votes != null) {
			votes.coerceAtLeast(0)
		} else {
			(comment.likesCount + if (newVote == 1) 1 else if (comment.userVote == 1) -1 else 0).coerceAtLeast(0)
		}
		val updatedDislikes = if (votes != null && votes < 0) -votes else comment.dislikesCount

		return comment.copy(
			likesCount = updatedLikes,
			dislikesCount = updatedDislikes,
			userVote = newVote,
		)
	}

	suspend fun editComment(
		doc: Document?,
		pageUrl: String,
		comment: SourceComment,
		newText: String,
	): SourceComment {
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val config = extractConfig(targetDoc)
			?: throw ParseException("wpDiscuz configuration not found", pageUrl)

		val form = buildMap {
			put("action", "wpdEditComment")
			put("commentId", comment.id)
			put("commentContent", newText)
			put("postId", config.postId)
			config.nonce?.let {
				put("wpdiscuz_nonce", it)
				put("wpdNonce", it)
			}
		}

		val rawResp = postAjax(config.ajaxUrl, form, pageUrl)
		val json = rawResp.toJSONObjectOrNull()
		if (json != null && !json.getBooleanOrDefault("success", false)) {
			val errMsg = json.optJSONObject("data")?.getStringOrNull("message") ?: json.getStringOrNull("data")
			checkAuthError(errMsg)
			throw ParseException(errMsg ?: "Failed to edit wpDiscuz comment", config.ajaxUrl)
		}
		return comment.copy(
			body = newText,
			bodyHtml = null,
			isEdited = true,
		)
	}

	suspend fun deleteComment(
		doc: Document?,
		pageUrl: String,
		comment: SourceComment,
	) {
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val config = extractConfig(targetDoc)
			?: throw ParseException("wpDiscuz configuration not found", pageUrl)

		val form = buildMap {
			put("action", "wpdDeleteComment")
			put("commentId", comment.id)
			put("postId", config.postId)
			config.nonce?.let {
				put("wpdiscuz_nonce", it)
				put("wpdNonce", it)
			}
		}

		val rawResp = postAjax(config.ajaxUrl, form, pageUrl)
		val json = rawResp.toJSONObjectOrNull()
		if (json != null && !json.getBooleanOrDefault("success", false)) {
			val errMsg = json.optJSONObject("data")?.getStringOrNull("message") ?: json.getStringOrNull("data")
			checkAuthError(errMsg)
			throw ParseException(errMsg ?: "Failed to delete wpDiscuz comment", config.ajaxUrl)
		}
	}

	private suspend fun postAjax(ajaxUrl: String, form: Map<String, String>, referer: String): String {
		val bodyBuilder = FormBody.Builder()
		for ((k, v) in form) {
			bodyBuilder.add(k, v)
		}
		val request = Request.Builder()
			.post(bodyBuilder.build())
			.url(ajaxUrl)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json, text/html, */*")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", referer)
			.tag(MangaSource::class.java, source)
			.build()

		return context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val text = response.body.string()
			if (!response.isSuccessful) {
				checkAuthError(text)
				throw ParseException("wpDiscuz AJAX HTTP ${response.code}", ajaxUrl)
			}
			text
		}
	}

	private fun checkAuthError(message: String?) {
		val lower = message?.lowercase(Locale.ROOT) ?: return
		if ("logged in" in lower || "log in" in lower || "login" in lower ||
			"msg_must_login" in lower || "wc_msg_required_login" in lower ||
			"giriş" in lower || "oturum" in lower
		) {
			throw AuthRequiredException(source)
		}
	}

	private companion object {
		val REGEX_WPD_NONCE = Regex("""["'](?:wpdiscuz_nonce|wpdNonce)["']\s*:\s*["']([^"']+)["']""")
	}
}
