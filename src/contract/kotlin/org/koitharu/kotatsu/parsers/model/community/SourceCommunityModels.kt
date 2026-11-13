package org.koitharu.kotatsu.parsers.model.community

public enum class SourceCommentEngine {
	NATIVE,
	WORDPRESS,
	WPDISCUZ,
	GRAPHCOMMENT,
	DISQUS,
	HYVOR,
	CUSDIS,
	CUSTOM_WIDGET,
	NONE,
}

public enum class SourceCommentSort {
	NEWEST,
	OLDEST,
	TOP,
}

public data class SourceCommunityCapabilities(
	public val supportsStats: Boolean = false,
	public val supportsRating: Boolean = false,
	public val maxRatingScale: Int = 5,
	public val supportsNativeComments: Boolean = false,
	public val supportsCommentPosting: Boolean = false,
	public val supportsReplies: Boolean = false,
	public val supportsCommentLikes: Boolean = false,
	public val supportsCommentDislikes: Boolean = false,
	public val supportsCommentEditing: Boolean = false,
	public val supportsCommentDeleting: Boolean = false,
	public val supportsChapterComments: Boolean = false,
	public val commentEngine: SourceCommentEngine = SourceCommentEngine.NONE,
	public val supportsWidgetFallback: Boolean = false,
	public val requiresAuthForRating: Boolean = true,
	public val requiresAuthForComment: Boolean = true,
	public val supportedSorts: Set<SourceCommentSort> = setOf(SourceCommentSort.NEWEST),
	public val customLabels: Map<String, String> = emptyMap(),
)

public data class SourceWidgetContext(
	public val engine: SourceCommentEngine,
	public val pageUrl: String,
	public val widgetUrl: String,
	public val identifier: String? = null,
	public val shortname: String? = null,
	public val title: String? = null,
	public val inlineHtml: String? = null,
	public val baseUrl: String? = null,
	public val extraHeaders: Map<String, String> = emptyMap(),
	public val allowedHostSuffixes: Set<String> = emptySet(),
)

public data class SourceMangaCommunityInfo(
	public val averageRating: Float? = null,
	public val rawRating: Float? = null,
	public val rawScale: Int = 5,
	public val userRating: Int? = null,
	public val ratingCount: Int? = null,
	public val viewCount: Long? = null,
	public val followCount: Long? = null,
	public val commentCount: Int? = null,
	public val chapterCommentCount: Int? = null,
	public val canCurrentUserRate: Boolean = false,
	public val canCurrentUserComment: Boolean = false,
	public val currentUsername: String? = null,
)

public data class SourceComment(
	public val id: String,
	public val authorName: String,
	public val authorAvatarUrl: String? = null,
	public val authorRole: String? = null,
	public val body: String,
	public val bodyHtml: String? = null,
	public val createdAtEpochMs: Long? = null,
	public val createdAtText: String? = null,
	public val isEdited: Boolean = false,
	public val isSpoiler: Boolean = false,
	public val isPinned: Boolean = false,
	public val likesCount: Int = 0,
	public val dislikesCount: Int = 0,
	public val userVote: Int = 0,
	public val repliesCount: Int = 0,
	public val parentId: String? = null,
	public val replies: List<SourceComment> = emptyList(),
	public val isOwnComment: Boolean = false,
	public val canReply: Boolean = false,
	public val canLike: Boolean = false,
	public val canDislike: Boolean = false,
	public val canEdit: Boolean = false,
	public val canDelete: Boolean = false,
	public val rawMeta: Map<String, String> = emptyMap(),
)

public data class SourceCommentPage(
	public val comments: List<SourceComment>,
	public val page: Int = 1,
	public val hasNextPage: Boolean = false,
	public val totalCount: Int? = null,
	public val widgetContext: SourceWidgetContext? = null,
	public val engine: SourceCommentEngine = SourceCommentEngine.NONE,
	public val nextCursor: String? = null,
)
