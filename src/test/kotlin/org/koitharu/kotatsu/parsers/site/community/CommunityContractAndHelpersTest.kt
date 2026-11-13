package org.koitharu.kotatsu.parsers.site.community

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.InMemoryCookieJar
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.community.SourceComment
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.all.MangaBallParser
import org.koitharu.kotatsu.parsers.site.all.MangaDexParser
import org.koitharu.kotatsu.parsers.site.en.MangaHere
import org.koitharu.kotatsu.parsers.site.iken.en.QiScans
import org.koitharu.kotatsu.parsers.site.mangareader.tr.Golgebahcesi
import org.koitharu.kotatsu.parsers.site.ru.MangaBuff
import org.koitharu.kotatsu.parsers.site.tr.TrWebtoon
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class CommunityContractAndHelpersTest {

	private data class RecordedRequest(
		val method: String,
		val url: String,
		val headers: Map<String, String>,
		val body: String,
	)

	private class MockHttpHarness(
		private val handler: (Request, String) -> ResponseSpec,
	) {
		data class ResponseSpec(
			val code: Int = 200,
			val body: String = "",
			val contentType: String = "application/json; charset=utf-8",
			val headers: Map<String, String> = emptyMap(),
		)

		val recorded = CopyOnWriteArrayList<RecordedRequest>()
		val source: MangaSource = MangaParserSource.OKUTOON
		val authValues = ConcurrentHashMap<String, String>()

		val context: MangaLoaderContext = object : MangaLoaderContext() {
			override val cookieJar = InMemoryCookieJar()
			override val parsers: ParsersBundle
				get() = ParsersBundleImpl
			override val httpClient: OkHttpClient = OkHttpClient.Builder()
				.cookieJar(cookieJar)
				.addInterceptor { chain ->
					val req = chain.request()
					val buf = Buffer()
					req.body?.writeTo(buf)
					val bodyStr = buf.readUtf8()
					val headerMap = req.headers.associate { it.first to it.second }
					recorded.add(RecordedRequest(req.method, req.url.toString(), headerMap, bodyStr))
					val spec = handler(req, bodyStr)
					val builder = Response.Builder()
						.request(req)
						.protocol(Protocol.HTTP_1_1)
						.code(spec.code)
						.message(if (spec.code in 200..299) "OK" else "Error")
						.body(spec.body.toResponseBody(spec.contentType.toMediaType()))
					for ((k, v) in spec.headers) {
						builder.header(k, v)
					}
					builder.build()
				}
				.build()

			@Deprecated("Provide a base url")
			override suspend fun evaluateJs(script: String): String? = null
			override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
			override fun getConfig(source: MangaSource): MangaSourceConfig = SourceConfigMock()
			override fun getDefaultUserAgent(): String = UserAgents.CHROME_MOBILE
			override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
			override fun createBitmap(width: Int, height: Int): Bitmap {
				throw UnsupportedOperationException()
			}
			override suspend fun getAuthValue(source: MangaSource, key: String): String? =
				authValues["${source.name}:$key"]
			override suspend fun setAuthValue(source: MangaSource, key: String, value: String?) {
				val fullKey = "${source.name}:$key"
				if (value == null) authValues.remove(fullKey) else authValues[fullKey] = value
			}
			override suspend fun clearAuthValues(source: MangaSource) {
				val prefix = "${source.name}:"
				authValues.keys.removeIf { it.startsWith(prefix) }
			}
		}

		val webClient = OkHttpWebClient(context.httpClient, source)
	}

	private fun dummyManga(url: String = "/manga/test-series", source: MangaSource = MangaParserSource.OKUTOON): Manga =
		Manga(
			id = 1L,
			title = "Test Series",
			altTitles = emptySet(),
			url = url,
			publicUrl = "https://example.com$url",
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)

	@Test
	fun testLoaderContextAuthValuesLifecycle() = runTest {
		val s1 = MangaParserSource.OKUTOON
		val s2 = MangaParserSource.MANGADEX
		MangaLoaderContextMock.clearAuthValues(s1)
		MangaLoaderContextMock.clearAuthValues(s2)

		assertNull(MangaLoaderContextMock.getAuthValue(s1, "access_token"))
		MangaLoaderContextMock.setAuthValue(s1, "access_token", "tok_123")
		MangaLoaderContextMock.setAuthValue(s2, "access_token", "tok_456")

		assertEquals("tok_123", MangaLoaderContextMock.getAuthValue(s1, "access_token"))
		assertEquals("tok_456", MangaLoaderContextMock.getAuthValue(s2, "access_token"))

		MangaLoaderContextMock.setAuthValue(s1, "access_token", null)
		assertNull(MangaLoaderContextMock.getAuthValue(s1, "access_token"))
		assertEquals("tok_456", MangaLoaderContextMock.getAuthValue(s2, "access_token"))

		MangaLoaderContextMock.clearAuthValues(s2)
		assertNull(MangaLoaderContextMock.getAuthValue(s2, "access_token"))
	}

	@Test
	fun testOkutoonStatsCommentsRepliesAndMutations() = runTest {
		val seriesHtml = """
			<html>
			<head>
				<meta name="csrf-token" content="csrf_secret_99">
				<script type="application/ld+json">
					{"@type":"Book","aggregateRating":{"@type":"AggregateRating","ratingValue":"4.6","bestRating":"5","ratingCount":"128"}}
				</script>
			</head>
			<body>
				<a class="site-profile-link" href="/@okuyucu">@okuyucu</a>
				<div class="series-stat--rating"><strong>4,7</strong><span class="series-stat-sub">(132 oy)</span></div>
				<span data-series-view-count="15400">15.4K</span>
				<div class="comments-section"
					data-comment-action="/api/series/42/comments"
					data-comment-load-url="/api/series/42/comments/list"
					data-comment-like-url-template="/api/comments/__ID__/like"
					data-comment-delete-url-template="/api/comments/__ID__"
					data-comment-edit-url-template="/api/comments/__ID__/edit"
					data-comment-replies-url-template="/api/comments/__ID__/replies"
					data-comment-thread-url-template="/api/comments/__ID__/thread"
					data-current-user-id="77"
					data-comment-batch-size="10"
					data-comment-kind="series">
					<form>
						<input type="hidden" name="_hp_token" value="hp_xyz">
						<input type="checkbox" name="content_policy" value="1">
					</form>
				</div>
			</body>
			</html>
		""".trimIndent()

		val commentsJson = """
			{
				"total": 25,
				"has_more": true,
				"comments": [
					{
						"id": 101,
						"user_id": 77,
						"parent_id": null,
						"username": "okuyucu",
						"avatar_url": "/avatars/77.png",
						"body": "Harika seri!",
						"body_html": "<p>Harika seri!</p>",
						"review_score": 5,
						"is_rating_only": false,
						"is_review": true,
						"is_pinned": true,
						"created_at": "2026-11-10T12:00:00Z",
						"edited": true,
						"like_count": 14,
						"liked_by_current_user": true,
						"can_delete": true,
						"can_edit": true,
						"reply_total": 3,
						"reply_count": 1,
						"thread_reply_total": 3,
						"replies": [
							{
								"id": 102,
								"user_id": 88,
								"parent_id": 101,
								"username": "misafir",
								"body": "Katılıyorum",
								"created_at": "2026-11-10T12:05:00Z",
								"like_count": 2,
								"liked_by_current_user": false,
								"can_delete": false,
								"can_edit": false
							}
						]
					}
				]
			}
		""".trimIndent()

		val repliesJson = """
			{
				"total": 3,
				"has_more": false,
				"replies": [
					{
						"id": 102,
						"user_id": 88,
						"parent_id": 101,
						"username": "misafir",
						"body": "Katılıyorum",
						"created_at": "2026-11-10T12:05:00Z"
					}
				]
			}
		""".trimIndent()

		val harness = MockHttpHarness { req, _ ->
			val path = req.url.encodedPath
			when {
				path == "/manga/test-series" -> MockHttpHarness.ResponseSpec(200, seriesHtml, "text/html")
				path == "/api/series/42/comments/list" -> MockHttpHarness.ResponseSpec(200, commentsJson)
				path == "/api/comments/101/replies" -> MockHttpHarness.ResponseSpec(200, repliesJson)
				path == "/api/series/42/comments" && req.method == "POST" -> MockHttpHarness.ResponseSpec(
					201,
					"""{"comment":{"id":201,"user_id":77,"username":"okuyucu","body":"Yeni yorum","created_at":"2026-11-11T10:00:00Z","can_edit":true,"can_delete":true}}""",
				)
				path == "/api/comments/201/edit" && req.method == "POST" -> MockHttpHarness.ResponseSpec(
					200,
					"""{"comment":{"id":201,"user_id":77,"username":"okuyucu","body":"Duzenlendi","edited":true,"can_edit":true,"can_delete":true}}""",
				)
				path == "/api/comments/201/like" && req.method == "POST" -> MockHttpHarness.ResponseSpec(
					200,
					"""{"liked_by_current_user":true,"like_count":5}""",
				)
				path == "/api/comments/201" && req.method == "DELETE" -> MockHttpHarness.ResponseSpec(200, """{"ok":true}""")
				path == "/login" -> MockHttpHarness.ResponseSpec(200, seriesHtml, "text/html")
				path == "/" -> MockHttpHarness.ResponseSpec(200, seriesHtml, "text/html")
				path == "/logout" -> MockHttpHarness.ResponseSpec(200, """{"ok":true}""")
				else -> MockHttpHarness.ResponseSpec(404, """{"error":"not found"}""")
			}
		}

		val helper = OkutoonCommunityHelper(
			context = harness.context,
			webClient = harness.webClient,
			source = harness.source,
			domainProvider = { "okutoon.com" },
		)

		val manga = dummyManga()
		val info = helper.getMangaCommunityInfo(manga)
		assertEquals(4.7f, info.rawRating)
		assertEquals(4.7f / 5f, info.averageRating)
		assertEquals(132, info.ratingCount)
		assertEquals(15400L, info.viewCount)
		assertEquals("okuyucu", info.currentUsername)
		assertTrue(info.canCurrentUserRate)
		assertTrue(info.canCurrentUserComment)

		val page = helper.getComments(manga, page = 1)
		assertEquals(1, page.comments.size)
		assertTrue(page.hasNextPage)
		assertEquals("1", page.nextCursor)
		assertEquals(25, page.totalCount)
		val c1 = page.comments.first()
		assertEquals("101", c1.id)
		assertTrue(c1.isPinned)
		assertTrue(c1.isEdited)
		assertEquals(14, c1.likesCount)
		assertEquals(1, c1.userVote)
		assertEquals(3, c1.repliesCount)
		assertEquals("5", c1.rawMeta["review_score"])
		assertEquals(1, c1.replies.size)

		val replies = helper.getReplies(manga, c1)
		assertEquals(1, replies.comments.size)
		assertEquals("102", replies.comments.first().id)

		val posted = helper.postComment(manga, "Yeni yorum")
		assertEquals("201", posted.id)
		assertEquals("Yeni yorum", posted.body)
		val postReq = harness.recorded.last { it.url.endsWith("/api/series/42/comments") }
		assertEquals("csrf_secret_99", postReq.headers["X-CSRF-Token"])
		assertEquals("application/json", postReq.headers["Accept"])
		assertTrue(postReq.body.contains("\"_hp_token\":\"hp_xyz\""))
		assertTrue(postReq.body.contains("\"content_policy\":\"1\""))

		val edited = helper.editComment(manga, posted, "Duzenlendi")
		assertEquals("Duzenlendi", edited.body)
		assertTrue(edited.isEdited)

		val liked = helper.toggleCommentLike(manga, edited)
		assertEquals(5, liked.likesCount)
		assertEquals(1, liked.userVote)

		helper.deleteComment(manga, liked)
		assertTrue(harness.recorded.any { it.method == "DELETE" && it.url.endsWith("/api/comments/201") })

		assertEquals("https://okutoon.com/login", helper.authUrl)
		assertEquals("https://okutoon.com/register", helper.signUpUrl)
		assertTrue(helper.isAuthorized())
		assertEquals("okuyucu", helper.getUsername())
	}

	@Test
	fun testOkutoonHeldForReviewProfanity422AndAuthErrors() = runTest {
		val authedHtml = """
			<html><head><meta name="csrf-token" content="t1"></head><body>
			<a href="/@user1">@user1</a>
			<div class="comments-section" data-comment-action="/api/comments" data-current-user-id="5"></div>
			</body></html>
		""".trimIndent()
		val unauthedHtml = """
			<html><body><div class="comments-section" data-comment-action="/api/comments" data-current-user-id=""></div></body></html>
		""".trimIndent()

		var mode = "held"
		val harness = MockHttpHarness { req, _ ->
			when (req.url.encodedPath) {
				"/manga/s" -> MockHttpHarness.ResponseSpec(200, if (mode == "unauth") unauthedHtml else authedHtml, "text/html")
				"/api/comments" -> when (mode) {
					"held" -> MockHttpHarness.ResponseSpec(
						200,
						"""{"held_for_review":true,"data":{"message":"Yorumunuz onay bekliyor."}}""",
					)
					"profanity" -> MockHttpHarness.ResponseSpec(
						422,
						"""{"code":"profanity_warning","message":"Lütfen uygunsuz ifadeleri düzeltin."}""",
					)
					else -> MockHttpHarness.ResponseSpec(403, """{"error":"Forbidden"}""")
				}
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}

		val helper = OkutoonCommunityHelper(harness.context, harness.webClient, harness.source, { "okutoon.com" })
		val manga = dummyManga("/manga/s")

		val heldComment = helper.postComment(manga, "test")
		assertEquals("Yorumunuz onay bekliyor.", heldComment.body)
		assertEquals("true", heldComment.rawMeta["held_for_review"])

		mode = "profanity"
		val profanityEx = assertThrowsSuspending<ParseException> {
			helper.postComment(manga, "bad word")
		}
		assertTrue(profanityEx.shortMessage?.contains("uygunsuz") == true)

		mode = "403"
		assertThrowsSuspending<AuthRequiredException> {
			helper.postComment(manga, "test")
		}

		mode = "unauth"
		assertThrowsSuspending<AuthRequiredException> {
			helper.postComment(manga, "test")
		}
	}

	private suspend inline fun <reified T : Throwable> assertThrowsSuspending(
		crossinline block: suspend () -> Unit,
	): T {
		try {
			block()
		} catch (e: Throwable) {
			if (e is T) return e
			throw AssertionError("Expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}", e)
		}
		throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but nothing was thrown")
	}

	@Test
	fun testWordPressRestAndHtmlFallbackComments() = runTest {
		val wpHtml = """
			<html>
			<head>
				<link rel="shortlink" href="https://wpsite.test/?p=555">
				<script>var wpApiSettings = {"root":"https://wpsite.test/wp-json/","nonce":"wpnonce77"};</script>
			</head>
			<body class="postid-555">
				<ol class="comment-list">
					<li id="comment-10" class="comment">
						<div class="comment-body">
							<div class="comment-author"><b class="fn">Ali</b></div>
							<time datetime="2026-11-01T10:00:00Z">Nov 1</time>
							<div class="comment-content"><p>HTML top comment</p></div>
						</div>
						<ol class="children">
							<li id="comment-11" class="comment">
								<div class="comment-body">
									<div class="comment-author"><b class="fn">Veli</b></div>
									<div class="comment-content"><p>HTML reply</p></div>
								</div>
							</li>
						</ol>
					</li>
				</ol>
				<form id="commentform" action="/wp-comments-post.php">
					<input type="hidden" name="comment_post_ID" value="555">
					<input type="hidden" name="comment_parent" value="0">
				</form>
			</body>
			</html>
		""".trimIndent()

		val wpRestJson = """
			[
				{
					"id": 1,
					"parent": 0,
					"author_name": "Reader1",
					"author_avatar_urls": {"96": "https://wpsite.test/a1.png"},
					"date_gmt": "2026-11-10T09:00:00",
					"content": {"rendered": "<p>REST comment</p>"}
				},
				{
					"id": 2,
					"parent": 1,
					"author_name": "Reader2",
					"date_gmt": "2026-11-10T09:05:00",
					"content": {"rendered": "<p>REST reply</p>"}
				}
			]
		""".trimIndent()

		var allowRest = true
		val harness = MockHttpHarness { req, _ ->
			when (req.url.encodedPath) {
				"/wp-json/wp/v2/comments" -> {
					if (!allowRest) {
						MockHttpHarness.ResponseSpec(404, """{"code":"rest_no_route"}""")
					} else if (req.method == "POST") {
						MockHttpHarness.ResponseSpec(
							201,
							"""{"id":99,"parent":0,"author_name":"You","date_gmt":"2026-11-11T09:00:00","content":{"rendered":"<p>Posted REST</p>"}}""",
						)
					} else {
						MockHttpHarness.ResponseSpec(
							200,
							wpRestJson,
							headers = mapOf("X-WP-Total" to "30", "X-WP-TotalPages" to "2"),
						)
					}
				}
				"/wp-comments-post.php" -> MockHttpHarness.ResponseSpec(200, wpHtml, "text/html")
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}

		val helper = WordPressCommentsHelper(harness.context, harness.webClient, harness.source, { "wpsite.test" })
		val doc = Jsoup.parse(wpHtml, "https://wpsite.test/manga/demo")
		assertEquals(555L, helper.extractPostId(doc))
		assertEquals("wpnonce77", helper.extractRestNonce(doc))

		val restPage = helper.getComments(doc, "https://wpsite.test/manga/demo", page = 1, sort = SourceCommentSort.NEWEST)
		assertEquals(SourceCommentEngine.WORDPRESS, restPage.engine)
		assertTrue(restPage.hasNextPage)
		assertEquals(30, restPage.totalCount)
		assertEquals(1, restPage.comments.size)
		assertEquals("REST comment", restPage.comments[0].body)
		assertEquals(1, restPage.comments[0].replies.size)
		assertEquals("REST reply", restPage.comments[0].replies[0].body)

		val postedRest = helper.postComment(doc, "https://wpsite.test/manga/demo", text = "Posted REST")
		assertEquals("99", postedRest.id)
		assertEquals("Posted REST", postedRest.body)

		// Fallback to HTML scraping & form post when REST API is disabled
		allowRest = false
		val htmlPage = helper.getComments(doc, "https://wpsite.test/manga/demo", page = 1)
		assertEquals(1, htmlPage.comments.size)
		assertEquals("Ali", htmlPage.comments[0].authorName)
		assertEquals(1, htmlPage.comments[0].replies.size)
		assertEquals("Veli", htmlPage.comments[0].replies[0].authorName)

		val postedForm = helper.postComment(doc, "https://wpsite.test/manga/demo", text = "Form comment")
		assertEquals("Form comment", postedForm.body)
		assertTrue(harness.recorded.any { it.url.endsWith("/wp-comments-post.php") })
	}

	@Test
	fun testWpDiscuzParsingAndAjaxActions() = runTest {
		val wpdHtml = """
			<html>
			<head>
				<script>
				var wpdiscuzAjaxObj = {"url":"https://wpd.test/wp-admin/admin-ajax.php","wpdiscuz_nonce":"n_abc123","wc_post_id":"321","phrases":{"more":"Load more"}};
				</script>
			</head>
			<body>
				<div id="wpdcom" data-post-id="321">
					<div class="wpd-thread-info" data-comments-count="12"></div>
					<div class="wpd-thread-list">
						<div id="wpd-comm-10_0" class="wpd-comment wpd_comment_level-1">
							<div class="wpd-comment-wrap">
								<div class="wpd-comment-header">
									<div class="wpd-comment-author">Mert</div>
									<div class="wpd-comment-label">Member</div>
									<div class="wpd-comment-date" data-date="2026-11-10T10:00:00Z">2 days ago</div>
								</div>
								<div class="wpd-comment-text"><p>İlk yorum</p></div>
								<div class="wpd-comment-footer">
									<span class="wpd-vote-result">4</span>
									<div class="wpd-vote-up wpd-voted"></div>
									<div class="wpd-comment-edit-button"></div>
									<div class="wpd-comment-delete-button"></div>
								</div>
							</div>
							<div id="wpd-comm-11_10" class="wpd-comment wpd_comment_level-2">
								<div class="wpd-comment-wrap">
									<div class="wpd-comment-author">Ece</div>
									<div class="wpd-comment-text"><p>Yanıt</p></div>
								</div>
							</div>
						</div>
					</div>
					<button class="wpd-load-more-submit" data-lastparentid="10">More</button>
				</div>
			</body>
			</html>
		""".trimIndent()

		val harness = MockHttpHarness { _, body ->
			when {
				"action=wpdLoadMoreComments" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"comment_list":"<div id='wpd-comm-12_0' class='wpd-comment'><div class='wpd-comment-wrap'><div class='wpd-comment-author'>Can</div><div class='wpd-comment-text'>Page 2</div></div></div>","is_show_load_more":false}}""",
				)
				"action=wpdAddComment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"new_comment_id":"55","message":"<div id='wpd-comm-55_0' class='wpd-comment'><div class='wpd-comment-wrap'><div class='wpd-comment-author'>Mert</div><div class='wpd-comment-text'>Yeni WPD</div></div></div>"}}""",
				)
				"action=wpdVoteOnComment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"votes":5}}""",
				)
				"action=wpdEditComment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"message":"ok"}}""",
				)
				"action=wpdDeleteComment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{}}""",
				)
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}

		val helper = WpDiscuzCommunityHelper(harness.context, harness.webClient, harness.source, { "wpd.test" })
		val doc = Jsoup.parse(wpdHtml, "https://wpd.test/manga/x")

		val cfg = helper.extractConfig(doc)
		assertNotNull(cfg)
		assertEquals("321", cfg!!.postId)
		assertEquals("n_abc123", cfg.nonce)
		assertEquals(12, cfg.commentsCount)

		val page1 = helper.getComments(doc, "https://wpd.test/manga/x", page = 1)
		assertEquals(SourceCommentEngine.WPDISCUZ, page1.engine)
		assertTrue(page1.hasNextPage)
		assertEquals("10", page1.nextCursor)
		assertEquals(1, page1.comments.size)
		assertEquals("Mert", page1.comments[0].authorName)
		assertEquals(4, page1.comments[0].likesCount)
		assertEquals(1, page1.comments[0].userVote)
		assertTrue(page1.comments[0].canEdit)
		assertTrue(page1.comments[0].canDelete)
		assertEquals(1, page1.comments[0].replies.size)
		assertEquals("Ece", page1.comments[0].replies[0].authorName)

		val page2 = helper.getComments(doc, "https://wpd.test/manga/x", page = 2, cursor = "10")
		assertFalse(page2.hasNextPage)
		assertEquals("Can", page2.comments[0].authorName)

		val added = helper.postComment(doc, "https://wpd.test/manga/x", "Yeni WPD")
		assertEquals("55", added.id)

		val voted = helper.toggleCommentLike(doc, "https://wpd.test/manga/x", page1.comments[0], isLike = true)
		assertEquals(5, voted.likesCount)

		val edited = helper.editComment(doc, "https://wpd.test/manga/x", added, "Edited WPD")
		assertEquals("Edited WPD", edited.body)
		assertTrue(edited.isEdited)

		helper.deleteComment(doc, "https://wpd.test/manga/x", edited)
		assertTrue(harness.recorded.any { "action=wpdDeleteComment" in it.body })
	}

	@Test
	fun testWidgetDetectorsDisqusGraphCommentHyvorCusdis() {
		val disqusHtml = """
			<html>
			<head><title>Solo Series</title></head>
			<body>
				<div id="disqus_thread"></div>
				<script>
					var disqus_config = function () {
						this.page.url = 'https://site.test/manga/solo';
						this.page.identifier = 'solo-42';
					};
					var s = document.createElement('script');
					s.src = 'https://my-manga-site.disqus.com/embed.js';
				</script>
			</body>
			</html>
		""".trimIndent()
		val disqusCtx = DisqusDetector.detect(Jsoup.parse(disqusHtml, "https://site.test/manga/solo"), "https://site.test/manga/solo")
		assertNotNull(disqusCtx)
		assertEquals(SourceCommentEngine.DISQUS, disqusCtx!!.engine)
		assertEquals("my-manga-site", disqusCtx.shortname)
		assertEquals("solo-42", disqusCtx.identifier)
		assertEquals("https://my-manga-site.disqus.com/embed.js", disqusCtx.widgetUrl)
		assertEquals("https://site.test/manga/solo", disqusCtx.baseUrl)
		assertTrue(disqusCtx.inlineHtml?.contains("my-manga-site.disqus.com/embed.js") == true)
		assertTrue("disqus.com" in disqusCtx.allowedHostSuffixes)

		val gcHtml = """
			<html><head><title>GC Manga</title></head><body>
			<div id="graphcomment"></div>
			<script>
			var gc_params = { graphcomment_id: 'gc_site_99', uid: 'manga-99' };
			</script>
			<script src="https://graphcomment.com/js/integration.js"></script>
			</body></html>
		""".trimIndent()
		val gcCtx = GraphCommentDetector.detect(Jsoup.parse(gcHtml, "https://site.test/manga/gc"), "https://site.test/manga/gc")
		assertNotNull(gcCtx)
		assertEquals(SourceCommentEngine.GRAPHCOMMENT, gcCtx!!.engine)
		assertEquals("gc_site_99", gcCtx.shortname)
		assertEquals("manga-99", gcCtx.identifier)
		assertTrue(gcCtx.inlineHtml?.contains("gc_site_99") == true)
		assertTrue("graphcomment.com" in gcCtx.allowedHostSuffixes)

		val hyvorHtml = """<html><body><hyvor-talk-view website-id="1234" page-id="p-9"></hyvor-talk-view></body></html>"""
		val hyvorCtx = HyvorTalkDetector.detect(Jsoup.parse(hyvorHtml, "https://site.test/p"), "https://site.test/p")
		assertNotNull(hyvorCtx)
		assertEquals(SourceCommentEngine.HYVOR, hyvorCtx!!.engine)
		assertEquals("1234", hyvorCtx.shortname)

		val cusdisHtml = """<html><body><div id="cusdis_thread" data-app-id="app-1" data-page-id="p-2" data-page-url="https://site.test/p"></div></body></html>"""
		val cusdisCtx = CusdisDetector.detect(Jsoup.parse(cusdisHtml, "https://site.test/p"), "https://site.test/p")
		assertNotNull(cusdisCtx)
		assertEquals(SourceCommentEngine.CUSDIS, cusdisCtx!!.engine)
		assertEquals("app-1", cusdisCtx.shortname)
	}

	@Test
	fun testMadaraCommunityHelperStatsRatingAndEngineRouting() = runTest {
		val madaraDisqusHtml = """
			<html>
			<head><title>Madara Manga</title></head>
			<body class="postid-888">
				<input type="hidden" class="rating-post-id" value="888">
				<div class="post-total-rating">
					<span id="averagerate" class="score">4.3</span>
					<span id="countrate" class="total_votes">95</span>
				</div>
				<div class="post-content_item">
					<div class="summary-heading"><h5>Görüntülenme</h5></div>
					<div class="summary-content">24.5K</div>
				</div>
				<div class="post-content_item">
					<div class="summary-heading"><h5>Bookmark</h5></div>
					<div class="summary-content">1,250 users bookmarked</div>
				</div>
				<div id="disqus_thread"></div>
				<script>var disqus_shortname = 'madara-tr';</script>
			</body>
			</html>
		""".trimIndent()

		val harness = MockHttpHarness { req, body ->
			when {
				req.url.encodedPath == "/manga/madara-test/" ->
					MockHttpHarness.ResponseSpec(200, madaraDisqusHtml, "text/html")
				req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=wp-manga-save-rating" in body ->
					MockHttpHarness.ResponseSpec(200, """{"success":true,"data":{"rating":4.5,"votes":96}}""")
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}

		val helper = MadaraCommunityHelper(
			context = harness.context,
			webClient = harness.webClient,
			source = harness.source,
			domainProvider = { "madara.test" },
		)
		val manga = dummyManga("/manga/madara-test/")

		val info = helper.getMangaCommunityInfo(manga)
		assertEquals(4.3f, info.rawRating)
		assertEquals(4.3f / 5f, info.averageRating)
		assertEquals(95, info.ratingCount)
		assertEquals(24500L, info.viewCount)
		assertEquals(1250L, info.followCount)
		assertTrue(info.canCurrentUserRate)
		assertTrue(info.canCurrentUserComment)

		val updated = helper.submitRating(manga, 5)
		assertEquals(4.5f, updated.rawRating)
		assertEquals(96, updated.ratingCount)
		assertEquals(5, updated.userRating)
		val ratingReq = harness.recorded.last { it.url.endsWith("/wp-admin/admin-ajax.php") }
		assertTrue("star=5" in ratingReq.body)
		assertTrue("postID=888" in ratingReq.body)

		val commentsPage = helper.getComments(manga)
		assertEquals(SourceCommentEngine.DISQUS, commentsPage.engine)
		assertTrue(commentsPage.comments.isEmpty())
		assertEquals("madara-tr", commentsPage.widgetContext?.shortname)

		val widget = helper.resolveWidgetContext(manga)
		assertEquals(SourceCommentEngine.DISQUS, widget?.engine)
	}

	@Test
	fun testTrWebtoonAuthStatsRatingAndComments() = runTest {
		val seriesHtml = """
			<html>
			<body>
				<div class="wt-stats">
					<span data-rv2-avg>4.8</span>
					<span data-rv2-hero-sub>312 oy</span>
					<span class="wt-stat-label">Okunma</span><strong>45.2K</strong>
					<span data-bm-count>1,234</span>
				</div>
				<div id="comments-section" data-target-type="webtoon" data-target-id="77"></div>
				<script>
					window.TRM = {"logged":true,"username":"ReaderTR","token":"tok-123","page":{"webtoon":{"id":77}}};
				</script>
			</body>
			</html>
		""".trimIndent()
		val commentsJson = """
			{
			  "success": true,
			  "data": {
			    "comments": [
			      {
			        "id": 501,
			        "username": "Ayse",
			        "content": "Harika [spoiler]final[/spoiler]",
			        "created_at": "2026-11-12T10:00:00Z",
			        "like_count": 9,
			        "user_liked": false,
			        "is_own_comment": true,
			        "replies": []
			      }
			    ],
			    "pagination": {
			      "total_comments": 1,
			      "has_next": false
			    }
			  }
			}
		""".trimIndent()
		val harness = MockHttpHarness { req, _ ->
			when (req.url.encodedPath) {
				"/panel/giris", "/" -> MockHttpHarness.ResponseSpec(200, seriesHtml, "text/html")
				"/webtoon/test-seri" -> MockHttpHarness.ResponseSpec(200, seriesHtml, "text/html")
				"/api/main" -> MockHttpHarness.ResponseSpec(
					200,
					"""{"type":"success","avg":4.9,"count":313,"vote":5}""",
				)
				"/api/comments.php" -> when (req.url.queryParameter("action")) {
					"list" -> MockHttpHarness.ResponseSpec(200, commentsJson)
					"get_token" -> MockHttpHarness.ResponseSpec(200, """{"success":true,"token":"tok-123"}""")
					else -> MockHttpHarness.ResponseSpec(200, """{"success":true}""")
				}
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}
		val parser = TrWebtoon(harness.context)
		assertEquals("https://trmanga.com/panel/giris", parser.authUrl)
		assertEquals("https://trmanga.com/panel/kayit", parser.signUpUrl)
		assertTrue(parser.isAuthorized())
		val manga = dummyManga("/webtoon/test-seri", MangaParserSource.TRWEBTOON)
		val info = parser.getMangaCommunityInfo(manga)
		assertEquals(4.8f, info.rawRating)
		assertEquals(5, info.rawScale)
		assertEquals(312, info.ratingCount)
		val updated = parser.submitRating(manga, 5)
		assertEquals(4.9f, updated.rawRating)
		val comments = parser.getComments(manga)
		assertEquals(1, comments.comments.size)
		assertEquals("Ayse", comments.comments.first().authorName)
	}

	@Test
	fun testMangaDexCommunityStatsRatingAndAuth() = runTest {
		val mangaUuid = "32d76d19-8a05-4db0-9fc2-e0b0648fe9d0"
		val statsJson = """
			{
			  "result": "ok",
			  "statistics": {
			    "$mangaUuid": {
			      "comments": { "threadId": 98765, "repliesCount": 42 },
			      "rating": {
			        "average": 8.95,
			        "bayesian": 8.91,
			        "distribution": { "9": 100, "10": 200 }
			      },
			      "follows": 55000
			    }
			  }
			}
		""".trimIndent()
		val harness = MockHttpHarness { req, body ->
			when {
				req.url.encodedPath == "/statistics/manga/$mangaUuid" ->
					MockHttpHarness.ResponseSpec(200, statsJson)
				req.url.encodedPath == "/rating/$mangaUuid" && req.method == "POST" -> {
					assertTrue(req.header("Authorization")?.startsWith("Bearer ") == true)
					assertTrue(""""rating":9""" in body)
					MockHttpHarness.ResponseSpec(200, """{"result":"ok"}""")
				}
				req.url.encodedPath == "/rating" ->
					MockHttpHarness.ResponseSpec(200, """{"result":"ok","ratings":{"$mangaUuid":{"rating":9}}}""")
				req.url.encodedPath == "/user/me" ->
					MockHttpHarness.ResponseSpec(200, """{"result":"ok","data":{"attributes":{"username":"DexUser"}}}""")
				else -> MockHttpHarness.ResponseSpec(404, """{"result":"error"}""")
			}
		}
		harness.context.setAuthValue(MangaParserSource.MANGADEX, "access_token", "valid-jwt-token")
		harness.context.setAuthValue(MangaParserSource.MANGADEX, "expires_at", (System.currentTimeMillis() + 600_000L).toString())
		harness.context.setAuthValue(MangaParserSource.MANGADEX, "username", "DexUser")

		val parser = MangaDexParser(harness.context)
		assertTrue(parser.authUrl.contains("auth.mangadex.org"))
		assertTrue(parser.signUpUrl?.contains("registrations") == true)
		assertEquals("DexUser", parser.getUsername())

		val manga = dummyManga(mangaUuid, MangaParserSource.MANGADEX)
		val info = parser.getMangaCommunityInfo(manga)
		assertEquals(8.91f, info.rawRating)
		assertEquals(10, info.rawScale)
		assertEquals(300, info.ratingCount)
		assertEquals(55000L, info.followCount)
		assertEquals(42, info.commentCount)
		assertEquals(9, info.userRating)

		val rated = parser.submitRating(manga, 9)
		assertEquals(9, rated.userRating)
		val widget = parser.resolveWidgetContext(manga)
		assertEquals("https://forums.mangadex.org/threads/98765/", widget?.pageUrl)
	}

	@Test
	fun testQiScansAndMangaBallCommunityContracts() = runTest {
		val mbId = "681234567890abcdef123456"
		val harness = MockHttpHarness { req, body ->
			when {
				req.url.host == "api.qimanhwa.com" && req.url.encodedPath == "/api/v1/series/qi-test" ->
					MockHttpHarness.ResponseSpec(200, """{"stats":{"averageRating":4.7,"reviewCount":40,"commentCount":15}}""")
				req.url.host == "api.qimanhwa.com" && req.url.encodedPath == "/api/v1/series/qi-test/comments" ->
					MockHttpHarness.ResponseSpec(200, """{"data":[{"id":"c1","content":"Nice!","author":{"username":"QiFan"},"upvotes":4,"downvotes":0,"replyCount":0}],"totalPages":1}""")
				req.url.host == "mangaball.com" && req.url.encodedPath == "/api/v1/title/detail/$mbId" ->
					MockHttpHarness.ResponseSpec(200, """{"data":{"stats":{"views":12000,"followers":340},"ratings":{"rating_average":9.1,"total_rating":88}}}""")
				req.url.host == "mangaball.com" && req.url.encodedPath == "/api/v1/comment/load-comments" -> {
					assertTrue(mbId in body)
					MockHttpHarness.ResponseSpec(200, """{"data":[{"id":"m1","user_name":"BallUser","content":"Peak","stats":{"likes":3}}],"pagination":{"total":1,"page":1,"total_pages":1}}""")
				}
				else -> MockHttpHarness.ResponseSpec(404, "{}")
			}
		}
		val qi = QiScans(harness.context)
		val qiManga = dummyManga("qi-test", MangaParserSource.QISCANS)
		val qiInfo = qi.getMangaCommunityInfo(qiManga)
		assertEquals(4.7f, qiInfo.rawRating)
		assertEquals(5, qiInfo.rawScale)
		val qiComments = qi.getComments(qiManga)
		assertEquals(1, qiComments.comments.size)
		assertEquals("QiFan", qiComments.comments.first().authorName)

		val mb = MangaBallParser.Turkish(harness.context)
		val mbManga = dummyManga("sample-$mbId", MangaParserSource.MANGABALL_TR)
		val mbInfo = mb.getMangaCommunityInfo(mbManga)
		assertEquals(9.1f, mbInfo.rawRating)
		assertEquals(10, mbInfo.rawScale)
		val mbComments = mb.getComments(mbManga)
		assertEquals(1, mbComments.comments.size)
		assertEquals("BallUser", mbComments.comments.first().authorName)
	}

	@Test
	fun testOkutoonHoneypotRetryContentPolicyAndNonAuth403() = runTest {
		val pageHtml = """
			<html>
			<head><meta name="csrf-token" content="csrf-99"></head>
			<body>
				<div id="comments-section" data-current-user-id="10" data-comment-action="https://okutoon.com/comments">
					<form class="comment-form">
						<input type="hidden" name="_hp_token" value="hp_ok_1">
						<input type="hidden" name="_hp_t" value="1700000000">
						<input type="text" class="auth-honeypot" name="website_url" value="">
						<input type="checkbox" name="content_policy" value="1">
					</form>
				</div>
			</body>
			</html>
		""".trimIndent()
		var submitAttempts = 0
		var returnPolicy403 = false
		val harness = MockHttpHarness { req, body ->
			when (req.url.encodedPath) {
				"/manga/test-series" -> MockHttpHarness.ResponseSpec(200, pageHtml, "text/html")
				"/comments" -> {
					if (returnPolicy403) {
						MockHttpHarness.ResponseSpec(403, """{"error":"İçerik politikası ihlali tespit edildi"}""")
					} else {
						submitAttempts++
						assertTrue(""""website_url":"""" in body)
						assertTrue(""""content_policy":"1"""" in body)
						assertTrue(""""content_policy_accepted":true""" in body)
						assertTrue(""""_hp_token":"hp_ok_1"""" in body)
						if (submitAttempts == 1) {
							MockHttpHarness.ResponseSpec(422, """{"error":"Lütfen en az 3 saniye bekleyin"}""")
						} else {
							MockHttpHarness.ResponseSpec(
								200,
								"""{"comment":{"id":901,"body":"<blockquote class=\"spoiler\">Hidden&lt;ok&gt;</blockquote>","user":{"id":10,"username":"cagan"},"created_at":"2026-11-13T10:00:00Z"}}""",
							)
						}
					}
				}
				else -> MockHttpHarness.ResponseSpec(404, "{}")
			}
		}
		val helper = OkutoonCommunityHelper(harness.context, harness.webClient, MangaParserSource.OKUTOON, { "okutoon.com" })
		val manga = dummyManga("/manga/test-series", MangaParserSource.OKUTOON)

		val posted = helper.postComment(manga, "Hidden<ok>", isSpoiler = true)
		assertEquals(2, submitAttempts)
		assertEquals("901", posted.id)
		assertTrue(posted.isSpoiler)

		returnPolicy403 = true
		var threwParseException = false
		try {
			helper.postComment(manga, "Blocked text")
		} catch (e: ParseException) {
			threwParseException = true
			assertTrue(e.message?.contains("politika") == true)
		}
		assertTrue(threwParseException)
	}

	@Test
	fun testMerlinCommentsHelperLifecycle() = runTest {
		val html = """
			<html>
			<head>
				<script>
				var MerlinComments = {"ajaxurl":"https://merlin.test/wp-admin/admin-ajax.php","nonce":"n_m1","post_id":"456","user_id":"9","is_logged_in":"1"};
				</script>
			</head>
			<body>
				<div id="merlin-comments-section" data-post-id="456">
					<span id="mcp-total-count">12</span>
				</div>
			</body>
			</html>
		""".trimIndent()
		val harness = MockHttpHarness { _, body ->
			when {
				"action=mcp_load_comments" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"total":12,"pages":2,"current_page":1,"user_votes":{"77":"like"},"comments":[{"id":77,"user_id":9,"display_name":"Mert","content_html":"<p>Selam</p>","has_spoiler":1,"likes_count":5,"dislikes_count":1,"replies":[{"id":78,"parent_id":77,"user_id":10,"display_name":"Ece","content":"Yanıt"}]}]}}""",
				)
				"action=mcp_submit_comment" in body -> {
					assertTrue("has_spoiler=1" in body)
					MockHttpHarness.ResponseSpec(
						200,
						"""{"success":true,"data":{"comment":{"id":79,"user_id":9,"display_name":"Mert","content":"Spoiler test","has_spoiler":1}}}""",
					)
				}
				"action=mcp_edit_comment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"content_html":"<p>Updated</p>"}}""",
				)
				"action=mcp_vote" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{"likes_count":6,"dislikes_count":1}}""",
				)
				"action=mcp_delete_comment" in body -> MockHttpHarness.ResponseSpec(
					200,
					"""{"success":true,"data":{}}""",
				)
				else -> MockHttpHarness.ResponseSpec(404, "{}")
			}
		}
		val helper = MerlinCommentsHelper(harness.context, MangaParserSource.NABISCANS, { "merlin.test" })
		val doc = Jsoup.parse(html, "https://merlin.test/manga/demo")
		assertTrue(helper.isMerlinPresent(doc))
		assertEquals(12, helper.extractTotalCount(doc))

		val page = helper.getComments(doc, "https://merlin.test/manga/demo", page = 1, sort = SourceCommentSort.TOP)
		assertEquals(SourceCommentEngine.NATIVE, page.engine)
		assertTrue(page.hasNextPage)
		assertEquals(12, page.totalCount)
		assertEquals(1, page.comments.size)
		assertEquals(1, page.comments[0].userVote)
		assertTrue(page.comments[0].isSpoiler)
		assertTrue(page.comments[0].isOwnComment)
		assertEquals(1, page.comments[0].replies.size)

		val posted = helper.postComment(doc, "https://merlin.test/manga/demo", "Spoiler test", isSpoiler = true)
		assertEquals("79", posted.id)
		assertTrue(posted.isSpoiler)

		val edited = helper.editComment(doc, "https://merlin.test/manga/demo", posted, "Updated")
		assertEquals("Updated", edited.body)
		assertTrue(edited.isEdited)

		val voted = helper.toggleCommentLike(doc, "https://merlin.test/manga/demo", page.comments[0], isLike = true)
		assertEquals(6, voted.likesCount)
		helper.deleteComment(doc, "https://merlin.test/manga/demo", posted)
	}

	@Test
	fun testWidgetDetectorsLiteSpeedBase64AndCamelCaseGraphComment() {
		val disqusPayload = Base64.getEncoder().encodeToString(
			"""var disqus_shortname = "gafeland-wp"; var disqus_identifier = "gaf-101";""".toByteArray(),
		)
		val litespeedHtml = """
			<html><body>
				<div id="disqus_thread"></div>
				<script src="data:text/javascript;base64,$disqusPayload"></script>
			</body></html>
		""".trimIndent()
		val disqusCtx = WidgetDetectors.detect(Jsoup.parse(litespeedHtml, "https://gafeland.test/manga/abc"), "https://gafeland.test/manga/abc")
		assertNotNull(disqusCtx)
		assertEquals(SourceCommentEngine.DISQUS, disqusCtx!!.engine)
		assertEquals("gafeland-wp", disqusCtx.shortname)
		assertEquals("gaf-101", disqusCtx.identifier)

		val gcHtml = """
			<html><body>
				<div id="graphcomment"></div>
				<script>var __semio__params = { graphcommentId: 'tortugaceviri-com' };</script>
			</body></html>
		""".trimIndent()
		val gcCtx = WidgetDetectors.detect(Jsoup.parse(gcHtml, "https://tortuga.test/manga/xyz"), "https://tortuga.test/manga/xyz")
		assertNotNull(gcCtx)
		assertEquals(SourceCommentEngine.GRAPHCOMMENT, gcCtx!!.engine)
		assertEquals("tortugaceviri-com", gcCtx.shortname)
	}

	@Test
	fun testGolgebahcesiMangaHereAndMangaBuffCommunity() = runTest {
		val harness = MockHttpHarness { req, body ->
			when {
				req.url.host == "api.golgebahcesi.com" && req.url.encodedPath == "/api/auth/me" ->
					MockHttpHarness.ResponseSpec(200, """{"id":"u1","username":"gb_user","displayName":"GB User"}""")
				req.url.host == "api.golgebahcesi.com" && req.url.encodedPath == "/api/series/demo-slug" ->
					MockHttpHarness.ResponseSpec(200, """{"id":"s1","rating":9.2,"ratingCount":15,"viewCount":500,"followerCount":80}""")
				req.url.host == "api.golgebahcesi.com" && req.url.encodedPath == "/api/series/s1/my-rating" ->
					MockHttpHarness.ResponseSpec(200, """{"userRating":9}""")
				req.url.host == "api.golgebahcesi.com" && req.url.encodedPath == "/api/series/s1/rate" -> {
					assertTrue(""""rating":10""" in body)
					MockHttpHarness.ResponseSpec(200, """{"userRating":10,"newRating":9.3,"ratingCount":16}""")
				}
				req.url.host == "api.golgebahcesi.com" && req.url.encodedPath == "/api/comments" && req.method == "GET" ->
					MockHttpHarness.ResponseSpec(200, """{"data":[{"id":"gc1","userId":"u1","userSnapshot":{"username":"gb_user","displayName":"GB User"},"content":"Harika seri","spoiler":true,"replyCount":0,"reactionCounts":{"like":4,"dislike":0},"isLiked":true,"isDisliked":false,"createdAt":"2026-11-10T12:00:00Z"}],"meta":{"hasMore":false,"total":1}}""")
				req.url.host == "www.mangahere.cc" && req.url.encodedPath == "/manga/demo_here/" ->
					MockHttpHarness.ResponseSpec(200, """<html><body><span class="item-score">4.8</span><script>var comicid = 11912;</script></body></html>""", "text/html")
				req.url.host == "www.mangahere.cc" && req.url.encodedPath == "/post.ashx" ->
					MockHttpHarness.ResponseSpec(200, "25", "text/plain")
				req.url.host == "www.mangahere.cc" && req.url.encodedPath == "/pagerdata.ashx" ->
					MockHttpHarness.ResponseSpec(200, """[{"Id":55,"Poster":"HereFan","HeadUrl":"","PostContent":"Awesome&#39;s","PostTime":"2026-11-10 05:40","Support":3}]""")
				req.url.host == "mangabuff.ru" && req.url.encodedPath == "/" ->
					MockHttpHarness.ResponseSpec(200, """<html><head><meta name="csrf-token" content="mb_csrf"></head><body><div class="header-profile"><span class="header-profile__name">BuffUser</span></div></body></html>""", "text/html")
				req.url.host == "mangabuff.ru" && req.url.encodedPath == "/manga/mb-demo" ->
					MockHttpHarness.ResponseSpec(
						200,
						"""<html><head><meta name="csrf-token" content="mb_csrf"></head><body><span class="manga__rating">9.5</span><div class="comments" data-type="manga" data-id="38317" data-count="25"><div class="comments__list"><div id="comment_101" class="comments__item" data-time="1700000000"><div class="comments__inner"><div class="comments__name">Ivan</div><div class="comments__body">First page</div><div class="comments__rating-number">7</div><button class="comments__edit-btn"></button></div></div></div></div></body></html>""",
						"text/html",
					)
				req.url.host == "mangabuff.ru" && req.url.encodedPath == "/comments/load" ->
					MockHttpHarness.ResponseSpec(200, """{"comments":"<div id='comment_102' class='comments__item'><div class='comments__inner'><div class='comments__name'>Olga</div><div class='comments__body'>Loaded batch</div></div></div>"}""")
				req.url.host == "mangabuff.ru" && req.url.encodedPath == "/comments" && req.method == "POST" ->
					MockHttpHarness.ResponseSpec(200, """{"comment":"<div id='comment_103' class='comments__item'><div class='comments__inner'><div class='comments__name'>BuffUser</div><div class='comments__body'>New MB</div><button class='comments__edit-btn'></button></div></div>"}""")
				req.url.host == "mangabuff.ru" && req.url.encodedPath == "/comments/vote" ->
					MockHttpHarness.ResponseSpec(200, """{"rating":{"number":"8","type":"like"}}""")
				else -> MockHttpHarness.ResponseSpec(404, "{}")
			}
		}

		val gb = Golgebahcesi(harness.context)
		val gbManga = dummyManga("/manga/demo-slug", MangaParserSource.GOLGEBAHCESI)
		val gbInfo = gb.getMangaCommunityInfo(gbManga)
		assertEquals(9.2f, gbInfo.rawRating)
		assertEquals(10, gbInfo.rawScale)
		assertEquals(9, gbInfo.userRating)
		val gbRated = gb.submitRating(gbManga, 10)
		assertEquals(10, gbRated.userRating)
		val gbComments = gb.getComments(gbManga)
		assertEquals(1, gbComments.comments.size)
		assertTrue(gbComments.comments.first().isSpoiler)
		assertTrue(gbComments.comments.first().isOwnComment)

		val mh = MangaHere(harness.context)
		val mhManga = dummyManga("/manga/demo_here/", MangaParserSource.MANGAHERE)
		val mhInfo = mh.getMangaCommunityInfo(mhManga)
		assertEquals(4.8f, mhInfo.rawRating)
		assertEquals(5, mhInfo.rawScale)
		assertEquals(25, mhInfo.commentCount)
		val mhComments = mh.getComments(mhManga)
		assertEquals(1, mhComments.comments.size)
		assertEquals("Awesome's", mhComments.comments.first().body)

		val mb = MangaBuff(harness.context)
		val mbManga = dummyManga("/manga/mb-demo", MangaParserSource.MANGABUFF)
		val mbPage1 = mb.getComments(mbManga, page = 1)
		assertEquals(1, mbPage1.comments.size)
		assertTrue(mbPage1.hasNextPage)
		assertTrue(mbPage1.comments.first().isOwnComment)
		val mbPage2 = mb.getComments(mbManga, page = 2, cursor = mbPage1.nextCursor)
		assertEquals(1, mbPage2.comments.size)
		assertEquals("Olga", mbPage2.comments.first().authorName)
		val mbPosted = mb.postComment(mbManga, "New MB")
		assertEquals("103", mbPosted.id)
		val mbVoted = mb.toggleCommentLike(mbManga, mbPage1.comments.first(), isLike = true)
		assertEquals(8, mbVoted.likesCount)
	}

	@Test
	fun testOkutoonBlankCurrentUserAndHomeAuthCheck() = runTest {
		val loggedOutSeriesHtml = """
			<html>
			<body data-current-username="">
				<a href="/@ItzSnowzy" class="comment-author">ItzSnowzy</a>
				<div class="comments-section" data-comment-action="/s/comment" data-comment-load-url="/s/comments" data-current-user-id=""></div>
			</body>
			</html>
		""".trimIndent()
		val loggedInHomeHtml = """
			<html>
			<body data-current-username="perfectus">
				<a href="/@Faxed" class="feed-user">Faxed</a>
			</body>
			</html>
		""".trimIndent()
		var loggedIn = false
		val harness = MockHttpHarness { req, _ ->
			when (req.url.encodedPath) {
				"/s" -> MockHttpHarness.ResponseSpec(200, loggedOutSeriesHtml, "text/html")
				"/" -> MockHttpHarness.ResponseSpec(200, if (loggedIn) loggedInHomeHtml else loggedOutSeriesHtml, "text/html")
				"/login" -> MockHttpHarness.ResponseSpec(403, "cf challenge", "text/html")
				else -> MockHttpHarness.ResponseSpec(404, "")
			}
		}
		val helper = OkutoonCommunityHelper(harness.context, harness.webClient, MangaParserSource.OKUTOON, { "okutoon.com" })
		val info = helper.getMangaCommunityInfo(dummyManga("/s"))
		assertEquals(null, info.currentUsername)
		assertTrue(!info.canCurrentUserComment)
		assertTrue(!helper.isAuthorized())

		loggedIn = true
		assertTrue(helper.isAuthorized())
		assertEquals("perfectus", helper.getUsername())
	}
}
