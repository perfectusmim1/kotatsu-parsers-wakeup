package org.koitharu.kotatsu.parsers.network

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAVAILABLE

public object CloudFlareHelper {

    public const val PROTECTION_NOT_DETECTED: Int = 0
    public const val PROTECTION_CAPTCHA: Int = 1
    public const val PROTECTION_BLOCKED: Int = 2

    private const val CF_CLEARANCE = "cf_clearance"
    private const val HTTP_TOO_MANY_REQUESTS = 429

    public fun checkResponseForProtection(response: Response): Int {
        if (response.code != HTTP_FORBIDDEN &&
            response.code != HTTP_UNAVAILABLE &&
            response.code != HTTP_TOO_MANY_REQUESTS
        ) {
            return PROTECTION_NOT_DETECTED
        }
        val content = try {
            response.peekBody(Long.MAX_VALUE).use {
                Jsoup.parse(it.byteStream(), Charsets.UTF_8.name(), response.request.url.toString())
            }
        } catch (_: IllegalStateException) {
            null
        } catch (_: IOException) {
            null
        }
        if (content != null) {
            val bodyText = content.body().text()
            if (content.selectFirst("h2[data-translate=\"blocked_why_headline\"]") != null ||
                bodyText.contains("Sorry, you have been blocked", ignoreCase = true)
            ) {
                return PROTECTION_BLOCKED
            }
        }
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
            return PROTECTION_CAPTCHA
        }
        if (content == null) {
            return PROTECTION_NOT_DETECTED
        }
        val rawHtml = content.outerHtml()
        val hasChallengeDom = content.getElementById("challenge-error-title") != null ||
            content.getElementById("challenge-error-text") != null ||
            content.getElementById("challenge-running") != null ||
            content.getElementById("challenge-stage") != null ||
            content.getElementById("challenge-form") != null ||
            content.getElementById("cf-challenge-running") != null ||
            content.getElementById("turnstile-wrapper") != null ||
            content.selectFirst(
                ".cf-browser-verification, .cf-turnstile, form[action*=\"__cf_chl\"], script[src*=\"/cdn-cgi/challenge-platform\"]",
            ) != null ||
            rawHtml.contains("challenges.cloudflare.com", ignoreCase = true) ||
            rawHtml.contains("window._cf_chl_opt", ignoreCase = true)
        if (hasChallengeDom) {
            return PROTECTION_CAPTCHA
        }
        val title = content.title()
        val hasCloudflareMarker = response.header("cf-ray") != null ||
            response.header("server")?.contains("cloudflare", ignoreCase = true) == true ||
            rawHtml.contains("/cdn-cgi/", ignoreCase = true) ||
            rawHtml.contains("cloudflare", ignoreCase = true) ||
            rawHtml.contains("cf-ray", ignoreCase = true)
        if (hasCloudflareMarker && (
                title.startsWith("Just a moment", ignoreCase = true) ||
                    title.startsWith("Bir dakika lütfen", ignoreCase = true)
                )
        ) {
            return PROTECTION_CAPTCHA
        }
        return PROTECTION_NOT_DETECTED
    }

    public fun getClearanceCookie(cookieJar: CookieJar, url: String): String? {
        return cookieJar.loadForRequest(url.toHttpUrl()).find { it.name == CF_CLEARANCE }?.value
    }

    public fun isCloudFlareCookie(name: String): Boolean {
        return name.startsWith("cf_")
            || name.startsWith("_cf")
            || name.startsWith("__cf")
            || name == "csrftoken"
    }
}
