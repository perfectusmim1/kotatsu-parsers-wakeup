package org.koitharu.kotatsu.parsers.site.madara.tr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.Locale

@MangaSourceParser("WEBTOONHATTI", "WebtoonHatti", "tr")
internal class Webtoonhatti(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.WEBTOONHATTI, "webtoonhatti.club", 20) {
	override val listUrl = "webtoon/"
	override val tagPrefix = "webtoon-tur/"
	override val datePattern = "d MMMM"

	override fun isInvalidPageImage(img: Element, rawUrl: String, relativeUrl: String): Boolean {
		if (super.isInvalidPageImage(img, rawUrl, relativeUrl)) {
			return true
		}
		val meta = buildString {
			append(img.className())
			append(' ')
			append(img.attr("alt"))
		}.lowercase(Locale.ROOT)
		if (meta.isNotBlank() && meta.split(TOKEN_SPLIT_REGEX).any { it in META_PROMO_TOKENS }) {
			return true
		}
		return isWebtoonHattiPromoUrl(rawUrl)
	}

	override fun isInvalidPageUrl(rawUrl: String, relativeUrl: String): Boolean {
		return super.isInvalidPageUrl(rawUrl, relativeUrl) || isWebtoonHattiPromoUrl(rawUrl)
	}

	override fun isTrailingPromoPage(
		rawUrl: String,
		relativeUrl: String,
		dominantDir: String?,
		leadingPagesCount: Int,
	): Boolean {
		if (super.isTrailingPromoPage(rawUrl, relativeUrl, dominantDir, leadingPagesCount)) {
			return true
		}
		if (leadingPagesCount < 2 || dominantDir == null) {
			return false
		}
		val parsed = rawUrl.toHttpUrlOrNull()
		val path = (parsed?.encodedPath ?: rawUrl.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
		val dir = "${parsed?.host?.lowercase(Locale.ROOT).orEmpty()}${path.substringBeforeLast('/', "")}"
		if (dir == dominantDir) {
			return false
		}
		val fileName = path.substringAfterLast('/')
		val stem = fileName.substringBeforeLast('.')
		if (!stem.any(Char::isDigit)) {
			return true
		}
		return WP_MEDIA_DATE_PATH_REGEX.containsMatchIn(path) && !WP_MEDIA_DATE_PATH_REGEX.containsMatchIn(dominantDir)
	}

	private fun isWebtoonHattiPromoUrl(rawUrl: String): Boolean {
		val parsed = rawUrl.toHttpUrlOrNull()
		val path = (parsed?.encodedPath ?: rawUrl.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
		val fileName = path.substringAfterLast('/')
		val stem = fileName.substringBeforeLast('.')
		val tokens = stem.split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
		if (tokens.isEmpty() || tokens.all { it.all(Char::isDigit) }) {
			return false
		}
		return tokens.any { it in PROMO_STEM_TOKENS }
	}

	private companion object {
		val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
		val WP_MEDIA_DATE_PATH_REGEX = Regex("""/wp-content/uploads/\d{4}/\d{2}/""")

		val PROMO_STEM_TOKENS = setOf(
			"webtoonhatti",
			"hatti",
			"ekip",
			"alimi",
			"alim",
			"sosyal",
			"takip",
			"telegram",
			"instagram",
			"duyuru",
			"sonbanner",
			"altbanner",
			"reklam",
		)

		val META_PROMO_TOKENS = PROMO_STEM_TOKENS + setOf(
			"banner",
			"sponsor",
			"discord",
			"patreon",
			"ads",
			"footer",
		)
	}
}
