package org.koitharu.kotatsu.parsers.site.ko

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.InMemoryCookieJar
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.community.SourceCommentEngine
import org.koitharu.kotatsu.parsers.model.community.SourceCommentSort
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.newLinkResolver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class NaverComicParserTest {

	private data class RecordedCall(
		val method: String,
		val url: String,
		val headers: Map<String, String>,
		val body: String,
	)

	private data class Spec(
		val code: Int = 200,
		val body: String = "{}",
		val contentType: String = "application/json; charset=utf-8",
		val finalUrl: String? = null,
	)

	private class Harness(
		private val handler: (Request, String) -> Spec,
	) {
		val calls = CopyOnWriteArrayList<RecordedCall>()
		val authValues = ConcurrentHashMap<String, String>()
		val cookieJar = InMemoryCookieJar()

		val context: MangaLoaderContext = object : MangaLoaderContext() {
			override val cookieJar = this@Harness.cookieJar
			override val parsers: ParsersBundle
				get() = ParsersBundleImpl

			override val httpClient: OkHttpClient = OkHttpClient.Builder()
				.cookieJar(cookieJar)
				.addInterceptor { chain ->
					val req = chain.request()
					val buf = Buffer()
					req.body?.writeTo(buf)
					val bodyStr = buf.readUtf8()
					calls.add(
						RecordedCall(
							method = req.method,
							url = req.url.toString(),
							headers = req.headers.associate { it.first to it.second },
							body = bodyStr,
						),
					)
					val spec = handler(req, bodyStr)
					val responseRequest = if (spec.finalUrl != null) {
						req.newBuilder().url(spec.finalUrl).build()
					} else {
						req
					}
					Response.Builder()
						.request(responseRequest)
						.protocol(Protocol.HTTP_1_1)
						.code(spec.code)
						.message(if (spec.code in 200..299) "OK" else "Error")
						.body(spec.body.toResponseBody(spec.contentType.toMediaType()))
						.build()
				}
				.build()

			@Deprecated("Provide a base url")
			override suspend fun evaluateJs(script: String): String? = null
			override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
			override fun getConfig(source: MangaSource): MangaSourceConfig = SourceConfigMock()
			override fun getDefaultUserAgent(): String = UserAgents.CHROME_MOBILE
			override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
			override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
			override suspend fun getAuthValue(source: MangaSource, key: String): String? =
				authValues["${source.name}:$key"]
			override suspend fun setAuthValue(source: MangaSource, key: String, value: String?) {
				val k = "${source.name}:$key"
				if (value == null) authValues.remove(k) else authValues[k] = value
			}
			override suspend fun clearAuthValues(source: MangaSource) {
				val prefix = "${source.name}:"
				authValues.keys.removeIf { it.startsWith(prefix) }
			}
		}
	}

	@Test
	fun testListingSearchDetailsChaptersPagesAndLinkDisambiguation() = runTest {
		val harness = Harness { req, _ ->
			val path = req.url.encodedPath
			val query = req.url
			when {
				req.url.host == "comic.naver.com" && path == "/api/webtoon/titlelist/weekday" -> Spec(
					body = """
						{
							"titleListMap": {
								"MONDAY": [
									{
										"titleId": 183559,
										"titleName": "신의 탑",
										"author": "siu****",
										"writers": [{"id": 1, "name": "SIU"}],
										"painters": [{"id": 1, "name": "SIU"}],
										"thumbnailUrl": "https://image-comic.pstatic.net/webtoon/183559/thumb.jpg",
										"rest": false,
										"adult": false,
										"starScore": 9.8,
										"finish": false
									}
								],
								"TUESDAY": [
									{
										"titleId": 183559,
										"titleName": "신의 탑",
										"author": "siu****",
										"writers": [{"id": 1, "name": "SIU"}],
										"thumbnailUrl": "https://image-comic.pstatic.net/webtoon/183559/thumb.jpg",
										"rest": false,
										"adult": false,
										"starScore": 9.8,
										"finish": false
									}
								]
							}
						}
					""".trimIndent(),
				)

				req.url.host == "comic.naver.com" && path == "/api/webtoon/titlelist/finished" -> Spec(
					body = """
						{
							"pageInfo": {"totalPages": 1},
							"titleList": [
								{
									"titleId": 350217,
									"titleName": "완결작",
									"author": "작가A",
									"thumbnailUrl": "https://image-comic.pstatic.net/webtoon/350217/thumb.jpg",
									"rest": false,
									"adult": false,
									"starScore": 9.0,
									"finish": true
								}
							]
						}
					""".trimIndent(),
				)

				req.url.host == "comic.naver.com" && path == "/api/bestChallenge/list" -> Spec(
					body = """
						{
							"pageInfo": {"totalPages": 1},
							"list": [
								{
									"titleId": 842915,
									"titleName": "벼락맞을 식혜",
									"author": "gyes****",
									"writers": [{"id": 327108, "name": "호박식혜"}],
									"painters": [],
									"thumbnailUrl": "https://shared-comic.pstatic.net/thumb/842915.jpg",
									"rest": false,
									"adult": false,
									"starScore": 9.4,
									"finish": false
								}
							]
						}
					""".trimIndent(),
				)

				req.url.host == "comic.naver.com" && path == "/api/challenge/list" -> Spec(
					body = """
						{
							"list": [
								{
									"titleId": 853017,
									"titleName": "에드워드의 모험",
									"author": "ddal****",
									"writers": [{"id": 411190, "name": "딸기작가"}],
									"thumbnailUrl": "https://image-comic.pstatic.net/853017.jpg",
									"rest": false,
									"adult": false,
									"starScore": 9.1,
									"finish": false
								}
							]
						}
					""".trimIndent(),
				)

				req.url.host == "comic.naver.com" && path == "/api/search/webtoon" -> {
					val p = query.queryParameter("page")?.toIntOrNull() ?: 1
					if (p > 1) {
						Spec(body = """{"pageInfo":{"totalPages":1},"searchList":[]}""")
					} else {
						Spec(
							body = """
								{
									"pageInfo": {"totalPages": 1},
									"searchList": [
										{
											"titleId": 183559,
											"titleName": "신의 탑",
											"displayAuthor": "SIU",
											"communityArtists": [{"name": "SIU"}],
											"synopsis": "탑을 오르는 이야기",
											"thumbnailUrl": "https://image-comic.pstatic.net/webtoon/183559/thumb.jpg",
											"finished": false,
											"adult": false,
											"genreList": [{"type": "FANTASY", "description": "판타지"}]
										}
									]
								}
							""".trimIndent(),
						)
					}
				}

				req.url.host == "comic.naver.com" && path == "/api/article/list/info" -> {
					when (query.queryParameter("titleId")) {
						"183559" -> Spec(
							body = """
								{
									"titleId": 183559,
									"titleName": "신의 탑",
									"synopsis": "시놉시스",
									"posterThumbnailUrl": "https://image-comic.pstatic.net/webtoon/183559/poster.jpg",
									"thumbnailUrl": "https://image-comic.pstatic.net/webtoon/183559/thumb.jpg",
									"rest": true,
									"finished": false,
									"favoriteCount": 1888428,
									"age": {"type": "RATE_12"},
									"communityArtists": [{"name": "SIU"}],
									"curationTagList": [
										{"id": 183559, "tagName": "판타지", "curationType": "GENRE_FANTASY"},
										{"id": 428, "tagName": "탑등반", "curationType": "CUSTOM_TAG"}
									]
								}
							""".trimIndent(),
						)
						"842915" -> Spec(
							body = """
								{
									"titleId": 842915,
									"titleName": "벼락맞을 식혜",
									"synopsis": "베도 소개",
									"thumbnailUrl": "https://image-comic.pstatic.net/842915.jpg",
									"rest": false,
									"finished": false,
									"age": {"type": "ALL"},
									"communityArtists": [{"name": "호박식혜"}],
									"genres": [{"type": "DAILY", "description": "일상"}],
									"challengeTagList": ["일상", "개그"]
								}
							""".trimIndent(),
						)
						else -> Spec(
							body = """
								{
									"titleId": 853017,
									"titleName": "에드워드의 모험",
									"synopsis": "도전만화 소개",
									"thumbnailUrl": "https://image-comic.pstatic.net/853017.jpg",
									"rest": false,
									"finished": false,
									"age": {"type": "ALL"},
									"communityArtists": [{"name": "딸기작가"}]
								}
							""".trimIndent(),
						)
					}
				}

				req.url.host == "comic.naver.com" && path == "/api/article/list" -> {
					val tid = query.queryParameter("titleId")
					val page = query.queryParameter("page")?.toIntOrNull() ?: 1
					if (tid == "844058") {
						Spec(code = 401, body = """{"message":"Authorization required"}""")
					} else if (tid == "183559" && page == 1) {
						Spec(
							body = """
								{
									"pageInfo": {"totalPages": 2},
									"articleList": [
										{"no": 1, "subtitle": "1화", "starScore": 9.8, "charge": false, "serviceDateDescription": "10.06.30", "thumbnailLock": false}
									]
								}
							""".trimIndent(),
						)
					} else if (tid == "183559" && page == 2) {
						Spec(
							body = """
								{
									"pageInfo": {"totalPages": 2},
									"articleList": [
										{"no": 2, "subtitle": "2화 유료", "starScore": 9.6, "charge": true, "serviceDateDescription": "10.07.07", "thumbnailLock": true}
									]
								}
							""".trimIndent(),
						)
					} else {
						Spec(
							body = """
								{
									"pageInfo": {"totalPages": 1},
									"articleList": [
										{"no": 1, "subtitle": "프롤로그", "starScore": 9.4, "charge": false, "serviceDateDescription": "25.09.25", "thumbnailLock": false}
									]
								}
							""".trimIndent(),
						)
					}
				}

				req.url.host == "m.comic.naver.com" && path == "/webtoon/detail" -> Spec(
					contentType = "text/html; charset=utf-8",
					body = """
						<html><body>
						<div id="toonLayer">
							<img src="https://image-comic.pstatic.net/static/agerate/age_12_white.jpg" class="fx2" />
							<img src="https://ssl.pstatic.net/static/m/comic/im/2012/bg_transparency.png"
								 data-src="https://image-comic.pstatic.net/mobilewebimg/183559/1/p01.jpg"
								 id="toon_0" class="fx2 lazy toon_image" />
							<img src="https://ssl.pstatic.net/static/m/comic/im/2012/bg_transparency.png"
								 data-src="https://image-comic.pstatic.net/mobilewebimg/183559/1/p02.jpg"
								 id="toon_1" class="fx2 lazy toon_image" />
						</div>
						<img class="swiper-lazy" data-src="https://image-comic.pstatic.net/rec.jpg" />
						</body></html>
					""".trimIndent(),
				)

				req.url.host == "m.comic.naver.com" && path == "/bestChallenge/detail" -> Spec(
					contentType = "text/html; charset=utf-8",
					finalUrl = "https://nid.naver.com/nidlogin.login?url=https%3A%2F%2Fm.comic.naver.com%2F",
					body = "<html><body>로그인</body></html>",
				)

				else -> Spec(code = 404, body = "{}")
			}
		}

		val wt = NaverWebtoonParser(harness.context)
		val bc = NaverBestChallengeParser(harness.context)
		val ch = NaverChallengeParser(harness.context)

		// 1. Deduplicated weekday ongoing list + finished state filter + BC/Challenge author unmasking
		val wtList = wt.getListPage(1, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		assertEquals(1, wtList.size)
		assertEquals("신의 탑", wtList.first().title)
		assertEquals(setOf("SIU"), wtList.first().authors)
		assertEquals("/webtoon/list?titleId=183559", wtList.first().url)

		val wtFinished = wt.getListPage(1, SortOrder.RATING, MangaListFilter(states = setOf(MangaState.FINISHED)))
		assertEquals(1, wtFinished.size)
		assertEquals("완결작", wtFinished.first().title)
		assertEquals(MangaState.FINISHED, wtFinished.first().state)

		val bcList = bc.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)
		assertEquals(setOf("호박식혜"), bcList.first().authors) // Unmasked writer instead of "gyes****"
		val chList = ch.getListPage(1, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		assertEquals(setOf("딸기작가"), chList.first().authors)

		// Search page 1 + page 2 exhaustion
		assertEquals(1, wt.getListPage(1, SortOrder.POPULARITY, MangaListFilter(query = "신의 탑")).size)
		assertTrue(wt.getListPage(2, SortOrder.POPULARITY, MangaListFilter(query = "신의 탑")).isEmpty())

		// 2. Details + concurrent multi-page chapters + locked prefix + tags
		val details = wt.getDetails(wtList.first())
		assertEquals(MangaState.PAUSED, details.state)
		assertEquals(ContentRating.SAFE, details.contentRating)
		assertEquals(2, details.chapters?.size)
		assertEquals("1화", details.chapters?.get(0)?.title)
		assertEquals("🔒 2화 유료", details.chapters?.get(1)?.title)
		assertTrue(details.tags.any { it.key == "FANTASY" && it.title == "Fantasy" })
		assertTrue(details.tags.any { it.key == "CUSTOM_TAG:428" && it.title == "탑등반" })

		// 18+ locked episode list throws AuthRequiredException
		val adultStub = wtList.first().copy(url = "/webtoon/list?titleId=844058")
		assertThrows(AuthRequiredException::class.java) {
			kotlinx.coroutines.runBlocking { wt.getDetails(adultStub) }
		}

		// 3. Pages ignore agerate/placeholder/recs and redirect-to-login throws AuthRequiredException
		val pages = wt.getPages(details.chapters!!.first())
		assertEquals(
			listOf(
				"https://image-comic.pstatic.net/mobilewebimg/183559/1/p01.jpg",
				"https://image-comic.pstatic.net/mobilewebimg/183559/1/p02.jpg",
			),
			pages.map { it.url },
		)
		val lockedChapter = MangaChapter(
			id = 1L,
			title = "1",
			number = 1f,
			volume = 0,
			url = "/bestChallenge/detail?titleId=842915&no=1",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.NAVERBESTCHALLENGE,
		)
		assertThrows(AuthRequiredException::class.java) {
			kotlinx.coroutines.runBlocking { bc.getPages(lockedChapter) }
		}

		// 4. Shared domain LinkResolver disambiguation
		assertNull(wt.resolveLink(harness.context.newLinkResolver("https://m.comic.naver.com/bestChallenge/list?titleId=842915"), "https://m.comic.naver.com/bestChallenge/list?titleId=842915".toHttpUrl()))
		assertNull(bc.resolveLink(harness.context.newLinkResolver("https://comic.naver.com/webtoon/list?titleId=183559"), "https://comic.naver.com/webtoon/list?titleId=183559".toHttpUrl()))
		val resolvedBc = bc.resolveLink(
			harness.context.newLinkResolver("https://m.comic.naver.com/bestChallenge/list?titleId=842915"),
			"https://m.comic.naver.com/bestChallenge/list?titleId=842915".toHttpUrl(),
		)
		assertNotNull(resolvedBc)
		assertEquals("벼락맞을 식혜", resolvedBc?.title)
		assertTrue(resolvedBc!!.tags.any { it.key == "DAILY" && it.title == "Slice of Life" })
		assertTrue(resolvedBc.tags.any { it.key == "CHALLENGE_TAG:개그" })
		assertFalse(resolvedBc.tags.any { it.key == "CHALLENGE_TAG:일상" }) // Deduplicated against DAILY
		val fullResolvedBc = harness.context.resolveLink("https://m.comic.naver.com/bestChallenge/list?titleId=842915")
		assertEquals(MangaParserSource.NAVERBESTCHALLENGE, fullResolvedBc?.source)
		val fullResolvedCh = harness.context.resolveLink("https://comic.naver.com/challenge/list?titleId=853017")
		assertEquals(MangaParserSource.NAVERCHALLENGE, fullResolvedCh?.source)
		val fullResolvedWt = harness.context.resolveLink("https://comic.naver.com/webtoon/list?titleId=183559")
		assertEquals(MangaParserSource.NAVERWEBTOON, fullResolvedWt?.source)
	}

	@Test
	fun testWccCommunityCommentsRepliesReactionsAuthAndWidgetFallback() = runTest {
		val reactionCalls = ArrayList<Pair<String, String>>()
		var loggedOutCalled = false

		val harness = Harness { req, body ->
			val path = req.url.encodedPath
			val ticket = req.header("Service-Ticket-Id")
			when {
				path == "/api/article/list/info" -> Spec(
					body = """{"titleId":183559,"favoriteCount":1200500,"firstArticle":{"no":1,"charge":false}}""",
				)

				path == "/api/article/list" -> Spec(
					body = """
						{
							"articleList": [
								{"no": 654, "starScore": 0.0, "charge": true},
								{"no": 650, "starScore": 9.85, "charge": false},
								{"no": 649, "starScore": 9.75, "charge": false}
							]
						}
					""".trimIndent(),
				)

				path == "/comment/api/community/v1/pages/activity/count/" -> {
					assertEquals("comic_webtoon", ticket)
					assertEquals("webtoon_183559_650", req.url.queryParameter("pageIds"))
					Spec(
						body = """
							{
								"status": "success",
								"result": {"countList": [{"pageId": "webtoon_183559_650", "activeRootPostCount": 321}]}
							}
						""".trimIndent(),
					)
				}

				path == "/api/loginStatus" && req.method == "POST" -> Spec(
					body = """{"status":"success","result":{"login":true,"naverLogin":true}}""",
				)

				path == "/comment/api/community/v1/users/my" -> Spec(
					body = """{"status":"success","result":{"cuid":"me_cuid","userId":"me_id","name":"독자A"}}""",
				)

				path == "/comment/api/community/v2/posts" -> {
					val cursor = req.url.queryParameter("cursor")
					if (cursor == null) {
						Spec(
							body = """
								{
									"status": "success",
									"result": {
										"activeRootPostCount": 2,
										"pagination": {"next": "cur_2"},
										"posts": [
											{
												"id": "KW-comic_webtoon:32-webtoon_183559_650-p1",
												"pageId": "webtoon_183559_650",
												"status": "SERVICE",
												"body": "첫 번째 댓글",
												"createdAt": 1700000000000,
												"activeChildPostCount": 1,
												"settings": {"spoilerFilter": "ON"},
												"createdBy": {"cuid": "u1", "name": "유저1", "isCreator": true},
												"reactions": [{
													"reactionId": "post_like",
													"emotions": [
														{"emotionId": "like", "count": 10, "reacted": true},
														{"emotionId": "dislike", "count": 2, "reacted": false}
													]
												}]
											}
										]
									}
								}
							""".trimIndent(),
						)
					} else {
						assertEquals("cur_2", cursor)
						Spec(
							body = """
								{
									"status": "success",
									"result": {
										"activeRootPostCount": 2,
										"pagination": {},
										"posts": [
											{
												"id": "KW-comic_webtoon:32-webtoon_183559_650-p2",
												"pageId": "webtoon_183559_650",
												"status": "SERVICE",
												"body": "두 번째 댓글",
												"createdAt": 1700000001000,
												"activeChildPostCount": 0,
												"createdBy": {"cuid": "me_cuid", "name": "독자A"},
												"reactions": []
											}
										]
									}
								}
							""".trimIndent(),
						)
					}
				}

				path.endsWith("/top-recent-posts") -> Spec(
					body = """
						{
							"status": "success",
							"result": {
								"pins": [
									{
										"id": "KW-pin",
										"pageId": "webtoon_183559_650",
										"status": "SERVICE",
										"body": "베스트 댓글",
										"createdAt": 1700000000000,
										"isPinned": true,
										"isTop": true,
										"createdBy": {"cuid": "u2", "name": "작성자"},
										"reactions": []
									}
								],
								"tops": [
									{
										"id": "KW-pin",
										"pageId": "webtoon_183559_650",
										"status": "SERVICE",
										"body": "베스트 댓글",
										"createdAt": 1700000000000,
										"isPinned": true,
										"isTop": true,
										"createdBy": {"cuid": "u2", "name": "작성자"},
										"reactions": []
									}
								]
							}
						}
					""".trimIndent(),
				)

				path.endsWith("/child-posts") -> Spec(
					body = """
						{
							"status": "success",
							"result": {
								"activeChildPostCount": 1,
								"pagination": {},
								"posts": [
									{
										"id": "KW-reply-1",
										"parentId": "KW-comic_webtoon:32-webtoon_183559_650-p1",
										"pageId": "webtoon_183559_650",
										"status": "SERVICE",
										"body": "답글 내용",
										"createdAt": 1700000002000,
										"createdBy": {"cuid": "u3", "name": "답글러"},
										"reactions": []
									}
								]
							}
						}
					""".trimIndent(),
				)

				path == "/comment/api/community/v2/post" && req.method == "POST" -> {
					assertTrue(""""body":"새 댓글"""" in body)
					Spec(
						body = """
							{
								"status": "success",
								"result": {
									"id": "KW-new-1",
									"pageId": "webtoon_183559_650",
									"status": "SERVICE",
									"body": "새 댓글",
									"createdAt": 1700000003000,
									"createdBy": {"cuid": "me_cuid", "name": "독자A"},
									"reactions": []
								}
							}
						""".trimIndent(),
					)
				}

				path.contains("/reaction/post_like/") -> {
					reactionCalls.add(req.method to path)
					Spec(
						body = """
							{
								"status": "success",
								"result": {
									"reactions": [{
										"reactionId": "post_like",
										"emotions": [
											{"emotionId": "like", "count": 9, "reacted": false},
											{"emotionId": "dislike", "count": 2, "reacted": false}
										]
									}]
								}
							}
						""".trimIndent(),
					)
				}

				path.startsWith("/comment/api/community/v2/post/") && req.method == "DELETE" -> Spec(
					body = """{"status":"success"}""",
				)

				path == "/nidlogin.logout" -> {
					loggedOutCalled = true
					Spec(code = 200, body = "ok", contentType = "text/plain")
				}

				else -> Spec(code = 404, body = "{}")
			}
		}

		val wt = NaverWebtoonParser(harness.context)
		val manga = org.koitharu.kotatsu.parsers.model.Manga(
			id = 183559L,
			title = "신의 탑",
			altTitles = emptySet(),
			url = "/webtoon/list?titleId=183559",
			publicUrl = "https://m.comic.naver.com/webtoon/list?titleId=183559",
			rating = 0.98f,
			contentRating = ContentRating.SAFE,
			coverUrl = null,
			tags = emptySet(),
			state = MangaState.ONGOING,
			authors = setOf("SIU"),
			source = MangaParserSource.NAVERWEBTOON,
		)

		// Unauthenticated stats pick latest FREE episode (650, skipping charged 654)
		val infoAnon = wt.getMangaCommunityInfo(manga, null)
		assertEquals(9.8f, infoAnon.rawRating)
		assertEquals(10, infoAnon.rawScale)
		assertEquals(1200500L, infoAnon.followCount)
		assertEquals(321, infoAnon.commentCount)
		assertFalse(infoAnon.canCurrentUserComment)

		// Authenticated session
		harness.cookieJar.insertCookies(
			"m.comic.naver.com",
			"NID_AUT=aut_tok; Path=/; Domain=.naver.com",
			"NID_SES=ses_tok; Path=/; Domain=.naver.com",
		)
		assertTrue(wt.isAuthorized())
		assertEquals("독자A", wt.getUsername())

		// Newest comments + cursor pagination
		val page1 = wt.getComments(manga, chapter = null, page = 1, sort = SourceCommentSort.NEWEST)
		assertTrue(page1.hasNextPage)
		assertEquals("cur_2", page1.nextCursor)
		assertEquals(1, page1.comments.size)
		val firstComment = page1.comments.first()
		assertTrue(firstComment.isSpoiler)
		assertEquals("Creator", firstComment.authorRole)
		assertEquals(10, firstComment.likesCount)
		assertEquals(1, firstComment.userVote)
		assertEquals(1, firstComment.repliesCount)

		val page2 = wt.getComments(manga, chapter = null, page = 2, sort = SourceCommentSort.NEWEST, cursor = page1.nextCursor)
		assertFalse(page2.hasNextPage)
		assertTrue(page2.comments.first().isOwnComment)
		assertTrue(page2.comments.first().canDelete)

		// TOP comments deduplicated across pins + tops
		val topPage = wt.getComments(manga, chapter = null, page = 1, sort = SourceCommentSort.TOP)
		assertEquals(1, topPage.comments.size)
		assertTrue(topPage.comments.first().isPinned)

		// Replies
		val replies = wt.getReplies(manga, firstComment)
		assertEquals(1, replies.comments.size)
		assertEquals("답글 내용", replies.comments.first().body)

		// Post, unlike (DELETE), and delete
		val posted = wt.postComment(manga, "새 댓글")
		assertEquals("KW-new-1", posted.id)
		assertTrue(posted.isOwnComment)

		val unliked = wt.toggleCommentLike(manga, firstComment, isLike = true)
		assertEquals(9, unliked.likesCount)
		assertEquals(0, unliked.userVote)
		assertEquals("DELETE", reactionCalls.single().first)

		wt.deleteComment(manga, posted)

		// Widget fallback
		val widget = wt.resolveWidgetContext(manga, null)
		assertNotNull(widget)
		assertEquals(SourceCommentEngine.CUSTOM_WIDGET, widget?.engine)
		assertEquals("https://m.comic.naver.com/scrolltoon/comment?titleId=183559&no=650", widget?.widgetUrl)

		// Logout clears session
		wt.logout()
		assertTrue(loggedOutCalled)
		assertFalse(wt.isAuthorized())
	}
}
