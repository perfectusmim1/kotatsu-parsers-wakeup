package org.koitharu.kotatsu.parsers.site

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madara.all.Manhwa18Cc
import org.koitharu.kotatsu.parsers.site.madara.en.MangaDass
import org.koitharu.kotatsu.parsers.site.madara.en.MangaDna
import org.koitharu.kotatsu.parsers.site.mangareader.es.HentaiReader
import org.koitharu.kotatsu.parsers.site.mangareader.id.KomikTapParser
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class MangaReaderScriptAndSortTest {

	@Test
	fun hentaiReaderDecryptsPackedReaderBlobInsteadOfJsonValueFunctionError() = runTest {
		val key = ByteArray(32).apply { SecureRandom().nextBytes(this) }
		val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
		val plainJson =
			"""{"p":["https://cdn.example.org/p1.webp","https://cdn.example.org/decoy.webp","https://cdn.example.org/p2.webp"],"s":[1]}"""
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
		val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

		val kB64 = Base64.getEncoder().encodeToString(key)
		val ivB64 = Base64.getEncoder().encodeToString(iv)
		val cB64 = Base64.getEncoder().encodeToString(encrypted)

		val chapterHtml = """
			<html><body>
			<script>
			eval(function(p,a,c,k,e,d){return p}('0.1({"2":"$kB64","3":"$ivB64","4":"$cB64"});',10,5,'ReaderBlob|start|k|iv|c'.split('|'),0,{}));
			(function(){var ts_reader_stub=true;})();
			</script>
			<div id="readerarea"><img src="https://hentaireader.org/assets/img/readerarea.svg" /></div>
			</body></html>
		""".trimIndent()

		val context = OfflineContext { _, _ -> chapterHtml }
		val pages = HentaiReader(context).getPages(
			MangaChapter(1L, "Cap 1", 1f, 0, "/read/55699", null, 0L, null, MangaParserSource.HENTAIREADER),
		)

		assertEquals(
			listOf("https://cdn.example.org/p1.webp", "https://cdn.example.org/p2.webp"),
			pages.map { it.url },
		)
	}

	@Test
	fun mangaReaderExtractsTsReaderInsideFunctionWrapper() = runTest {
		val chapterHtml = """
			<html><body>
			<script>
			(function(){
				if (window.ts_reader) {
					ts_reader.run({"sources":[{"source":"Server1","images":["https://img.example.org/01.jpg","https://img.example.org/02.jpg", ] }]});
				}
			})();
			</script>
			</body></html>
		""".trimIndent()

		val context = OfflineContext { _, _ -> chapterHtml }
		val pages = KomikTapParser(context).getPages(
			MangaChapter(2L, "Ch 1", 1f, 0, "/ch-1", null, 0L, null, MangaParserSource.KOMIKTAP),
		)

		assertEquals(
			listOf("https://img.example.org/01.jpg", "https://img.example.org/02.jpg"),
			pages.map { it.url },
		)
	}

	@Test
	fun madaraWithoutAjaxAndMangaDassExposeOnlySupportedSortOrders() = runTest {
		var requestedUrl: String? = null
		val context = OfflineContext { url, _ ->
			requestedUrl = url
			"<html><body></body></html>"
		}

		val mangaDass = MangaDass(context)
		assertFalse(SortOrder.NEWEST in mangaDass.availableSortOrders)
		assertFalse(SortOrder.UPDATED_ASC in mangaDass.availableSortOrders)
		mangaDass.getListPage(1, SortOrder.POPULARITY, MangaListFilter.EMPTY)
		assertEquals("https://mangadass.com/manga//1?orderby=trending", requestedUrl)

		val mangaDna = MangaDna(context)
		assertFalse(SortOrder.NEWEST in mangaDna.availableSortOrders)
		assertFalse(SortOrder.UPDATED_ASC in mangaDna.availableSortOrders)

		val manhwa18Cc = Manhwa18Cc(context)
		assertFalse(SortOrder.NEWEST in manhwa18Cc.availableSortOrders)
		assertFalse(SortOrder.UPDATED_ASC in manhwa18Cc.availableSortOrders)

		val isekaiScan = ParsersBundleImpl.newParser(MangaParserSource.ISEKAISCAN, context)
		assertFalse(SortOrder.UPDATED_ASC in isekaiScan.availableSortOrders)
		assertFalse(SortOrder.POPULARITY_ASC in isekaiScan.availableSortOrders)
	}

	private class OfflineContext(
		private val responder: (url: String, query: String?) -> String,
	) : MangaLoaderContext() {

		override val parsers: ParsersBundle get() = ParsersBundleImpl

		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				val body = responder(req.url.toString(), req.url.query).toResponseBody("text/html".toMediaType())
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(body)
					.build()
			}
			.build()

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES

		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null

		override fun getDefaultUserAgent(): String = "KotatsuTest/1.0"

		override fun getConfig(source: MangaSource) = SourceConfigMock()

		override fun encodeBase64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

		override fun decodeBase64(data: String): ByteArray = Base64.getDecoder().decode(data)

		override fun getPreferredLocales() = listOf(java.util.Locale.ENGLISH)

		override fun requestBrowserAction(
			parser: org.koitharu.kotatsu.parsers.MangaParser,
			url: String,
		): Nothing = throw UnsupportedOperationException()

		override fun redrawImageResponse(
			response: Response,
			redraw: (image: Bitmap) -> Bitmap,
		) = response

		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}
}
