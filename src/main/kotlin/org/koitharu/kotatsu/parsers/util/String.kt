@file:JvmName("StringUtils")

package org.koitharu.kotatsu.parsers.util

import androidx.collection.MutableIntList
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.min

private val REGEX_WHITESPACE = Regex("\\s+")
private val REGEX_SEARCH_PUNCTUATION = Regex(
	"""['’‘ʼ`"“”«»„‚:：;；\-–—―~～!！?？/／\\|,.\u3002\u3001·•&＆+＝=()*（）\[\]【】{}<>《》〈〉「」『』@#\$%^…]+""",
)
private val REGEX_BRACKETED_CONTENT = Regex("""[\[(【（{]\s*([^\]\)】）}]+?)\s*[\])】）}]""")
private val REGEX_TRAILING_NOISE_WITH_SEP = Regex(
	"""\s*[-–—|:/,]\s*(?:raw|end|ended|complete|completed|ongoing|official(?:\s+colored)?|full\s+color|color(?:ed)?|uncensored|censored|decensored|digital|oneshot|one-shot|promo|manga|manhwa|manhua|webtoon|nsfw|adult|18\+|\+18)\s*$""",
	RegexOption.IGNORE_CASE,
)
private val REGEX_TRAILING_NOISE_WORD = Regex(
	"""(?<=\S)\s+(?:raw|uncensored|censored|decensored)\s*$""",
	RegexOption.IGNORE_CASE,
)
private val NOISE_TITLE_TOKENS = setOf(
	"raw", "raws", "end", "ended", "complete", "completed", "ongoing",
	"official", "color", "colored", "full", "uncensored", "censored", "decensored",
	"digital", "oneshot", "one", "shot", "promo", "webtoon", "manga", "manhwa",
	"manhua", "novel", "light", "hentai", "nsfw", "adult", "mature", "18+", "+18",
	"r18", "eng", "english", "es", "esp", "spanish", "pt", "br", "ru", "rus",
	"kr", "jp", "cn", "scan", "scans", "scanlation", "tl", "ver", "version",
	"remake", "remaster", "remastered", "reupload", "update", "updated",
)
internal const val LONG_HASH_SEED = 1125899906842597L

public fun String.removeSurrounding(vararg chars: Char): String {
	if (isEmpty()) {
		return this
	}
	for (c in chars) {
		if (first() == c && last() == c) {
			return substring(1, length - 1)
		}
	}
	return this
}

public inline fun <C : R, R : CharSequence?> C?.ifNullOrEmpty(defaultValue: () -> R): R {
	contract {
		callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
	}
	return if (this.isNullOrEmpty()) defaultValue() else this
}

public fun String.longHashCode(): Long {
	var h = LONG_HASH_SEED
	val len: Int = this.length
	for (i in 0 until len) {
		h = 31 * h + this[i].code
	}
	return h
}

public fun String.toCamelCase(): String {
	if (isEmpty()) {
		return this
	}
	val result = StringBuilder(length)
	var capitalize = true
	for (char in this) {
		result.append(
			if (capitalize) {
				char.uppercase()
			} else {
				char.lowercase()
			},
		)
		capitalize = char.isWhitespace()
	}
	return result.toString()
}

public fun String.digits(): String = filter { it.isDigit() }

public fun String.toTitleCase(): String {
	return replaceFirstChar { x -> x.uppercase() }
}

public fun String.toTitleCase(locale: Locale): String {
	return replaceFirstChar { x -> x.uppercase(locale) }
}

public fun String.ellipsize(maxLength: Int): String = if (this.length > maxLength) {
	this.take(maxLength - 1) + Typography.ellipsis
} else this

public fun String.splitTwoParts(delimiter: Char): Pair<String, String>? {
	val indices = MutableIntList(4)
	for ((i, c) in this.withIndex()) {
		if (c == delimiter) {
			indices += i
		}
	}
	if (indices.isEmpty() || indices.size and 1 == 0) {
		return null
	}
	val index = indices[indices.size / 2]
	return substring(0, index) to substring(index + 1)
}

public fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

public fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

public fun String.nl2br(): String = replace("\n", "<br>")

public fun String.splitByWhitespace(): List<String> = trim().split(REGEX_WHITESPACE)

public fun <T : CharSequence> T.nullIfEmpty(): T? = takeUnless { it.isEmpty() }

public fun ByteArray.byte2HexFormatted(): String {
	val str = StringBuilder(size * 2)
	for (i in indices) {
		var h = Integer.toHexString(this[i].toInt())
		val l = h.length
		if (l == 1) {
			h = "0$h"
		}
		if (l > 2) {
			h = h.substring(l - 2, l)
		}
		str.append(h.uppercase(Locale.ROOT))
		if (i < size - 1) {
			str.append(':')
		}
	}
	return str.toString()
}

public fun String.md5(): String {
	val md = MessageDigest.getInstance("MD5")
	return BigInteger(1, md.digest(toByteArray()))
		.toString(16)
		.padStart(32, '0')
}

public fun String.substringBetween(from: String, to: String, fallbackValue: String = this): String {
	val fromIndex = indexOf(from)
	if (fromIndex == -1) {
		return fallbackValue
	}
	val toIndex = lastIndexOf(to)
	return if (toIndex == -1) {
		fallbackValue
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.substringBetweenFirst(from: String, to: String): String? {
	val fromIndex = indexOf(from)
	if (fromIndex == -1) {
		return null
	}
	val toIndex = indexOf(to, fromIndex)
	return if (toIndex == -1) {
		null
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.substringBetweenLast(from: String, to: String, fallbackValue: String = this): String {
	val fromIndex = lastIndexOf(from)
	if (fromIndex == -1) {
		return fallbackValue
	}
	val toIndex = lastIndexOf(to)
	return if (toIndex == -1) {
		fallbackValue
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.find(regex: Regex): String? = regex.find(this)?.value

public fun String.findGroupValue(regex: Regex): String? = regex.find(this)?.groupValues?.getOrNull(1)

public fun String.removeSuffix(suffix: Char): String {
	if (lastOrNull() == suffix) {
		return substring(0, length - 1)
	}
	return this
}

public fun String.levenshteinDistance(other: String): Int {
	if (this == other) {
		return 0
	}
	if (this.isEmpty()) {
		return other.length
	}
	if (other.isEmpty()) {
		return this.length
	}

	val lhsLength = this.length + 1
	val rhsLength = other.length + 1

	var cost = Array(lhsLength) { it }
	var newCost = Array(lhsLength) { 0 }

	for (i in 1 until rhsLength) {
		newCost[0] = i

		for (j in 1 until lhsLength) {
			val match = if (this[j - 1] == other[i - 1]) 0 else 1

			val costReplace = cost[j - 1] + match
			val costInsert = cost[j] + 1
			val costDelete = newCost[j - 1] + 1

			newCost[j] = min(min(costInsert, costDelete), costReplace)
		}

		val swap = cost
		cost = newCost
		newCost = swap
	}

	return cost[lhsLength - 1]
}

/**
 * @param threshold 0 = exact match
 */
public fun String.almostEquals(other: String, threshold: Float): Boolean {
	if (threshold <= 0f) {
		return equals(other, ignoreCase = true)
	}
	val diff = lowercase().levenshteinDistance(other.lowercase()) / ((length + other.length) / 2f)
	return diff < threshold
}

public fun String.isNumeric(): Boolean = all { c -> c.isDigit() }

internal fun StringBuilder.removeTrailingZero() {
	if (length > 2 && get(length - 1) == '0') {
		val dot = get(length - 2)
		if (dot == ',' || dot == '.') {
			delete(length - 2, length)
		}
	}
}

/**
 * Normalizes punctuation (apostrophes, colons, dashes, slashes, brackets, etc.) into single
 * spaces and collapses whitespace so parser search endpoints match titles regardless of
 * punctuation differences. Preserves underscores and Unicode letters/digits.
 */
public fun String.normalizeSearchQuery(): String {
	if (isBlank()) return ""
	return REGEX_SEARCH_PUNCTUATION.replace(this, " ")
		.replace(REGEX_WHITESPACE, " ")
		.trim()
}

/**
 * Strips common release/scanlation noise suffixes and bracketed tags (e.g. `(Raw)`, `[End]`,
 * `[Completed]`, `(Official)`, trailing `Raw`/`Uncensored`) without altering legitimate words
 * inside the title, then normalizes punctuation and whitespace.
 */
public fun String.sanitizeMangaSearchTitle(): String {
	if (isBlank()) return ""
	var cleaned = this.trim()
	cleaned = REGEX_BRACKETED_CONTENT.replace(cleaned) { match ->
		val tokens = match.groupValues[1]
			.lowercase(Locale.ROOT)
			.split(Regex("""[\s,/|&\-–—:+]+"""))
			.filter { it.isNotEmpty() }
		if (tokens.isNotEmpty() && tokens.all { it in NOISE_TITLE_TOKENS }) {
			" "
		} else {
			match.value
		}
	}
	var previous: String
	do {
		previous = cleaned
		cleaned = REGEX_TRAILING_NOISE_WITH_SEP.replace(cleaned, "")
		cleaned = REGEX_TRAILING_NOISE_WORD.replace(cleaned, "")
		cleaned = cleaned.trim()
	} while (cleaned != previous)
	return cleaned.normalizeSearchQuery()
}

/**
 * Builds deduplicated, sanitized search queries ordered from most specific (full sanitized
 * title) to subtitle-prefix, shorter word-prefix, and sanitized alternate titles.
 */
public fun buildMangaSearchQueries(
	title: String,
	altTitles: Collection<String> = emptySet(),
	maxQueries: Int = 6,
): List<String> {
	if (maxQueries <= 0) return emptyList()
	val seen = HashSet<String>()
	val result = ArrayList<String>(maxQueries)

	fun addCandidate(raw: String) {
		val sanitized = raw.sanitizeMangaSearchTitle().ifEmpty { raw.normalizeSearchQuery() }
		if (sanitized.isBlank()) return
		if (seen.add(sanitized.lowercase(Locale.ROOT))) {
			result.add(sanitized)
		}
	}

	addCandidate(title)

	val separatorIndex = listOf(": ", " - ", " – ", " — ", "~", " / ", "(", "[")
		.map { title.indexOf(it) }
		.filter { it > 0 }
		.minOrNull()
	if (separatorIndex != null) {
		addCandidate(title.substring(0, separatorIndex))
	}

	for (alt in altTitles) {
		if (result.size >= maxQueries) break
		addCandidate(alt)
	}

	if (result.size < maxQueries) {
		val primaryWords = result.firstOrNull()?.split(' ').orEmpty()
		if (primaryWords.size >= 4) {
			addCandidate(primaryWords.take(3).joinToString(" "))
		}
	}

	if (result.isEmpty()) {
		val fallback = title.normalizeSearchQuery()
		if (fallback.isNotEmpty()) {
			result.add(fallback)
		}
	}
	return result.take(maxQueries)
}

/**
 * Computes a normalized title match distance in `[0f, 1f)` when comparing [candidateTitle] and
 * [candidateAltTitles] against [seedTitles] after ignoring punctuation and noise tags, or
 * [Float.POSITIVE_INFINITY] if none of the titles are similar within [threshold].
 */
public fun mangaTitleMatchDistance(
	candidateTitle: String,
	candidateAltTitles: Collection<String>,
	seedTitles: Collection<String>,
	threshold: Float = 0.25f,
): Float {
	fun normalizeSet(titles: Collection<String>): List<String> = titles
		.asSequence()
		.flatMap { raw ->
			val sanitized = raw.sanitizeMangaSearchTitle().lowercase(Locale.ROOT)
			val normalized = raw.normalizeSearchQuery().lowercase(Locale.ROOT)
			sequenceOf(sanitized, normalized)
		}
		.filter { it.isNotEmpty() }
		.distinct()
		.toList()

	val candidateVariants = normalizeSet(buildList(1 + candidateAltTitles.size) {
		add(candidateTitle)
		addAll(candidateAltTitles)
	})
	val seedVariants = normalizeSet(seedTitles)
	if (candidateVariants.isEmpty() || seedVariants.isEmpty()) {
		return Float.POSITIVE_INFINITY
	}

	var bestScore = Float.POSITIVE_INFINITY
	for (c in candidateVariants) {
		val cWords = c.split(' ').filter { it.isNotEmpty() }
		for (s in seedVariants) {
			if (c == s) return 0f
			val avgLen = (c.length + s.length) / 2f
			if (avgLen > 0f) {
				val dist = c.levenshteinDistance(s) / avgLen
				if (dist < threshold && dist < bestScore) {
					bestScore = dist
				}
			}
			val sWords = s.split(' ').filter { it.isNotEmpty() }
			val minWords = min(cWords.size, sWords.size)
			if (minWords >= 2) {
				val (shorter, longer) = if (cWords.size <= sWords.size) cWords to sWords else sWords to cWords
				val longerSet = longer.toHashSet()
				val overlapCount = shorter.count { it in longerSet }
				val longerOverlap = overlapCount.toFloat() / longer.size.toFloat()
				val containsShorterAll = overlapCount == shorter.size
				if (containsShorterAll && longerOverlap >= 0.55f) {
					val tokenScore = (1f - longerOverlap) * threshold
					if (tokenScore < threshold && tokenScore < bestScore) {
						bestScore = tokenScore
					}
				}
			}
		}
	}
	return bestScore
}

public fun isSimilarMangaTitle(
	candidateTitle: String,
	candidateAltTitles: Collection<String>,
	seedTitles: Collection<String>,
	threshold: Float = 0.25f,
): Boolean = mangaTitleMatchDistance(candidateTitle, candidateAltTitles, seedTitles, threshold) < threshold

public fun String.containsNormalizedQuery(query: String): Boolean {
	if (query.isBlank()) return true
	if (contains(query, ignoreCase = true)) return true
	val normalizedQuery = query.normalizeSearchQuery()
	if (normalizedQuery.isEmpty()) return false
	return normalizeSearchQuery().contains(normalizedQuery, ignoreCase = true)
}
