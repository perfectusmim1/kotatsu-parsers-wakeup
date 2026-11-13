package org.koitharu.kotatsu.parsers.site.keyoapp.en

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.keyoapp.KeyoappParser

@MangaSourceParser("KEWNSCANS", "KewnScans", "en")
internal class KewnScans(context: MangaLoaderContext) :
	KeyoappParser(context, MangaParserSource.KEWNSCANS, "kewnscans.org") {

	override val defaultDisqusShortname: String = "kewnscans"

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		val contentType = response.header("Content-Type").orEmpty()
		if (chain.request().url.host == "cdn.meowing.org" && contentType.startsWith("text/plain", ignoreCase = true)) {
			val bytes = response.use { it.body.bytes() }
			val detectedMime = detectImageMimeType(bytes) ?: return response.newBuilder()
				.body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
				.build()
			return response.newBuilder()
				.header("Content-Type", detectedMime)
				.body(bytes.toResponseBody(detectedMime.toMediaTypeOrNull()))
				.build()
		}
		return response
	}

	private fun detectImageMimeType(bytes: ByteArray): String? {
		if (bytes.size >= 4 &&
			bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
			bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
		) {
			return "image/png"
		}
		if (bytes.size >= 3 &&
			bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
		) {
			return "image/jpeg"
		}
		if (bytes.size >= 12 &&
			bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
			bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
			bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
			bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
		) {
			return "image/webp"
		}
		if (bytes.size >= 4 &&
			bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
			bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
		) {
			return "image/gif"
		}
		return null
	}
}
