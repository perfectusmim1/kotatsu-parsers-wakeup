package org.koitharu.kotatsu.parsers.site.mangareader.es

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
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class LectorHentaiTest {

	@Test
	fun getPagesDecryptsPackedReaderBlobAndSkipsDecoyIndices() = runTest {
		val key = ByteArray(32).apply { SecureRandom().nextBytes(this) }
		val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
		val plainJson =
			"""{"p":["https://cdn.example.com/1.webp","https://cdn.example.com/decoy.webp","https://cdn.example.com/2.webp"],"s":[1]}"""
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
		val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

		val kB64 = Base64.getEncoder().encodeToString(key)
		val ivB64 = Base64.getEncoder().encodeToString(iv)
		val cB64 = Base64.getEncoder().encodeToString(encrypted)

		// Dean Edwards packed script where tokens 0..4 map via dict
		val packedScript = """
			eval(function(p,a,c,k,e,d){return p}('0.1({"2":"$kB64","3":"$ivB64","4":"$cB64"});',10,5,'ReaderBlob|start|k|iv|c'.split('|'),0,{}));
			ts_reader.run({"sources":[{"images":["https://lectorhentai.com/assets/img/readerarea.svg"]}]});
		""".trimIndent()

		val chapterHtml = """
			<html><body>
			<script>$packedScript</script>
			<div id="readerarea"><img src="https://lectorhentai.com/assets/img/readerarea.svg" /></div>
			</body></html>
		""".trimIndent()

		val context = TestContext { path, _ ->
			when (path) {
				"/read/1/sample" -> chapterHtml
				else -> "<html/>"
			}
		}
		val parser = LectorHentai(context)
		val pages = parser.getPages(
			MangaChapter(
				id = 1L,
				title = "Capítulo 1",
				number = 1f,
				volume = 0,
				url = "/read/1/sample",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = MangaParserSource.LECTORHENTAI,
			),
		)

		assertEquals(
			listOf("https://cdn.example.com/1.webp", "https://cdn.example.com/2.webp"),
			pages.map { it.url },
		)
	}

	@Test
	fun getPagesFallsBackToTsReaderAndHtmlAndNormalizesSearch() = runTest {
		val encodedPage = Base64.getEncoder().encodeToString("https://cdn.example.com/decoded.jpg".toByteArray())
		val tsReaderHtml = """
			<html><body>
			<script>
			ts_reader.run({"sources":[{"source":"Server1","images":["https://lectorhentai.com/assets/img/readerarea.svg","$encodedPage","https://cdn.example.com/last.jpg"]}]});
			</script>
			</body></html>
		""".trimIndent()

		val htmlFallback = """
			<html><body>
			<div id="readerarea">
				<img src="https://lectorhentai.com/assets/img/readerarea.svg" />
				<img data-src="//cdn.example.com/html1.webp" />
				<img src="https://cdn.example.com/html2.webp" />
			</div>
			</body></html>
		""".trimIndent()

		val detailsHtml = """
			<html><body>
			<h1 class="entry-title">Sample Doujin</h1>
			<div class="releases"><a href="/read/99/sample"><h2>Leer Capítulo</h2></a></div>
			<time datetime="2025-11-22 06:06:19">22 Nov 2025</time>
			</body></html>
		""".trimIndent()

		var capturedQuery: String? = null
		val context = TestContext { path, query ->
			when (path) {
				"/read/2/ts" -> tsReaderHtml
				"/read/3/html" -> htmlFallback
				"/manga/99/sample" -> detailsHtml
				"/tipo/all" -> {
					capturedQuery = query
					"<html/>"
				}
				else -> "<html/>"
			}
		}
		val parser = LectorHentai(context)

		val tsPages = parser.getPages(
			MangaChapter(2L, null, 1f, 0, "/read/2/ts", null, 0L, null, MangaParserSource.LECTORHENTAI),
		)
		assertEquals(
			listOf("https://cdn.example.com/decoded.jpg", "https://cdn.example.com/last.jpg"),
			tsPages.map { it.url },
		)

		val htmlPages = parser.getPages(
			MangaChapter(3L, null, 1f, 0, "/read/3/html", null, 0L, null, MangaParserSource.LECTORHENTAI),
		)
		assertEquals(
			listOf("https://cdn.example.com/html1.webp", "https://cdn.example.com/html2.webp"),
			htmlPages.map { it.url },
		)

		val details = parser.getDetails(
			Manga(
				id = 99L,
				title = "Sample Doujin",
				altTitles = emptySet(),
				url = "/manga/99/sample",
				publicUrl = "https://lectorhentai.com/manga/99/sample",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = MangaParserSource.LECTORHENTAI,
			),
		)
		val chapters = requireNotNull(details.chapters)
		assertEquals(1, chapters.size)
		assertTrue(chapters.single().uploadDate > 0L)

		parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(query = "Don't Toy: With Me!"))
		assertEquals("s=Don+t+Toy+With+Me&page=1", capturedQuery)
	}

	private class TestContext(
		private val responder: (path: String, query: String?) -> String,
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val parsers: ParsersBundle
			get() = ParsersBundleImpl
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
