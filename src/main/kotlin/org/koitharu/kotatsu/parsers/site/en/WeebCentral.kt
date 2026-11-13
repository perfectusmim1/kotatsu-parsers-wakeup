package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.ContentRating.SAFE
import org.koitharu.kotatsu.parsers.model.ContentRating.SUGGESTIVE
import org.koitharu.kotatsu.parsers.model.ContentType.COMICS
import org.koitharu.kotatsu.parsers.model.ContentType.MANGA
import org.koitharu.kotatsu.parsers.model.ContentType.MANHUA
import org.koitharu.kotatsu.parsers.model.ContentType.MANHWA
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState.ABANDONED
import org.koitharu.kotatsu.parsers.model.MangaState.FINISHED
import org.koitharu.kotatsu.parsers.model.MangaState.ONGOING
import org.koitharu.kotatsu.parsers.model.MangaState.PAUSED
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.SortOrder.ADDED
import org.koitharu.kotatsu.parsers.model.SortOrder.ADDED_ASC
import org.koitharu.kotatsu.parsers.model.SortOrder.ALPHABETICAL
import org.koitharu.kotatsu.parsers.model.SortOrder.ALPHABETICAL_DESC
import org.koitharu.kotatsu.parsers.model.SortOrder.POPULARITY
import org.koitharu.kotatsu.parsers.model.SortOrder.POPULARITY_ASC
import org.koitharu.kotatsu.parsers.model.SortOrder.RATING
import org.koitharu.kotatsu.parsers.model.SortOrder.RATING_ASC
import org.koitharu.kotatsu.parsers.model.SortOrder.RELEVANCE
import org.koitharu.kotatsu.parsers.model.SortOrder.UPDATED
import org.koitharu.kotatsu.parsers.model.SortOrder.UPDATED_ASC
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseCompactCount
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("WEEBCENTRAL", "Weeb Central", "en")
internal class WeebCentral(context: MangaLoaderContext) : AbstractMangaParser(context, MangaParserSource.WEEBCENTRAL),
	MangaParserAuthProvider,
	MangaSourceCommunityProvider {

	override val configKeyDomain = ConfigKey.Domain("weebcentral.com")

	override val authUrl: String
		get() = "https://$domain"

	override suspend fun isAuthorized(): Boolean =
		context.cookieJar.getCookies(domain).any { it.name == "access_token" && it.value.isNotBlank() }

	override suspend fun getUsername(): String {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		return webClient.httpGet("https://$domain/users/me/profiles")
			.parseHtml()
			.selectFirst("div:has(section > .avatar) .text-4xl")
			?.textOrNull()
			?: throw AuthRequiredException(source)
	}

	override suspend fun logout() {
		runCatching {
			webClient.httpPost("https://$domain/logout", emptyMap()).close()
		}
		context.cookieJar.insertCookies(domain, "access_token=; Max-Age=0; Path=/")
		context.clearAuthValues(source)
	}

	override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
		supportsStats = true,
		supportsRating = false,
		maxRatingScale = 5,
		supportsNativeComments = true,
		supportsCommentPosting = true,
		supportsReplies = true,
		supportsCommentLikes = true,
		supportsCommentDislikes = false,
		supportsCommentEditing = false,
		supportsCommentDeleting = false,
		supportsChapterComments = true,
		commentEngine = SourceCommentEngine.NATIVE,
		supportsWidgetFallback = true,
		requiresAuthForRating = true,
		requiresAuthForComment = true,
		supportedSorts = setOf(SourceCommentSort.NEWEST),
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		RELEVANCE,
		ALPHABETICAL,
		ALPHABETICAL_DESC,
		POPULARITY,
		POPULARITY_ASC,
		RATING,
		RATING_ASC,
		ADDED,
		ADDED_ASC,
		UPDATED,
		UPDATED_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities =
		MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val document = webClient.httpGet("https://$domain/search")
			.parseHtml()

		val tags = document.select("section[x-show=show_filter] div:contains(tags) fieldset label").mapToSet {
			MangaTag(
				title = it.selectFirstOrThrow("span").text(),
				key = it.selectFirstOrThrow("input[id$=value]").attr("value"),
				source = source,
			)
		}

		val states = EnumSet.of(
			ONGOING, FINISHED, ABANDONED, PAUSED,
		)

		val types = EnumSet.of(
			MANGA, MANHWA, MANHUA, COMICS,
		)

		val rating = EnumSet.of(
			SAFE, SUGGESTIVE,
		)

		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = states,
			availableContentTypes = types,
			availableContentRating = rating,
		)
	}

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/search/data".toHttpUrl().newBuilder().apply {
			addQueryParameter("limit", "32")
			addQueryParameter("offset", offset.toString())
			filter.query?.let {
				val query = it
					.replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
					.replace(Regex("""\s+"""), " ")
					.trim()
				addQueryParameter("text", query)
			}
			addQueryParameter(
				name = "sort",
				value = when (order) {
					RELEVANCE -> "Best Match"
					ALPHABETICAL, ALPHABETICAL_DESC -> "Alphabet"
					POPULARITY, POPULARITY_ASC -> "Popularity"
					RATING, RATING_ASC -> "Subscribers"
					ADDED, ADDED_ASC -> "Recently Added"
					UPDATED, UPDATED_ASC -> "Latest Updates"
					else -> throw UnsupportedOperationException("unsupported order: $order")
				},
			)
			addQueryParameter(
				name = "order",
				value = when (order) {
					RELEVANCE, ALPHABETICAL, POPULARITY_ASC, RATING_ASC, ADDED_ASC, UPDATED_ASC -> "Ascending"
					ALPHABETICAL_DESC, POPULARITY, RATING, ADDED, UPDATED -> "Descending"
					else -> throw UnsupportedOperationException("unsupported order: $order")
				},
			)
			addQueryParameter("official", "Any")
			addQueryParameter("anime", "Any")
			with(filter.contentRating) {
				addQueryParameter(
					name = "adult",
					value = when {
						isEmpty() -> "Any"
						SAFE in this && SUGGESTIVE in this -> "Any"
						SAFE in this -> "False"
						SUGGESTIVE in this -> "True"
						else -> throw UnsupportedOperationException("unsupported content rating: $this")
					},
				)
			}
			filter.states.forEach { state ->
				addQueryParameter(
					name = "included_status",
					value = when (state) {
						ONGOING -> "Ongoing"
						FINISHED -> "Complete"
						ABANDONED -> "Canceled"
						PAUSED -> "Hiatus"
						else -> throw UnsupportedOperationException("unsupported state: $state")
					},
				)
			}
			filter.types.forEach { type ->
				addQueryParameter(
					name = "included_type",
					value = when (type) {
						MANGA -> "Manga"
						MANHWA -> "Manhwa"
						MANHUA -> "Manhua"
						COMICS -> "OEL"
						else -> throw UnsupportedOperationException("unsupported type: $type")
					},
				)
			}
			filter.tags.forEach { tag ->
				addQueryParameter("included_tag", tag.key)
			}
			filter.tagsExclude.forEach { tag ->
				addQueryParameter("excluded_tag", tag.key)
			}
			addQueryParameter("display_mode", "Full Display")
		}.build()

		val document = webClient.httpGet(url).parseHtml()

		return document.select("article:has(section)").map { element ->
			val mangaId = element.selectFirstOrThrow("a")
				.attrAsAbsoluteUrl("href")
				.toHttpUrl()
				.pathSegments[1]
			val author = document.select("div:contains(author) a").eachText().joinToString().nullIfEmpty()
			val title =
				element.selectFirst("div.text-ellipsis.truncate.text-white.text-center.text-lg.z-20.w-\\[90\\%\\]")
					?.text()
					?: "No name"
			Manga(
				id = generateUid(mangaId),
				url = mangaId,
				publicUrl = "https://$domain/series/$mangaId",
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = if (element.selectFirst("svg:has(style:containsData(ff0000))") == null) {
					SAFE
				} else {
					SUGGESTIVE
				},
				coverUrl = element.selectFirst("picture img")?.attrAsAbsoluteUrlOrNull("src"),
				tags = element.selectFirst("div:contains(Tag(s): )")?.text()
					?.substringAfter("Tag(s): ")
					?.split(", ")
					?.mapToSet {
						MangaTag(
							title = it,
							key = it,
							source = source,
						)
					}
					.orEmpty(),
				state = when (document.selectFirst("div:contains(status) span")?.text()) {
					"Ongoing" -> ONGOING
					"Complete" -> FINISHED
					"Canceled" -> ABANDONED
					"Hiatus" -> PAUSED
					else -> null
				},
				authors = setOfNotNull(author),
				largeCoverUrl = null,
				chapters = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val document = webClient.httpGet("https://$domain/series/${manga.url}")
			.parseHtml()

		val chapters = async { getChapters(manga.url, document) }

		val sectionLeft = document.select("section[x-data] > section")[0]
		val sectionRight = document.select("section[x-data] > section")[1]
		val author = sectionLeft.select("ul > li:has(strong:contains(Author)) > span > a")
			.eachText().joinToString()

		manga.copy(
			title = sectionRight.selectFirstOrThrow("h1").text(),
			altTitles = sectionRight.select("li:has(strong:contains(Associated Name)) li")
				.eachText().toSet(),
			publicUrl = "https://$domain/series/${manga.url}",
			rating = RATING_UNKNOWN,
			contentRating = if (sectionLeft.selectFirst("ul > li > strong:contains(Official Translation) + a:contains(Yes)") != null) {
				SUGGESTIVE
			} else {
				SAFE
			},
			coverUrl = sectionLeft.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = sectionLeft.select("ul > li:has(strong:contains(Tag)) a").mapToSet {
				MangaTag(
					title = it.text(),
					key = it.text(),
					source = source,
				)
			},
			state = when (sectionLeft.selectFirst("ul > li:has(strong:contains(Status)) > a")?.text()) {
				"Ongoing" -> ONGOING
				"Complete" -> FINISHED
				"Canceled" -> ABANDONED
				"Hiatus" -> PAUSED
				else -> null
			},
			authors = setOf(author),
			description = Element("div").also { desc ->
				sectionRight.selectFirst("li:has(strong:contains(Description)) > p")?.let {
					desc.appendChild(it)
				}

				val ul = Element("ul")
				sectionLeft.select("ul > li:has(strong:contains(Track)) abbr").stream().forEach { abbr ->
					abbr.selectFirst("a")?.attr("href")?.let { url ->
						val a = Element("a")
							.text(
								abbr.attr("title"),
							)
							.attr("href", url)

						ul.appendChild(
							Element("li").appendChild(a),
						)
					}
				}

				if (ul.children().isNotEmpty()) {
					desc.append("<br><strong>Links:</strong>")
					desc.appendChild(ul)
				}
			}.outerHtml(),
			chapters = chapters.await(),
			source = source,
		)
	}

	private suspend fun getChapters(mangaId: String, mangaDocument: Document): List<MangaChapter> {
		val document = if (mangaDocument.selectFirst("#chapter-list > button[hx-get*=full-chapter-list]") != null) {
			webClient.httpGet("https://$domain/series/$mangaId/full-chapter-list").parseHtml()
		} else {
			mangaDocument
		}

		return document.select("div[x-data] > a").mapChapters(reversed = true) { i, element ->
			val chapterId = element.attrAsAbsoluteUrl("href")
				.toHttpUrl()
				.pathSegments[1]
			val name = element.selectFirstOrThrow("span.flex > span").text()

			MangaChapter(
				id = generateUid(chapterId),
				url = chapterId,
				title = name,
				number = Regex("""(?<!S)\b(\d+(\.\d+)?)\b""").find(name)
					?.groupValues?.get(1)?.toFloatOrNull()
					?: i.toFloat(),
				volume = Regex("""(?:S|vol(?:ume)?)\s*(\d+)""").find(name)
					?.groupValues?.get(1)?.toInt()
					?: 0,
				scanlator = when (element.selectFirst("svg")?.attr("stroke")) {
					"#d8b4fe" -> "Official"
					else -> null
				},
				uploadDate = dateFormat.parseSafe(
					element.selectFirst("time[datetime]")?.attr("datetime"),
				),
				branch = null,
				source = source,
			)
		}
	}

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain".toHttpUrl().newBuilder().apply {
			addPathSegment("chapters")
			addPathSegment(chapter.url)
			addPathSegment("images")
			addQueryParameter("is_prev", "False")
			addQueryParameter("reading_style", "long_strip")
		}.build()

		val document = webClient.httpGet(url).parseHtml()

		return document.select("section[x-data~=scroll] > img").map { element ->
			val pageUrl = element.attrAsAbsoluteUrl("src")

			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val mangaId = link.pathSegments[1]

		return resolver.resolveManga(this, mangaId)
	}

	override suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceMangaCommunityInfo {
		val authorized = runCatching { isAuthorized() }.getOrDefault(false)
		val username = if (authorized) runCatching { getUsername() }.getOrNull() else null
		val seriesDoc = runCatching {
			webClient.httpGet("https://$domain/series/${manga.url}").parseHtml()
		}.getOrNull()
		val followCount = seriesDoc?.let { doc ->
			doc.selectFirst("button[hx-post*='/subscribe'] span, [data-subscribe-count], li:has(strong:contains(Subscriber)) span")
				?.textOrNull()
				?.let(::parseCompactCount)
		}
		val targetId = chapter?.url ?: manga.url
		val contentType = if (chapter != null) "chapter" else "series"
		val commentsDoc = runCatching {
			webClient.httpGet(
				"https://$domain/comments/contents/$targetId?content_type=$contentType",
				htmxHeaders(),
			).parseHtml()
		}.getOrNull()
		val loadedCount = commentsDoc?.select("article[id^=comment-]")?.size?.takeIf { it > 0 }
		val headerCount = commentsDoc?.selectFirst("h2")?.textOrNull()?.let { parseCompactCount(it)?.toInt() }
		val count = headerCount ?: loadedCount

		return SourceMangaCommunityInfo(
			followCount = followCount,
			commentCount = if (chapter == null) count else null,
			chapterCommentCount = if (chapter != null) count else null,
			canCurrentUserComment = authorized,
			currentUsername = username,
		)
	}

	override suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter?,
		page: Int,
		sort: SourceCommentSort,
		cursor: String?,
	): SourceCommentPage {
		val targetId = chapter?.url ?: manga.url
		val contentType = if (chapter != null) "chapter" else "series"
		val commentsUrl = buildString {
			append("https://")
			append(domain)
			append("/comments/contents/")
			append(targetId)
			append("?content_type=")
			append(contentType)
			if (!cursor.isNullOrBlank()) {
				append("&cursor=")
				append(cursor.urlEncoded())
			}
		}
		val doc = runCatching {
			webClient.httpGet(commentsUrl, htmxHeaders()).parseHtml()
		}.getOrNull()
		if (doc != null) {
			val comments = doc.select("article[id^=comment-]").mapNotNull(::parseWeebCentralComment)
			val nextCursor = doc.selectFirst("#comment-view-more-container[hx-get]")
				?.attrOrNull("hx-get")
				?.toHttpUrlOrNull()
				?.queryParameter("cursor")
				?.nullIfEmpty()
			return SourceCommentPage(
				comments = comments,
				page = page,
				hasNextPage = nextCursor != null,
				engine = SourceCommentEngine.NATIVE,
				nextCursor = nextCursor,
			)
		}
		val fallbackWidget = resolveWidgetContext(manga, chapter)
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			widgetContext = fallbackWidget,
			engine = fallbackWidget?.engine ?: SourceCommentEngine.NATIVE,
		)
	}

	override suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter?,
		page: Int,
		cursor: String?,
	): SourceCommentPage {
		val doc = runCatching {
			webClient.httpGet(
				"https://$domain/comments/${comment.id}/replies",
				htmxHeaders(),
			).parseHtml()
		}.getOrNull() ?: return super.getReplies(manga, comment, chapter, page, cursor)
		val replies = doc.select("article[id^=reply-]").mapNotNull { el ->
			parseWeebCentralComment(el, parentId = comment.id)
		}
		return SourceCommentPage(
			comments = replies,
			page = page,
			hasNextPage = false,
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
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val targetId = chapter?.url ?: manga.url
		val contentType = if (chapter != null) "chapter" else "series"
		val bodyText = if (isSpoiler) "<spoiler>$text</spoiler>" else text
		val endpoint = if (parentComment != null) {
			"https://$domain/comments/${parentComment.id}/replies".toHttpUrl()
		} else {
			"https://$domain/comments".toHttpUrl().newBuilder()
				.addQueryParameter("content_id", targetId)
				.addQueryParameter("content_type", contentType)
				.build()
		}
		val response = webClient.httpPost(endpoint, mapOf("content" to bodyText), htmxHeaders())
		val code = response.code
		val html = response.use { it.body.string() }
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val doc = Jsoup.parseBodyFragment(html)
		val parsed = doc.selectFirst("article[id^=comment-], article[id^=reply-]")?.let {
			parseWeebCentralComment(it, parentId = parentComment?.id)
		}
		return parsed ?: SourceComment(
			id = System.currentTimeMillis().toString(),
			authorName = runCatching { getUsername() }.getOrNull() ?: "You",
			body = text,
			createdAtEpochMs = System.currentTimeMillis(),
			isSpoiler = isSpoiler,
			parentId = parentComment?.id,
			isOwnComment = true,
		)
	}

	override suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean,
		chapter: MangaChapter?,
	): SourceComment {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val shouldUnlike = comment.userVote > 0 || !isLike
		val action = if (shouldUnlike) "unlike" else "like"
		val response = webClient.httpPost(
			"https://$domain/comments/${comment.id}/$action".toHttpUrl(),
			emptyMap(),
			htmxHeaders(),
		)
		val code = response.code
		response.close()
		if (code == 401 || code == 403) {
			throw AuthRequiredException(source)
		}
		val nextVote = if (shouldUnlike) 0 else 1
		val nextLikes = (comment.likesCount + (nextVote - comment.userVote)).coerceAtLeast(0)
		return comment.copy(
			likesCount = nextLikes,
			userVote = nextVote,
		)
	}

	override suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter?,
	): SourceWidgetContext {
		val pageUrl = if (chapter != null) {
			"https://$domain/chapters/${chapter.url}"
		} else {
			"https://$domain/series/${manga.url}"
		}
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

	private fun htmxHeaders(): Headers = Headers.Builder()
		.add("HX-Request", "true")
		.build()

	private fun parseWeebCentralComment(el: Element, parentId: String? = null): SourceComment? {
		val rawId = el.id()
		val id = rawId.removePrefix("comment-").removePrefix("reply-").nullIfEmpty() ?: return null
		val author = el.selectFirst("span.font-semibold")?.textOrNull() ?: "Anonymous"
		val avatar = el.selectFirst(".avatar img")?.attrAsAbsoluteUrlOrNull("src")
		val timeEl = el.selectFirst("time.text-datetime") ?: el.selectFirst("time")
		val dateAttr = timeEl?.attrOrNull("datetime")?.nullIfEmpty() ?: timeEl?.textOrNull()
		val bodyEl = el.selectFirst("section.mt-2 > p") ?: el.selectFirst("section.mt-2")
		val body = bodyEl?.text()?.trim().orEmpty()
		val bodyHtml = bodyEl?.html()?.nullIfEmpty()
		val xData = el.selectFirst("section[x-data*=likes]")?.attrOrNull("x-data").orEmpty()
		val likes = REGEX_WC_LIKES.find(xData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
		val isLiked = REGEX_WC_IS_LIKED.find(xData)?.groupValues?.getOrNull(1)
			?.equals("true", ignoreCase = true) == true
		val repliesText = el.selectFirst("button[hx-get*=/replies]")?.textOrNull()
		val repliesCount = repliesText?.let { parseCompactCount(it)?.toInt() } ?: 0
		val isSpoiler = bodyEl?.selectFirst("spoiler, .spoiler") != null

		return SourceComment(
			id = id,
			authorName = author,
			authorAvatarUrl = avatar,
			body = body,
			bodyHtml = bodyHtml,
			createdAtEpochMs = parseIsoTimestamp(dateAttr),
			createdAtText = timeEl?.textOrNull(),
			isSpoiler = isSpoiler,
			likesCount = likes,
			userVote = if (isLiked) 1 else 0,
			repliesCount = repliesCount,
			parentId = parentId,
			canReply = parentId == null,
			canLike = true,
		)
	}

	private companion object {
		private val REGEX_WC_LIKES = Regex("""likes\s*:\s*(\d+)""")
		private val REGEX_WC_IS_LIKED = Regex("""is_liked\s*:\s*['"]?(true|false)['"]?""", RegexOption.IGNORE_CASE)
	}
}
