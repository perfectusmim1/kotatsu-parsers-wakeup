package org.koitharu.kotatsu.parsers.site.tr

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.WordPressCommentsHelper
import org.koitharu.kotatsu.parsers.site.community.WpDiscuzCommunityHelper
import org.koitharu.kotatsu.parsers.site.community.extractBalancedJsonObjectAfter
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ELECEEDTURKIYE", "Eleceed Türkiye", "tr")
internal class EleceedTurkiye(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.ELECEEDTURKIYE),
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain(
		"eleceedturkiye.com",
		"www.eleceedturkiye.com",
	)

	private val wpComments by lazy {
		WordPressCommentsHelper(
			context = context,
			webClient = webClient,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
		)
	}

	private val wpDiscuz by lazy {
		WpDiscuzCommunityHelper(
			context = context,
			webClient = webClient,
			source = source,
			domainProvider = ::domain,
			requestHeadersProvider = ::getRequestHeaders,
		)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 10,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = true,
		supportsCommentEditing = true,
		supportsCommentDeleting = true,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.WORDPRESS,
		supportsWidgetFallback = true,
		requiresAuthForRating = false,
		requiresAuthForComment = false,
		supportedSorts = setOf(
			SourceCommentSort.NEWEST,
			SourceCommentSort.OLDEST,
			SourceCommentSort.TOP,
		),
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val raw = webClient.httpGet("https://$domain").parseHtml()
		val coverUrl = raw.select("div.thumb img").attr("src")
		return listOf(
			Manga(
				id = generateUid(mangaId),
				url = "https://$domain/",
				publicUrl = "https://$domain/",
				coverUrl = coverUrl,
				title = "Eleceed",
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOf("Son Jae Ho", "ZHENA"),
				state = MangaState.ONGOING,
				source = source,
				contentRating = ContentRating.ADULT,
			),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url).parseHtml()
		return manga.copy(
			description = doc.selectFirst("div.entry-content")?.html(),
			tags = setOf(
				MangaTag("Aksiyon", "", source),
				MangaTag("Drama", "", source),
				MangaTag("Komedi", "", source),
				MangaTag("Süper Güçler", "", source),
			),
			chapters = doc.select("div.eph-num").mapChapters(reversed = true) { _, div ->
				val href = div.selectFirstOrThrow("a").attr("href")
				val title = div.selectFirst("span.chapternum")?.text() ?: "Not found"
				MangaChapter(
					id = generateUid(href),
					title = title,
					number = title?.let { Regex("\\d+").find(it)?.value?.toFloatOrNull() } ?: 0f,
					url = href,
					uploadDate = dateFormat.parseSafe(div.selectFirst("span.chapterdate")?.text()),
					scanlator = null,
					branch = null,
					source = source,
					volume = 0,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url).parseHtml()
		val scriptData = doc.select("script")
			.firstOrNull { it.data().contains("ts_reader.run(") }
			?.data()
			?.substringAfter("ts_reader.run(")
			?.substringBeforeLast(");")
			?: error("Script data not found")

		val json = JSONObject(scriptData)
		val sources = json.getJSONArray("sources")
		if (sources.length() == 0) error("No sources found")

		val firstSource = sources.getJSONObject(0)
		val images = firstSource.getJSONArray("images")

		return List(images.length()) { index ->
			val imageUrl = images.getString(index)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl.toAbsoluteUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	private val mangaId = "eleceed"
	private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("tr"))

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		val rawRating = doc.selectFirst(".numscore, .rt .rating .num, [itemprop=ratingValue]")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating) }
		val scale = if (rawRating != null && rawRating > 5f) 10 else if (doc.selectFirst(".numscore") != null) 10 else 5
		val ratingCount = doc.selectFirst("[itemprop=ratingCount], .rating .votes")
			?.let { el -> (el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }
		val followCount = doc.selectFirst(".bmc")?.textOrNull()?.let(::parseCompactCount)
		val commentCount = doc.selectFirst("#mcp-total-count, #wpdcom .wpd-thread-info[data-comments-count], .comments-title")
			?.let { el -> (el.attrOrNull("data-comments-count") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }

		val merlinConfig = extractMerlinConfig(doc)
		val hasComments = merlinConfig != null ||
			wpDiscuz.isWpDiscuzPresent(doc) ||
			WidgetDetectors.detect(doc, pageUrl) != null ||
			doc.selectFirst("#comments, #commentform, .commentlist, ol.comment-list, input[name=comment_post_ID]") != null ||
			wpComments.extractPostId(doc) != null

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / scale.toFloat()).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = scale,
			ratingCount = ratingCount,
			followCount = followCount,
			commentCount = if (chapter == null) commentCount else null,
			chapterCommentCount = if (chapter != null) commentCount else null,
			canCurrentUserComment = hasComments,
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
		val doc = webClient.httpGet(pageUrl).parseHtml()

		extractMerlinConfig(doc)?.let { merlin ->
			return loadMerlinComments(merlin, pageUrl, page, sort)
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.getComments(doc, pageUrl, page, sort, cursor)
		}
		WidgetDetectors.detect(doc, pageUrl)?.let { widget ->
			return SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				widgetContext = widget,
				engine = widget.engine,
			)
		}
		if (doc.selectFirst("#comments, #commentform, .commentlist, ol.comment-list, input[name=comment_post_ID]") != null ||
			wpComments.extractPostId(doc) != null
		) {
			return wpComments.getComments(doc, pageUrl, page = page, sort = sort)
		}

		val fallback = buildCustomWidget(doc, pageUrl)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = fallback,
			engine = SourceCommentEngine.CUSTOM_WIDGET,
		)
	}

	override suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter?,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		extractMerlinConfig(doc)?.let { merlin ->
			return postMerlinComment(merlin, pageUrl, text, parentComment, isSpoiler)
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.postComment(doc, pageUrl, text, parentComment)
		}
		return wpComments.postComment(doc, pageUrl, text = text, parentComment = parentComment)
	}

	override suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		extractMerlinConfig(doc)?.let { merlin ->
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
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.editComment(doc, pageUrl, comment, newText)
		}
		throw UnsupportedOperationException("Editing comments is not supported for this engine")
	}

	override suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		extractMerlinConfig(doc)?.let { merlin ->
			val form = FormBody.Builder()
				.add("action", "mcp_delete_comment")
				.add("comment_id", comment.id)
				.apply { merlin.nonce?.let { add("nonce", it) } }
				.build()
			postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
			return
		}
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			wpDiscuz.deleteComment(doc, pageUrl, comment)
			return
		}
		throw UnsupportedOperationException("Deleting comments is not supported for this engine")
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()

		extractMerlinConfig(doc)?.let { merlin ->
			val form = FormBody.Builder()
				.add("action", "mcp_vote")
				.add("comment_id", comment.id)
				.add("vote_type", if (isLike) "like" else "dislike")
				.apply { merlin.nonce?.let { add("nonce", it) } }
				.build()
			val json = postMerlinAjax(merlin.ajaxUrl, form, pageUrl)
			val dataObj = json.optJSONObject("data")
			val likes = dataObj?.opt("likes_count")?.toString()?.toIntOrNull()
				?: (comment.likesCount + if (isLike && comment.userVote != 1) 1 else if (isLike && comment.userVote == 1) -1 else 0).coerceAtLeast(0)
			val dislikes = dataObj?.opt("dislikes_count")?.toString()?.toIntOrNull()
				?: (comment.dislikesCount + if (!isLike && comment.userVote != -1) 1 else if (!isLike && comment.userVote == -1) -1 else 0).coerceAtLeast(0)
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
		if (wpDiscuz.isWpDiscuzPresent(doc)) {
			return wpDiscuz.toggleCommentLike(doc, pageUrl, comment, isLike)
		}
		throw UnsupportedOperationException("Comment voting is not supported for this engine")
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext? {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return WidgetDetectors.detect(doc, pageUrl) ?: buildCustomWidget(doc, pageUrl)
	}

	private data class MerlinConfig(
		val postId: String,
		val ajaxUrl: String,
		val nonce: String?,
		val currentUserId: String?,
		val isLoggedIn: Boolean,
	)

	private fun extractMerlinConfig(doc: Document): MerlinConfig? {
		val section = doc.selectFirst("#merlin-comments-section[data-post-id]")
		val scriptsText = doc.select("script").joinToString("\n") { it.data() }
		val obj = extractBalancedJsonObjectAfter(scriptsText, "MerlinComments =")?.toJSONObjectOrNull()
			?: extractBalancedJsonObjectAfter(scriptsText, "MerlinComments=")?.toJSONObjectOrNull()
		if (section == null && obj == null) return null

		val postId = section?.attrOrNull("data-post-id")
			?: obj?.getStringOrNull("post_id")?.takeIf { it.isNotBlank() && it != "0" }
			?: return null
		val resolvedOrigin = doc.location().toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
			?: "https://$domain"
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

	private suspend fun loadMerlinComments(
		merlin: MerlinConfig,
		pageUrl: String,
		page: Int,
		sort: SourceCommentSort,
	): SourceCommentPage {
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
		val comments = parseMerlinCommentsArray(commentsArray, merlin, userVotes)
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

	private suspend fun postMerlinComment(
		merlin: MerlinConfig,
		pageUrl: String,
		text: String,
		parentComment: SourceComment?,
		isSpoiler: Boolean,
	): SourceComment {
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
			return parseMerlinComment(commentObj, merlin, null)
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

	private suspend fun postMerlinAjax(ajaxUrl: String, form: FormBody, referer: String): JSONObject {
		val request = Request.Builder()
			.post(form)
			.url(ajaxUrl)
			.headers(getRequestHeaders())
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
			val lower = msg.lowercase(sourceLocale)
			if ("giriş" in lower || "login" in lower || "oturum" in lower) {
				throw AuthRequiredException(source)
			}
			throw ParseException(msg, ajaxUrl)
		}
		return json
	}

	private fun parseMerlinCommentsArray(
		array: JSONArray,
		merlin: MerlinConfig,
		userVotes: JSONObject?,
	): List<SourceComment> {
		val list = ArrayList<SourceComment>(array.length())
		for (i in 0 until array.length()) {
			val jo = array.optJSONObject(i) ?: continue
			list.add(parseMerlinComment(jo, merlin, userVotes))
		}
		return list
	}

	private fun parseMerlinComment(
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
			parseMerlinCommentsArray(repliesArray, merlin, userVotes)
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

	private fun buildCustomWidget(doc: Document?, pageUrl: String): SourceWidgetContext = SourceWidgetContext(
		engine = SourceCommentEngine.CUSTOM_WIDGET,
		pageUrl = pageUrl,
		widgetUrl = pageUrl,
		title = doc?.title()?.nullIfEmpty(),
		baseUrl = pageUrl,
	)

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}
}
