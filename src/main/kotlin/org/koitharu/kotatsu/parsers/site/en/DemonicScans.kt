package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.arraySetOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceCommunityProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.site.community.WidgetDetectors
import org.koitharu.kotatsu.parsers.site.community.parseFloatRating
import org.koitharu.kotatsu.parsers.site.community.parseIsoTimestamp
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DEMONICSCANS", "DemonicScans", "en")
internal class DemonicScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DEMONICSCANS, 25),
    MangaSourceCommunityProvider {

    override val configKeyDomain = ConfigKey.Domain("demonicscans.org")

    override val capabilities: SourceCommunityCapabilities = SourceCommunityCapabilities(
        supportsStats = true,
        supportsRating = false,
        maxRatingScale = 100,
        supportsNativeComments = true,
        supportsCommentPosting = true,
        supportsReplies = true,
        supportsCommentLikes = true,
        supportsCommentDislikes = false,
        supportsCommentEditing = true,
        supportsCommentDeleting = true,
        supportsChapterComments = true,
        commentEngine = SourceCommentEngine.NATIVE,
        supportsWidgetFallback = true,
        requiresAuthForRating = true,
        requiresAuthForComment = true,
        supportedSorts = setOf(SourceCommentSort.NEWEST, SourceCommentSort.TOP),
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasQuery = !filter.query.isNullOrEmpty()
        val isNewest = order == SortOrder.NEWEST && !hasQuery && filter.tags.isEmpty()
        val isAlpha = order == SortOrder.ALPHABETICAL && !hasQuery && filter.tags.isEmpty()
        val isAlphaDesc = order == SortOrder.ALPHABETICAL_DESC && !hasQuery && filter.tags.isEmpty()
        val url = when {
            hasQuery -> "https://$domain/search.php?manga=" + (filter.query ?: "")
            isNewest -> "https://$domain/lastupdates.php?list=$page"
            isAlpha -> buildString {
                append("https://$domain/advanced.php")
                append("?list=$page")
                append("&status=all")
                append("&orderby=NAME ASC")
                if (filter.tags.isNotEmpty()) {
                    filter.tags.forEach { tag ->
                        append("&genre[]=")
                        append(tag.key)
                    }
                }
            }
            isAlphaDesc -> buildString {
                append("https://$domain/advanced.php")
                append("?list=$page")
                append("&status=all")
                append("&orderby=NAME DESC")
                if (filter.tags.isNotEmpty()) {
                    filter.tags.forEach { tag ->
                        append("&genre[]=")
                        append(tag.key)
                    }
                }
            }
            else -> buildString {
                append("https://$domain/advanced.php")
                append("?list=$page")
                append("&status=all")
                append("&orderby=VIEWS DESC")
                if (filter.tags.isNotEmpty()) {
                    filter.tags.forEach { tag ->
                        append("&genre[]=")
                        append(tag.key)
                    }
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        val selector = when {
            hasQuery -> "body > a[href]"
            isNewest -> "div#updates-container > div.updates-element" 
            else -> "div#advanced-content > div.advanced-element"
        }

        return doc.select(selector).map { element ->
            when {
                isNewest -> parseNewestManga(element)
                else -> parseNormalManga(element, hasQuery)
            }
        }
    }

    private fun parseNewestManga(element: Element): Manga {
        val info = element.selectFirst("div.updates-element-info")!!
        val anchor = info.selectFirst("a")!!
        val href = anchor.attr("href").let {
            if (it.startsWith("http", ignoreCase = true)) it else "https://$domain${if (it.startsWith("/")) it else "/$it"}"
        }
        return Manga(
            id = generateUid(href),
            title = anchor.ownText(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = element.selectFirst("div.thumb img")?.attr("src")?.let { url ->
                if (url.startsWith("http", ignoreCase = true)) url else "https://$domain$url"
            },
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    private fun parseNormalManga(element: Element, hasQuery: Boolean): Manga {
        val anchor = if (hasQuery) element else element.selectFirst("a")!!
        val href = anchor.attr("href").let {
            if (it.startsWith("http", ignoreCase = true)) it else "https://$domain${if (it.startsWith("/")) it else "/$it"}"
        }
        return Manga(
            id = generateUid(href),
            title = if (hasQuery)
                element.selectFirst("div.seach-right > div")?.text().orEmpty()
            else
                element.selectFirst("h1")?.ownText().orEmpty(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = anchor.selectFirst("img")?.attr("src")?.let { url ->
                if (url.startsWith("http", ignoreCase = true)) url else "https://$domain$url"
            },
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()
        val info = doc.selectFirst("div#manga-info-container")
        val title = info?.selectFirst("h1.big-fat-titles")?.ownText().orEmpty()
        val thumbnail = info?.selectFirst("div#manga-page img")?.attrAsAbsoluteUrlOrNull("src")
        val genre = info?.select("div.genres-list > li")?.joinToString { it.text() } ?: ""
        val description = info?.selectFirst("div#manga-info-rightColumn > div > div.white-font")?.textOrNull()
        val author = info?.select("div#manga-info-stats > div:has(> li:eq(0):contains(Author)) > li:eq(1)")?.textOrNull()
        val statusText = info?.select("div#manga-info-stats > div:has(> li:eq(0):contains(Status)) > li:eq(1)")?.text()
        val state = when (statusText) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            else -> null
        }

        val chapters = doc.select("div#chapters-list a.chplinks").mapChapters(reversed = true) { i, el ->
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
            val href = el.attr("href").let {
                if (it.startsWith("http")) it else "https://$domain${if (it.startsWith("/")) it else "/$it"}"
            }
            val date = el.selectFirst("span")?.text()
            MangaChapter(
                id = generateUid(href),
                title = el.ownText(),
                number = i + 1f,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(date),
                branch = null,
                source = source
            )
        }

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            tags = genre.split(", ").filter { it.isNotBlank() }.mapToSet {
                MangaTag(title = it.lowercase().replace(" ", "-").toTitleCase(sourceLocale), key = it, source)
            },
            description = description,
            state = state,
            authors = if (author.isNullOrBlank()) emptySet() else setOf(author),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        return doc.select("div > img.imgholder").map {
            val url = it.requireSrc()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    private fun fetchTags() = arraySetOf(
		MangaTag("Action", "1", source),
    	MangaTag("Adventure", "2", source),
    	MangaTag("Comedy", "3", source),
    	MangaTag("Cooking", "34", source),
    	MangaTag("Doujinshi", "25", source),
    	MangaTag("Drama", "4", source),
    	MangaTag("Ecchi", "19", source),
    	MangaTag("Fantasy", "5", source),
    	MangaTag("Gender Bender", "30", source),
    	MangaTag("Harem", "10", source),
   		MangaTag("Historical", "28", source),
    	MangaTag("Horror", "8", source),
    	MangaTag("Isekai", "33", source),
    	MangaTag("Josei", "31", source),
    	MangaTag("Martial Arts", "6", source),
    	MangaTag("Mature", "22", source),
    	MangaTag("Mecha", "32", source),
    	MangaTag("Mystery", "15", source),
    	MangaTag("One Shot", "26", source),
    	MangaTag("Psychological", "11", source),
    	MangaTag("Romance", "12", source),
    	MangaTag("School Life", "13", source),
    	MangaTag("Sci-fi", "16", source),
    	MangaTag("Seinen", "17", source),
    	MangaTag("Shoujo", "14", source),
    	MangaTag("Shoujo Ai", "23", source),
    	MangaTag("Shounen", "7", source),
    	MangaTag("Shounen Ai", "29", source),
    	MangaTag("Slice of Life", "21", source),
    	MangaTag("Smut", "27", source),
    	MangaTag("Sports", "20", source),
    	MangaTag("Supernatural", "9", source),
    	MangaTag("Tragedy", "18", source),
    	MangaTag("Webtoons", "24", source),
    )

    override suspend fun getMangaCommunityInfo(
        manga: Manga,
        chapter: MangaChapter?,
    ): SourceMangaCommunityInfo {
        val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
        val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
        val rawRating = doc?.selectFirst("#manga-info-stats > div:has(> li:eq(0):contains(Rating)) > li:eq(1)")
            ?.textOrNull()
            ?.let(::parseFloatRating)
            ?.takeIf { it in 0.1f..100f }
        val discussionId = doc?.selectFirst("#cw-comments[data-chapter]")?.attrOrNull("data-chapter")
        val totalComments = if (discussionId != null) {
            runCatching {
                webClient.httpGet("https://$domain/comments_api.php?chapterid=$discussionId&sort=newest&offset=0")
                    .parseJson()
                    .getIntOrDefault("total", -1)
                    .takeIf { it >= 0 }
            }.getOrNull()
        } else {
            null
        }
        return SourceMangaCommunityInfo(
            averageRating = rawRating?.let { (it / 100f).coerceIn(0f, 1f) },
            rawRating = rawRating,
            rawScale = 100,
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
        val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
        val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
        val discussionId = doc?.selectFirst("#cw-comments[data-chapter]")?.attrOrNull("data-chapter")
        if (discussionId != null) {
            val offset = cursor?.toIntOrNull() ?: ((page - 1) * 20).coerceAtLeast(0)
            val sortParam = if (sort == SourceCommentSort.TOP) "best" else "newest"
            val apiUrl = "https://$domain/comments_api.php?chapterid=$discussionId&sort=$sortParam&offset=$offset"
            val json = runCatching { webClient.httpGet(apiUrl).parseJson() }.getOrNull()
            val items = json?.optJSONArray("items")
            if (items != null) {
                val comments = items.mapJSONNotNull { parseDemonicComment(it, discussionId) }
                val hasMore = json.getBooleanOrDefault("has_more", false)
                val nextOffset = json.getIntOrDefault("next_offset", offset + comments.size)
                val total = json.getIntOrDefault("total", -1).takeIf { it >= 0 }
                return SourceCommentPage(
                    comments = comments,
                    page = page,
                    hasNextPage = hasMore,
                    totalCount = total,
                    engine = SourceCommentEngine.NATIVE,
                    nextCursor = if (hasMore) nextOffset.toString() else null,
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
        val discussionId = comment.rawMeta["discussionId"] ?: run {
            val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
            runCatching { webClient.httpGet(pageUrl).parseHtml() }
                .getOrNull()
                ?.selectFirst("#cw-comments[data-chapter]")
                ?.attrOrNull("data-chapter")
        } ?: return super.getReplies(manga, comment, chapter, page, cursor)

        val offset = cursor?.toIntOrNull() ?: 0
        val apiUrl = "https://$domain/comments_api.php?chapterid=$discussionId&thread=${comment.id}&offset=$offset"
        val json = runCatching { webClient.httpGet(apiUrl).parseJson() }.getOrNull()
            ?: return super.getReplies(manga, comment, chapter, page, cursor)
        val items = json.optJSONArray("items") ?: JSONArray()
        val replies = items.mapJSONNotNull { parseDemonicComment(it, discussionId, parentId = comment.id) }
        val hasMore = json.getBooleanOrDefault("has_more", false)
        val nextOffset = json.getIntOrDefault("next_offset", offset + replies.size)
        return SourceCommentPage(
            comments = replies,
            page = page,
            hasNextPage = hasMore,
            engine = SourceCommentEngine.NATIVE,
            nextCursor = if (hasMore) nextOffset.toString() else null,
        )
    }

    override suspend fun postComment(
        manga: Manga,
        text: String,
        chapter: MangaChapter?,
        parentComment: SourceComment?,
        isSpoiler: Boolean,
    ): SourceComment {
        val discussionId = resolveDiscussionId(manga, chapter, parentComment)
            ?: throw UnsupportedOperationException("Discussion not found")
        val payload = JSONObject().apply {
            put("action", "post")
            put("chapterid", discussionId.toLongOrNull() ?: discussionId)
            put("commentcontent", text)
            put("replyto", parentComment?.id?.toLongOrNull() ?: 0L)
        }
        val jo = postCommentsApi(discussionId, payload)
        val item = jo.optJSONObject("item") ?: jo.optJSONObject("comment")
        return item?.let { parseDemonicComment(it, discussionId, parentId = parentComment?.id) }
            ?: SourceComment(
                id = System.currentTimeMillis().toString(),
                authorName = "You",
                body = text,
                createdAtEpochMs = System.currentTimeMillis(),
                parentId = parentComment?.id,
                isOwnComment = true,
                rawMeta = mapOf("discussionId" to discussionId),
            )
    }

    override suspend fun editComment(
        manga: Manga,
        comment: SourceComment,
        newText: String,
        chapter: MangaChapter?,
    ): SourceComment {
        val discussionId = resolveDiscussionId(manga, chapter, comment)
            ?: throw UnsupportedOperationException("Discussion not found")
        val payload = JSONObject().apply {
            put("action", "edit")
            put("chapterid", discussionId.toLongOrNull() ?: discussionId)
            put("commentid", comment.id.toLongOrNull() ?: comment.id)
            put("commentcontent", newText)
        }
        postCommentsApi(discussionId, payload)
        return comment.copy(body = newText, bodyHtml = null, isEdited = true)
    }

    override suspend fun deleteComment(
        manga: Manga,
        comment: SourceComment,
        chapter: MangaChapter?,
    ) {
        val discussionId = resolveDiscussionId(manga, chapter, comment)
            ?: throw UnsupportedOperationException("Discussion not found")
        val payload = JSONObject().apply {
            put("action", "delete")
            put("commentid", comment.id.toLongOrNull() ?: comment.id)
        }
        postCommentsApi(discussionId, payload)
    }

    override suspend fun toggleCommentLike(
        manga: Manga,
        comment: SourceComment,
        isLike: Boolean,
        chapter: MangaChapter?,
    ): SourceComment {
        val discussionId = resolveDiscussionId(manga, chapter, comment)
            ?: throw UnsupportedOperationException("Discussion not found")
        val nextLiked = comment.userVote <= 0 && isLike
        val payload = JSONObject().apply {
            put("action", "like")
            put("commentid", comment.id.toLongOrNull() ?: comment.id)
            put("liked", nextLiked)
        }
        val jo = postCommentsApi(discussionId, payload)
        val likes = jo.getIntOrDefault("likes", comment.likesCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
        val liked = jo.getBooleanOrDefault("liked", nextLiked)
        return comment.copy(
            likesCount = likes,
            userVote = if (liked) 1 else 0,
        )
    }

    override suspend fun resolveWidgetContext(
        manga: Manga,
        chapter: MangaChapter?,
    ): SourceWidgetContext {
        val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
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

    private suspend fun resolveDiscussionId(
        manga: Manga,
        chapter: MangaChapter?,
        comment: SourceComment?,
    ): String? {
        comment?.rawMeta?.get("discussionId")?.let { return it }
        val pageUrl = (chapter?.url ?: manga.url).toAbsoluteUrl(domain)
        val doc = runCatching { webClient.httpGet(pageUrl).parseHtml() }.getOrNull()
        return doc?.selectFirst("#cw-comments[data-chapter]")?.attrOrNull("data-chapter")
    }

    private suspend fun postCommentsApi(discussionId: String, payload: JSONObject): JSONObject {
        val url = "https://$domain/comments_api.php?chapterid=$discussionId".toHttpUrl()
        val response = webClient.httpPost(url, payload)
        val code = response.code
        val raw = response.use { it.body.string() }
        if (code == 401 || code == 403) {
            throw AuthRequiredException(source)
        }
        val jo = raw.toJSONObjectOrNull() ?: JSONObject()
        if (!jo.getBooleanOrDefault("ok", true)) {
            val msg = jo.getStringOrNull("message").orEmpty()
            if (msg.contains("sign in", ignoreCase = true) || msg.contains("login", ignoreCase = true) || msg.contains("auth", ignoreCase = true)) {
                throw AuthRequiredException(source)
            }
        }
        return jo
    }

    private fun parseDemonicComment(
        jo: JSONObject,
        discussionId: String,
        parentId: String? = null,
    ): SourceComment? {
        val id = jo.getStringOrNull("id") ?: return null
        val avatarPath = jo.getStringOrNull("avatar")?.let { raw ->
            if (raw.startsWith("http", ignoreCase = true)) raw else "https://$domain/${raw.trimStart('/')}"
        }
        val createdAt = jo.getStringOrNull("created_at")
        val replyTo = jo.getLongOrDefault("reply_to", 0L)
        val resolvedParent = parentId ?: if (replyTo > 0L) replyTo.toString() else null
        val canEdit = jo.getBooleanOrDefault("can_edit", false)
        val canDelete = jo.getBooleanOrDefault("can_delete", false)
        val liked = jo.getBooleanOrDefault("liked", false)

        return SourceComment(
            id = id,
            authorName = jo.getStringOrNull("username") ?: "Reader",
            authorAvatarUrl = avatarPath,
            body = jo.getStringOrNull("content").orEmpty(),
            createdAtEpochMs = parseIsoTimestamp(createdAt),
            createdAtText = createdAt,
            isEdited = jo.getStringOrNull("edited_at") != null,
            isPinned = jo.getBooleanOrDefault("pinned", false),
            likesCount = jo.getIntOrDefault("likes", 0),
            userVote = if (liked) 1 else 0,
            repliesCount = jo.getIntOrDefault("reply_count", 0),
            parentId = resolvedParent,
            isOwnComment = canEdit || canDelete,
            canReply = resolvedParent == null,
            canLike = true,
            canEdit = canEdit,
            canDelete = canDelete,
            rawMeta = mapOf("discussionId" to discussionId),
        )
    }
}
