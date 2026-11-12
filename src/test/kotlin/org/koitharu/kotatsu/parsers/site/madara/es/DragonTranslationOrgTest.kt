package org.koitharu.kotatsu.parsers.site.madara.es

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class DragonTranslationOrgTest {

	@Test
	fun parsesRedesignedMangaListJsonChaptersAndChapterImages() = runTest {
		val listHtml = """
			<html><body>
			<div class="grid">
				<a href="https://dragontranslation.org/manga/mi-serie/" title="Mi Serie">
					<img class="wp-post-image" src="https://dragontranslation.org/wp-content/uploads/cover.webp" />
				</a>
			</div>
			</body></html>
		""".trimIndent()

		val detailsHtml = """
			<html><head><meta property="og:url" content="https://dragontranslation.org/manga/mi-serie/" /></head>
			<body>
			<article class="type-wp-manga">
				<h1>Mi Serie</h1>
				<img class="wp-post-image" src="https://dragontranslation.org/wp-content/uploads/cover.webp" />
				<span>Estado</span><span>En emisión</span>
				<span>Alternativo</span><span>My Series Alt</span>
				<div><p>Una gran sinopsis.</p></div><button>Ver más</button>
				<div><a href="/manga-genre/accion/">Acción</a><a href="/manga-genre/fantasia/">Fantasía</a></div>
				<script type="application/json">
				{
					"mangaId": 123,
					"items": [
						{"name": "Capítulo 2", "url": "https://dragontranslation.org/manga/mi-serie/capitulo-2/", "ago": "2026-10-30"},
						{"name": "Capítulo 1", "url": "https://dragontranslation.org/manga/mi-serie/capitulo-1/", "ago": "10/10/2026"}
					]
				}
				</script>
			</article>
			</body></html>
		""".trimIndent()

		val chapterHtml = """
			<html><body>
			<div class="reader-wrap">
				<img class="wp-manga-chapter-img" data-src="https://cdn.dragontranslation.org/123/ch1/01.webp" />
				<img class="wp-manga-chapter-img" src="https://dragontranslation.org/wp-content/uploads/123/ch1/02.webp" />
			</div>
			</body></html>
		""".trimIndent()

		var latestQuery: String? = null
		val context = TestContext { path, query ->
			when {
				path == "/manga/" -> {
					latestQuery = query
					listHtml
				}
				path == "/manga/mi-serie/" -> detailsHtml
				path == "/manga/mi-serie/capitulo-1/" -> chapterHtml
				else -> "<html/>"
			}
		}

		val parser = DragonTranslationOrg(context)

		val latest = parser.getListPage(0, SortOrder.UPDATED, MangaListFilter())
		assertEquals("m_orderby=latest", latestQuery)
		assertEquals(1, latest.size)
		assertEquals("Mi Serie", latest.single().title)
		assertEquals("/manga/mi-serie/", latest.single().url)

		val details = parser.getDetails(
			Manga(
				id = 1L,
				title = "Old",
				altTitles = emptySet(),
				url = "/manga/mi-serie/",
				publicUrl = "https://dragontranslation.org/manga/mi-serie/",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = MangaParserSource.DRAGONTRANSLATIONORG,
			),
		)
		assertEquals("Mi Serie", details.title)
		assertEquals(MangaState.ONGOING, details.state)
		assertEquals(setOf("My Series Alt"), details.altTitles)
		assertTrue(details.description?.contains("Una gran sinopsis.") == true)
		assertEquals(setOf("accion", "fantasia"), details.tags.map { it.key }.toSet())

		val chapters = requireNotNull(details.chapters)
		assertEquals(2, chapters.size)
		assertEquals("Capítulo 1", chapters[0].title)
		assertEquals(1f, chapters[0].number)
		assertTrue(chapters[0].uploadDate > 0L)
		assertEquals("Capítulo 2", chapters[1].title)
		assertEquals(2f, chapters[1].number)
		assertTrue(chapters[1].uploadDate > 0L)

		val pages = parser.getPages(
			MangaChapter(1L, "Capítulo 1", 1f, 0, "/manga/mi-serie/capitulo-1/?style=list", null, 0L, null, MangaParserSource.DRAGONTRANSLATIONORG),
		)
		assertEquals(
			listOf(
				"https://cdn.dragontranslation.org/123/ch1/01.webp",
				"/wp-content/uploads/123/ch1/02.webp",
			),
			pages.map { it.url },
		)
	}

	private class TestContext(
		private val responder: (path: String, query: String?) -> String,
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				val body = responder(req.url.encodedPath, req.url.encodedQuery)
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
					.build()
			}
			.build()

		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent(): String = "TestAgent/1.0"
		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}
}
