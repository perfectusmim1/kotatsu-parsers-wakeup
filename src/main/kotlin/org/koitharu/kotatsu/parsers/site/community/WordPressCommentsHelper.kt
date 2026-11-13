package org.koitharu.kotatsu.parsers.site.community

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getLongOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.util.Locale

internal class WordPressCommentsHelper(
	private val context: MangaLoaderContext,
	private val webClient: WebClient,
	private val source: MangaSource,
	private val domainProvider: () -> String,
	private val requestHeadersProvider: () -> Headers = { Headers.Builder().build() },
) {

	fun extractPostId(doc: Document): Long? {
		doc.selectFirst("input[name=comment_post_ID]")?.attrOrNull("value")?.toLongOrNull()?.let { return it }
		doc.selectFirst(".rating-post-id")?.let { el ->
			(el.attrOrNull("value") ?: el.attrOrNull("data-id") ?: el.textOrNull())?.toLongOrNull()?.let { return it }
		}
		doc.selectFirst("#manga-chapters-holder[data-id]")?.attrOrNull("data-id")?.toLongOrNull()?.let { return it }
		val bodyClass = doc.body().className()
		REGEX_POST_ID_CLASS.find(bodyClass)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
		doc.selectFirst("link[rel=shortlink]")?.attrOrNull("href")?.toHttpUrlOrNull()?.queryParameter("p")?.toLongOrNull()
			?.let { return it }
		doc.selectFirst("article[id^=post-]")?.id()?.removePrefix("post-")?.toLongOrNull()?.let { return it }
		val scripts = doc.select("script").joinToString("\n") { it.data() }
		REGEX_POST_ID_JS.find(scripts)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
		return null
	}

	fun extractRestNonce(doc: Document): String? {
		doc.selectFirst("meta[name=wp-rest-nonce]")?.attrOrNull("content")?.let { return it }
		doc.selectFirst("input[name=_wpnonce]")?.attrOrNull("value")?.let { return it }
		val scripts = doc.select("script").joinToString("\n") { it.data() }
		extractBalancedJsonObjectAfter(scripts, "wpApiSettings =")
			?.toJSONObjectOrNull()
			?.getStringOrNull("nonce")
			?.let { return it }
		return REGEX_WP_REST_NONCE.find(scripts)?.groupValues?.getOrNull(1)?.nullIfEmpty()
	}

	suspend fun getComments(
		doc: Document?,
		pageUrl: String,
		postId: Long? = doc?.let(::extractPostId),
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
	): SourceCommentPage {
		val domain = domainProvider()
		val resolvedPostId = postId ?: doc?.let(::extractPostId)
		val restNonce = doc?.let(::extractRestNonce)

		if (resolvedPostId != null) {
			val restPage = tryLoadViaWpRest(
				domain = domain,
				postId = resolvedPostId,
				page = page,
				sort = sort,
				restNonce = restNonce,
			)
			if (restPage != null) {
				return restPage
			}
		}

		val targetDoc = doc ?: runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
		if (targetDoc != null) {
			return parseHtmlComments(targetDoc, page)
		}

		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			engine = SourceCommentEngine.WORDPRESS,
		)
	}

	private suspend fun tryLoadViaWpRest(
		domain: String,
		postId: Long,
		page: Int,
		sort: SourceCommentSort,
		restNonce: String?,
	): SourceCommentPage? {
		val order = if (sort == SourceCommentSort.OLDEST) "asc" else "desc"
		val url = "https://$domain/wp-json/wp/v2/comments"
			.toHttpUrlOrNull()
			?.newBuilder()
			?.addQueryParameter("post", postId.toString())
			?.addQueryParameter("page", page.coerceAtLeast(1).toString())
			?.addQueryParameter("per_page", PER_PAGE.toString())
			?.addQueryParameter("order", order)
			?.build()
			?: return null

		val requestBuilder = Request.Builder()
			.get()
			.url(url)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json")
			.tag(MangaSource::class.java, source)
		if (restNonce != null) {
			requestBuilder.header("X-WP-Nonce", restNonce)
		}

		return runCatching {
			context.httpClient.newCall(requestBuilder.build()).await().use { response ->
				if (!response.isSuccessful) return@use null
				val bodyStr = response.body.string()
				val array = bodyStr.toJSONArrayOrNull() ?: return@use null
				val totalCount = response.header("X-WP-Total")?.toIntOrNull()
				val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull()
				val flat = parseRestCommentsArray(array, domain)
				val nested = nestCommentsByParentId(flat)
				val hasNext = when {
					totalPages != null -> page < totalPages
					totalCount != null -> page * PER_PAGE < totalCount
					else -> flat.size >= PER_PAGE
				}
				SourceCommentPage(
					comments = nested,
					page = page,
					hasNextPage = hasNext,
					totalCount = totalCount,
					engine = SourceCommentEngine.WORDPRESS,
				)
			}
		}.getOrNull()
	}

	internal fun parseRestCommentsArray(array: JSONArray, domain: String): List<SourceComment> {
		return array.mapJSON { jo -> parseRestComment(jo, domain) }
	}

	internal fun parseRestComment(jo: JSONObject, domain: String): SourceComment {
		val id = jo.getStringOrNull("id") ?: jo.getLongOrDefault("id", 0L).toString()
		val parentId = jo.getLongOrDefault("parent", 0L).takeIf { it > 0L }?.toString()
		val authorName = jo.getStringOrNull("author_name") ?: "Guest"
		val avatarUrl = jo.optJSONObject("author_avatar_urls")?.let { avatars ->
			avatars.getStringOrNull("96")
				?: avatars.getStringOrNull("48")
				?: avatars.getStringOrNull("24")
		}?.toAbsoluteUrl(domain)
		val contentHtml = jo.optJSONObject("content")?.getStringOrNull("rendered")
		val contentText = contentHtml?.let { Jsoup.parseBodyFragment(it).text().nullIfEmpty() }
			?: jo.getStringOrNull("content")
			?: ""
		val dateGmt = jo.getStringOrNull("date_gmt") ?: jo.getStringOrNull("date")
		val link = jo.getStringOrNull("link")
		val status = jo.getStringOrNull("status")
		val meta = buildMap {
			if (link != null) put("link", link)
			if (status != null) put("status", status)
		}
		return SourceComment(
			id = id,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			body = contentText,
			bodyHtml = contentHtml,
			createdAtEpochMs = parseIsoTimestamp(dateGmt),
			createdAtText = dateGmt,
			parentId = parentId,
			canReply = true,
			rawMeta = meta,
		)
	}

	internal fun parseHtmlComments(doc: Document, page: Int = 1): SourceCommentPage {
		val listRoot = doc.selectFirst(".commentlist, ol.comment-list, ul.comment-list")
		if (listRoot == null) {
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.WORDPRESS,
			)
		}

		val topLevelLis = listRoot.select("> li.comment, > li[id^=comment-], > li[id^=li-comment-]")
			.ifEmpty { listRoot.select("li.comment, li[id^=comment-]") }

		val comments = if (topLevelLis.any { it.parent() == listRoot }) {
			topLevelLis.mapNotNull { parseHtmlCommentNode(it) }
		} else {
			// Flat fallback without duplicating children
			val seenIds = HashSet<String>()
			topLevelLis.mapNotNull { li ->
				val parsed = parseHtmlCommentNode(li) ?: return@mapNotNull null
				if (seenIds.add(parsed.id)) parsed else null
			}
		}

		val hasNext = doc.selectFirst(
			".comment-navigation .nav-previous a, .comments-pagination a.next, a.next.page-numbers",
		) != null

		return SourceCommentPage(
			comments = comments,
			page = page,
			hasNextPage = hasNext,
			engine = SourceCommentEngine.WORDPRESS,
		)
	}

	private fun parseHtmlCommentNode(li: Element): SourceComment? {
		val bodyEl = li.selectFirst("> article.comment-body, > div.comment-body, > .comment-body") ?: li
		val rawId = li.id().removePrefix("li-comment-").removePrefix("comment-").nullIfEmpty()
			?: bodyEl.id().removePrefix("div-comment-").removePrefix("comment-").nullIfEmpty()
			?: return null

		val authorName = bodyEl.selectFirst(
			".comment-author .fn, .comment-author b, .comment-author cite, .comment-author a, .comment-author",
		)?.textOrNull() ?: "Guest"
		val avatarUrl = bodyEl.selectFirst(".comment-author img")?.src()
		val timeEl = bodyEl.selectFirst("time")
		val createdAtText = timeEl?.textOrNull()
			?: bodyEl.selectFirst(".comment-metadata a, .comment-meta a")?.textOrNull()
		val createdAtIso = timeEl?.attrOrNull("datetime")
		val contentEl = bodyEl.selectFirst(".comment-content, .comment-text")
		val bodyHtml = contentEl?.html()?.nullIfEmpty()
		val bodyText = contentEl?.textOrNull()
			?: bodyEl.select("> p").joinToString("\n") { it.text() }.trim().nullIfEmpty()
			?: return null

		val childLis = li.select("> ol.children > li, > ul.children > li")
		val replies = childLis.mapNotNull { child ->
			parseHtmlCommentNode(child)?.copy(parentId = rawId)
		}

		return SourceComment(
			id = rawId,
			authorName = authorName,
			authorAvatarUrl = avatarUrl,
			body = bodyText,
			bodyHtml = bodyHtml,
			createdAtEpochMs = parseIsoTimestamp(createdAtIso ?: createdAtText),
			createdAtText = createdAtText ?: createdAtIso,
			repliesCount = replies.size,
			replies = replies,
			canReply = true,
		)
	}

	suspend fun postComment(
		doc: Document?,
		pageUrl: String,
		postId: Long? = doc?.let(::extractPostId),
		text: String,
		parentComment: SourceComment? = null,
	): SourceComment {
		val domain = domainProvider()
		val targetDoc = doc ?: webClient.httpGet(pageUrl).parseHtml()
		val resolvedPostId = postId ?: extractPostId(targetDoc)
			?: throw ParseException("Cannot determine WordPress post ID for comments", pageUrl)
		val restNonce = extractRestNonce(targetDoc)

		if (restNonce != null) {
			val restUrl = "https://$domain/wp-json/wp/v2/comments"
			val jsonPayload = JSONObject().apply {
				put("post", resolvedPostId)
				put("content", text)
				if (parentComment != null) {
					put("parent", parentComment.id.toLongOrNull() ?: 0L)
				}
			}
			val request = Request.Builder()
				.post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
				.url(restUrl)
				.headers(requestHeadersProvider())
				.header("Accept", "application/json")
				.header("X-WP-Nonce", restNonce)
				.header("Referer", pageUrl)
				.tag(MangaSource::class.java, source)
				.build()

			context.httpClient.newCall(request).await().use { response ->
				val rawBody = response.body.string()
				if (response.code == 401 || response.code == 403) {
					val errCode = rawBody.toJSONObjectOrNull()?.getStringOrNull("code")
					if (errCode == "rest_comment_login_required" || errCode == "rest_cannot_create" || response.code == 401) {
						throw AuthRequiredException(source)
					}
					throw AuthRequiredException(source)
				}
				if (response.isSuccessful) {
					val obj = rawBody.toJSONObjectOrNull()
					if (obj != null) {
						return parseRestComment(obj, domain)
					}
				} else if (response.code in 400..499 && response.code != 404) {
					val errMsg = rawBody.toJSONObjectOrNull()?.getStringOrNull("message")
					throw ParseException(errMsg ?: "WordPress comment post failed (${response.code})", restUrl)
				}
			}
		}

		// Standard wp-comments-post.php form submission
		val form = targetDoc.selectFirst("form#commentform, form[action*=wp-comments-post.php]")
		val formAction = form?.attrOrNull("action")?.toAbsoluteUrl(domain)
			?: "https://$domain/wp-comments-post.php"

		val formBuilder = FormBody.Builder()
		val addedKeys = HashSet<String>()
		if (form != null) {
			for (input in form.select("input[type=hidden][name]")) {
				val name = input.attrOrNull("name") ?: continue
				val value = when (name) {
					"comment_post_ID" -> resolvedPostId.toString()
					"comment_parent" -> parentComment?.id ?: "0"
					else -> input.attr("value")
				}
				formBuilder.add(name, value)
				addedKeys.add(name)
			}
		}
		if ("comment_post_ID" !in addedKeys) {
			formBuilder.add("comment_post_ID", resolvedPostId.toString())
		}
		if ("comment_parent" !in addedKeys) {
			formBuilder.add("comment_parent", parentComment?.id ?: "0")
		}
		formBuilder.add("comment", text)

		val request = Request.Builder()
			.post(formBuilder.build())
			.url(formAction)
			.headers(requestHeadersProvider())
			.header("Referer", pageUrl)
			.tag(MangaSource::class.java, source)
			.build()

		return context.httpClient.newCall(request).await().use { response ->
			val finalUrl = response.request.url.toString()
			val html = response.body.string()
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val respDoc = Jsoup.parse(html, finalUrl)
			val dieMsg = respDoc.selectFirst(".wp-die-message, body#error-page")?.textOrNull()
			if (dieMsg != null) {
				val lower = dieMsg.lowercase(Locale.ROOT)
				if ("logged in" in lower || "log in" in lower || "giriş" in lower || "oturum" in lower) {
					throw AuthRequiredException(source)
				}
				throw ParseException(dieMsg, formAction)
			}
			if (!response.isSuccessful && response.code !in 300..399) {
				throw ParseException("WordPress comment post failed (${response.code})", formAction)
			}

			val anchorId = REGEX_COMMENT_ANCHOR.find(finalUrl)?.groupValues?.getOrNull(1)
			if (anchorId != null) {
				val node = respDoc.selectFirst("#comment-$anchorId, #li-comment-$anchorId")
				if (node != null) {
					parseHtmlCommentNode(node)?.let { return@use it }
				}
			}

			SourceComment(
				id = anchorId ?: "wp-${System.currentTimeMillis()}",
				authorName = "You",
				body = text,
				createdAtEpochMs = System.currentTimeMillis(),
				parentId = parentComment?.id,
				isOwnComment = true,
				canReply = true,
			)
		}
	}

	private companion object {
		const val PER_PAGE = 20
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val REGEX_POST_ID_CLASS = Regex("""\bpostid-(\d+)\b""")
		val REGEX_POST_ID_JS = Regex("""["']?post_?[iI]d["']?\s*[:=]\s*["']?(\d+)""")
		val REGEX_WP_REST_NONCE = Regex("""["']nonce["']\s*:\s*["']([a-zA-Z0-9]+)["']""")
		val REGEX_COMMENT_ANCHOR = Regex("""#comment-(\d+)""")
	}
}
