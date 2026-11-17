package org.koitharu.kotatsu.parsers.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class CloudFlareHelperTest {

	@Test
	fun detectsBlockedHeadlineBeforeChallengeMarkers() {
		val html = """
			<html>
				<head><title>Attention Required! | Cloudflare</title></head>
				<body>
					<h2 data-translate="blocked_why_headline">Why have I been blocked?</h2>
					<div id="challenge-error-title">ignored</div>
				</body>
			</html>
		""".trimIndent()
		val response = buildResponse(403, html)
		assertEquals(
			CloudFlareHelper.PROTECTION_BLOCKED,
			CloudFlareHelper.checkResponseForProtection(response),
		)
	}

	@Test
	fun detectsSorryBlockedBodyText() {
		val html = "<html><body><p>Sorry, you have been blocked from accessing this site.</p></body></html>"
		val response = buildResponse(403, html)
		assertEquals(
			CloudFlareHelper.PROTECTION_BLOCKED,
			CloudFlareHelper.checkResponseForProtection(response),
		)
	}

	@Test
	fun detectsCfMitigatedChallengeOn429() {
		val response = buildResponse(
			code = 429,
			body = "rate limited",
			headers = mapOf("cf-mitigated" to "challenge"),
		)
		assertEquals(
			CloudFlareHelper.PROTECTION_CAPTCHA,
			CloudFlareHelper.checkResponseForProtection(response),
		)
	}

	@Test
	fun doesNotTriggerFalsePositiveOnNonCloudflare429Api() {
		val response = buildResponse(
			code = 429,
			body = """{"error":"Too many requests","retry_after":60}""",
			contentType = "application/json",
		)
		assertEquals(
			CloudFlareHelper.PROTECTION_NOT_DETECTED,
			CloudFlareHelper.checkResponseForProtection(response),
		)
	}

	@Test
	fun detectsExpandedChallengeDomMarkersAndTurkishTitle() {
		val turnstileHtml = """<html><body><div class="cf-turnstile"></div></body></html>"""
		assertEquals(
			CloudFlareHelper.PROTECTION_CAPTCHA,
			CloudFlareHelper.checkResponseForProtection(buildResponse(403, turnstileHtml)),
		)

		val scriptHtml = """<html><body><script src="/cdn-cgi/challenge-platform/h/g/orchestrate"></script></body></html>"""
		assertEquals(
			CloudFlareHelper.PROTECTION_CAPTCHA,
			CloudFlareHelper.checkResponseForProtection(buildResponse(503, scriptHtml)),
		)

		val turkishTitleHtml = """<html><head><title>Bir dakika lütfen...</title></head><body></body></html>"""
		assertEquals(
			CloudFlareHelper.PROTECTION_CAPTCHA,
			CloudFlareHelper.checkResponseForProtection(
				buildResponse(403, turkishTitleHtml, headers = mapOf("cf-ray" to "123456-IST")),
			),
		)

		assertEquals(
			CloudFlareHelper.PROTECTION_NOT_DETECTED,
			CloudFlareHelper.checkResponseForProtection(buildResponse(403, turkishTitleHtml)),
		)
	}

	private fun buildResponse(
		code: Int,
		body: String,
		contentType: String = "text/html",
		headers: Map<String, String> = emptyMap(),
	): Response {
		val request = Request.Builder().url("https://example.com/").build()
		val builder = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("HTTP $code")
			.body(body.toResponseBody(contentType.toMediaType()))
		headers.forEach { (k, v) -> builder.header(k, v) }
		return builder.build()
	}
}
