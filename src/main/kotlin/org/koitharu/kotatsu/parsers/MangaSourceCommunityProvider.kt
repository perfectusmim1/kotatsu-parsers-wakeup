package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentPage
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.model.community.SourceCommunityCapabilities
import org.koitharu.kotatsu.parsers.model.community.SourceMangaCommunityInfo
import org.koitharu.kotatsu.parsers.model.community.SourceWidgetContext

/**
 * Optional capability contract for parsers that integrate with live source-side
 * statistics, user ratings, comments, replies, likes/votes, or embedded comment widgets.
 */
public interface MangaSourceCommunityProvider {

	public val capabilities: SourceCommunityCapabilities

	public suspend fun getMangaCommunityInfo(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo

	public suspend fun submitRating(
		manga: Manga,
		score: Int,
		chapter: MangaChapter? = null,
	): SourceMangaCommunityInfo {
		throw UnsupportedOperationException("Rating is not supported")
	}

	public suspend fun getComments(
		manga: Manga,
		chapter: MangaChapter? = null,
		page: Int = 1,
		sort: SourceCommentSort = SourceCommentSort.NEWEST,
		cursor: String? = null,
	): SourceCommentPage {
		return SourceCommentPage(
			comments = emptyList(),
			page = page,
			hasNextPage = false,
			engine = capabilities.commentEngine,
		)
	}

	public suspend fun getReplies(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
		page: Int = 1,
		cursor: String? = null,
	): SourceCommentPage {
		return SourceCommentPage(
			comments = comment.replies,
			page = page,
			hasNextPage = false,
			engine = capabilities.commentEngine,
		)
	}

	public suspend fun postComment(
		manga: Manga,
		text: String,
		chapter: MangaChapter? = null,
		parentComment: SourceComment? = null,
		isSpoiler: Boolean = false,
	): SourceComment {
		throw UnsupportedOperationException("Posting comments is not supported")
	}

	public suspend fun editComment(
		manga: Manga,
		comment: SourceComment,
		newText: String,
		chapter: MangaChapter? = null,
	): SourceComment {
		throw UnsupportedOperationException("Editing comments is not supported")
	}

	public suspend fun deleteComment(
		manga: Manga,
		comment: SourceComment,
		chapter: MangaChapter? = null,
	) {
		throw UnsupportedOperationException("Deleting comments is not supported")
	}

	public suspend fun toggleCommentLike(
		manga: Manga,
		comment: SourceComment,
		isLike: Boolean = true,
		chapter: MangaChapter? = null,
	): SourceComment {
		throw UnsupportedOperationException("Comment reactions are not supported")
	}

	public suspend fun resolveWidgetContext(
		manga: Manga,
		chapter: MangaChapter? = null,
	): SourceWidgetContext? = null
}
