package org.koitharu.kotatsu.parsers.site.community

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
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
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.util.Locale

internal class MadaraCommunityHelper(
	private val context: MangaLoaderContext,
	private val webClient: WebClient,
	private val source: MangaSource,
	private val domainProvider: () -> String,
	private val requestHeadersProvider: () -> Headers = { Headers.Builder().build() },
) {

	private val wpComments = WordPressCommentsHelper(
		context = context,
		webClient = webClient,
		source = source,
		domainProvider = domainProvider,
		requestHeadersProvider = requestHeadersProvider,
	)

	private val wpDiscuz = WpDiscuzCommunityHelper(
		context = context,
		webClient = webClient,
		source = source,
		domainProvider = domainProvider,
		requestHeadersProvider = requestHeadersProvider,
	)

	private val merlinComments = MerlinCommentsHelper(
		context = context,
		source = source,
		domainProvider = domainProvider,
		requestHeadersProvider = requestHeadersProvider,
	)

	val baseCapabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = true,
		maxRatingScale = 5,
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

	fun extractPostId(doc: Document): Long? {
		return wpComments.extractPostId(doc)
	}

	fun detectCommentEngine(doc: Document, pageUrl: String): SourceCommentEngine {
		if (merlinComments.isMerlinPresent(doc, pageUrl)) return SourceCommentEngine.NATIVE
		if (wpDiscuz.isWpDiscuzPresent(doc)) return SourceCommentEngine.WPDISCUZ
		if (GraphCommentDetector.detect(doc, pageUrl) != null) return SourceCommentEngine.GRAPHCOMMENT
		if (DisqusDetector.detect(doc, pageUrl) != null) return SourceCommentEngine.DISQUS
		if (HyvorTalkDetector.detect(doc, pageUrl) != null) return SourceCommentEngine.HYVOR
		if (CusdisDetector.detect(doc, pageUrl) != null) return SourceCommentEngine.CUSDIS
		if (doc.selectFirst("#comments, #commentform, .commentlist, ol.comment-list, input[name=comment_post_ID]") != null ||
			extractPostId(doc) != null
		) {
			return SourceCommentEngine.WORDPRESS
		}
		return SourceCommentEngine.NONE
	}

	suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return parseCommunityInfo(doc, pageUrl)
	}

	internal fun parseCommunityInfo(doc: Document, pageUrl: String = doc.location()): SourceMangaCommunityInfo {
		var rawRating = doc.selectFirst("span#averagerate, .post-total-rating .score, .post-rating span#averagerate")
			?.textOrNull()?.let(::parseFloatRating)
			?: doc.selectFirst("[itemprop=ratingValue]")?.let { el ->
				(el.attrOrNull("content") ?: el.textOrNull())?.let(::parseFloatRating)
			}

		var ratingCount = doc.selectFirst("#countrate, span.total_votes")
			?.textOrNull()?.let { parseCompactCount(it)?.toInt() }
			?: doc.selectFirst("[itemprop=ratingCount]")?.let { el ->
				(el.attrOrNull("content") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() }
			}

		var viewCount: Long? = null
		var followCount: Long? = null

		for (item in doc.select(".post-content_item")) {
			val heading = item.selectFirst(".summary-heading")?.text()?.trim()?.lowercase(Locale.ROOT) ?: continue
			val contentText = item.selectFirst(".summary-content")?.textOrNull() ?: continue
			when {
				"view" in heading || "görüntülenme" in heading || "okunma" in heading ||
					"visita" in heading || "vue" in heading -> {
					if (viewCount == null) {
						viewCount = parseCompactCount(contentText)
					}
				}
				"bookmark" in heading || "takip" in heading || "favori" in heading || "yer imi" in heading -> {
					if (followCount == null) {
						followCount = parseCompactCount(contentText)
					}
				}
				"rating" in heading || "puan" in heading || "değerlendirme" in heading -> {
					if (rawRating == null) {
						rawRating = parseFloatRating(contentText)
					}
					if (ratingCount == null) {
						val votesMatch = Regex("""(?:out of|/|üzerinden)\s*\d+(?:[.,]\d+)?\s*(?:\D+)?(\d+)""", RegexOption.IGNORE_CASE)
							.find(contentText)
						ratingCount = votesMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
					}
				}
			}
		}

		val userRating = doc.selectFirst("[data-user-rating], .post-rating .my-rating, .user-rating .score")?.let { el ->
			(el.attrOrNull("data-user-rating") ?: el.textOrNull())?.let { parseFloatRating(it)?.toInt() }
		}

		val commentCount = doc.selectFirst("#mcp-total-count, #wpdcom .wpd-thread-info[data-comments-count]")
			?.let { el -> (el.attrOrNull("data-comments-count") ?: el.textOrNull())?.let { parseCompactCount(it)?.toInt() } }
			?: doc.selectFirst(".wp-manga-comments-count, .comments-title")
				?.textOrNull()?.let { parseCompactCount(it)?.toInt() }

		val currentUsername = doc.selectFirst(
			".c-user_name, .user-name, #user-name, .wp-admin-bar-my-account .display-name, .c-modal_item .user-profile-name",
		)?.textOrNull()

		val postId = extractPostId(doc)
		val engine = detectCommentEngine(doc, pageUrl)

		return SourceMangaCommunityInfo(
			averageRating = rawRating?.let { (it / 5f).coerceIn(0f, 1f) },
			rawRating = rawRating,
			rawScale = 5,
			userRating = userRating,
			ratingCount = ratingCount,
			viewCount = viewCount,
			followCount = followCount,
			commentCount = commentCount,
			canCurrentUserRate = postId != null,
			canCurrentUserComment = engine != SourceCommentEngine.NONE,
			currentUsername = currentUsername,
		)
	}

	suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		val domain = domainProvider()
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		val postId = extractPostId(doc)
			?: throw ParseException("Cannot find Madara post ID for rating", pageUrl)
		val star = score.coerceIn(1, 5)
		val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php"

		val formBody = FormBody.Builder()
			.add("action", "wp-manga-save-rating")
			.add("star", star.toString())
			.add("postID", postId.toString())
			.build()

		val request = Request.Builder()
			.post(formBody)
			.url(ajaxUrl)
			.headers(requestHeadersProvider())
			.header("Accept", "application/json, */*")
			.header("X-Requested-With", "XMLHttpRequest")
			.header("Referer", pageUrl)
			.tag(MangaSource::class.java, source)
			.build()

		val rawResp = context.httpClient.newCall(request).await().use { response ->
			if (response.code == 401 || response.code == 403) {
				throw AuthRequiredException(source)
			}
			val text = response.body.string()
			if (!response.isSuccessful) {
				throw ParseException("Madara rating failed (HTTP ${response.code})", ajaxUrl)
			}
			text
		}

		val json = rawResp.toJSONObjectOrNull()
		val baseInfo = parseCommunityInfo(doc, pageUrl)
		if (json != null) {
			if (json.has("success") && !json.getBooleanOrDefault("success", true)) {
				val errMsg = json.optJSONObject("data")?.getStringOrNull("message")
					?: json.getStringOrNull("data")
				val lower = errMsg?.lowercase(Locale.ROOT).orEmpty()
				if ("login" in lower || "logged in" in lower || "giriş" in lower) {
					throw AuthRequiredException(source)
				}
				throw ParseException(errMsg ?: "Madara rating submission failed", ajaxUrl)
			}

			val dataObj = json.optJSONObject("data") ?: json
			var updatedRating = (dataObj.opt("rating") ?: dataObj.opt("average"))?.toString()?.let(::parseFloatRating)
			var updatedVotes = (dataObj.opt("votes") ?: dataObj.opt("total_votes") ?: dataObj.opt("count"))
				?.toString()?.let { parseCompactCount(it)?.toInt() }

			val htmlFallback = json.getStringOrNull("data")
			if ((updatedRating == null || updatedVotes == null) && htmlFallback != null && "<" in htmlFallback) {
				val frag = Jsoup.parseBodyFragment(htmlFallback, pageUrl)
				if (updatedRating == null) {
					updatedRating = frag.selectFirst("span#averagerate, .score")?.textOrNull()?.let(::parseFloatRating)
				}
				if (updatedVotes == null) {
					updatedVotes = frag.selectFirst("#countrate, .total_votes")?.textOrNull()
						?.let { parseCompactCount(it)?.toInt() }
				}
			}

			val finalRating = updatedRating ?: baseInfo.rawRating
			return baseInfo.copy(
				rawRating = finalRating,
				averageRating = finalRating?.let { (it / 5f).coerceIn(0f, 1f) },
				userRating = star,
				ratingCount = updatedVotes ?: baseInfo.ratingCount,
			)
		}

		return baseInfo.copy(userRating = star)
	}

	suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter? = null,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
		cursor: String? = null,
	): SourceCommentPage {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return when (val engine = detectCommentEngine(doc, pageUrl)) {
			SourceCommentEngine.NATIVE -> merlinComments.getComments(
				doc = doc,
				pageUrl = pageUrl,
				page = page,
				sort = sort,
			)
			SourceCommentEngine.WPDISCUZ -> wpDiscuz.getComments(
				doc = doc,
				pageUrl = pageUrl,
				page = page,
				sort = sort,
				cursor = cursor,
			)
			SourceCommentEngine.GRAPHCOMMENT,
			SourceCommentEngine.DISQUS,
			SourceCommentEngine.HYVOR,
			SourceCommentEngine.CUSDIS -> {
				val widget = resolveWidgetContextFromDoc(doc, pageUrl, engine)
				SourceCommentPage(
					comments = emptyList(),
					page = page,
					hasNextPage = false,
					widgetContext = widget,
					engine = engine,
				)
			}
			SourceCommentEngine.WORDPRESS -> wpComments.getComments(
				doc = doc,
				pageUrl = pageUrl,
				postId = extractPostId(doc),
				page = page,
				sort = sort,
			)
			else -> SourceCommentPage(
				comments = emptyList(),
				page = page,
				hasNextPage = false,
				engine = SourceCommentEngine.NONE,
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
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return when (detectCommentEngine(doc, pageUrl)) {
			SourceCommentEngine.NATIVE -> merlinComments.postComment(
				doc = doc,
				pageUrl = pageUrl,
				text = text,
				parentComment = parentComment,
				isSpoiler = isSpoiler,
			)
			SourceCommentEngine.WPDISCUZ -> wpDiscuz.postComment(
				doc = doc,
				pageUrl = pageUrl,
				text = text,
				parentComment = parentComment,
			)
			SourceCommentEngine.WORDPRESS -> wpComments.postComment(
				doc = doc,
				pageUrl = pageUrl,
				postId = extractPostId(doc),
				text = text,
				parentComment = parentComment,
			)
			else -> throw UnsupportedOperationException("Native comment posting is not supported for this source")
		}
	}

	suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter? = null,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return when (detectCommentEngine(doc, pageUrl)) {
			SourceCommentEngine.NATIVE -> merlinComments.editComment(doc, pageUrl, comment, newText)
			SourceCommentEngine.WPDISCUZ -> wpDiscuz.editComment(doc, pageUrl, comment, newText)
			else -> throw UnsupportedOperationException("Editing comments is not supported for this engine")
		}
	}

	suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	) {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		when (detectCommentEngine(doc, pageUrl)) {
			SourceCommentEngine.NATIVE -> merlinComments.deleteComment(doc, pageUrl, comment)
			SourceCommentEngine.WPDISCUZ -> wpDiscuz.deleteComment(doc, pageUrl, comment)
			else -> throw UnsupportedOperationException("Deleting comments is not supported for this engine")
		}
	}

	suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean = true,
		chapter: MangaChapter? = null,
	): SourceComment {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return when (detectCommentEngine(doc, pageUrl)) {
			SourceCommentEngine.NATIVE -> merlinComments.toggleCommentLike(doc, pageUrl, comment, isLike)
			SourceCommentEngine.WPDISCUZ -> wpDiscuz.toggleCommentLike(doc, pageUrl, comment, isLike)
			else -> throw UnsupportedOperationException("Comment voting is not supported for this engine")
		}
	}

	suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceWidgetContext? {
		val pageUrl = resolvePageUrl(manga, chapter)
		val doc = webClient.httpGet(pageUrl).parseHtml()
		return WidgetDetectors.detect(doc, pageUrl)
	}

	private fun resolveWidgetContextFromDoc(
		doc: Document,
		pageUrl: String,
		engine: SourceCommentEngine,
	): SourceWidgetContext? {
		return when (engine) {
			SourceCommentEngine.GRAPHCOMMENT -> GraphCommentDetector.detect(doc, pageUrl)
			SourceCommentEngine.DISQUS -> DisqusDetector.detect(doc, pageUrl)
			SourceCommentEngine.HYVOR -> HyvorTalkDetector.detect(doc, pageUrl)
			SourceCommentEngine.CUSDIS -> CusdisDetector.detect(doc, pageUrl)
			else -> WidgetDetectors.detect(doc, pageUrl)
		}
	}

	private fun resolvePageUrl(manga: Manga, chapter: MangaChapter?): String {
		val domain = domainProvider()
		val rawUrl = chapter?.url?.takeIf { it.isNotBlank() } ?: manga.url
		return rawUrl.toAbsoluteUrl(domain)
	}
}
