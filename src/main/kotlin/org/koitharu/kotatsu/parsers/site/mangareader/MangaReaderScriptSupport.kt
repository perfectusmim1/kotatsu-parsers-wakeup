package org.koitharu.kotatsu.parsers.site.mangareader

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared extractor for MangaReader-family chapter pages supporting Dean Edwards packed scripts,
 * encrypted `ReaderBlob.start(...)` payloads, `ts_reader.run(...)` JSON calls, and base64-encoded
 * script tags without fragile `substringAfter('(')` slicing that fails on `function(...)` wrappers.
 */
internal object MangaReaderScriptSupport {

	private const val BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
	private val WORD_TOKEN_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")
	private val TRAILING_ARRAY_COMMA_REGEX = Regex(""",\s*]""")
	private val TRAILING_OBJ_COMMA_REGEX = Regex(""",\s*}""")

	fun extractScriptPageUrls(
		context: MangaLoaderContext,
		docs: Document,
		selectTestScript: String = "script:containsData(ts_reader)",
		selectScript: String = "div.wrapper script",
		encodedSrc: Boolean = false,
	): List<String> {
		if (encodedSrc) {
			for (el in docs.select(selectScript)) {
				val src = el.attr("src")
				if (src.startsWith("data:text/javascript;base64,")) {
					val decoded = runCatching {
						Base64.getDecoder()
							.decode(src.removePrefix("data:text/javascript;base64,"))
							.decodeToString()
					}.getOrNull().orEmpty()
					extractFromScriptText(context, decoded)?.takeIf { it.isNotEmpty() }?.let { return it }
				}
			}
		}

		val candidates = LinkedHashSet<String>()
		docs.select(selectTestScript).forEach { candidates.add(it.data()) }
		docs.select("script").forEach { el ->
			val data = el.data()
			if (data.contains("ReaderBlob") ||
				data.contains("ts_reader.run") ||
				(data.contains("eval(function") && data.contains(".split("))
			) {
				candidates.add(data)
			}
		}

		for (scriptData in candidates) {
			extractFromScriptText(context, scriptData)?.takeIf { it.isNotEmpty() }?.let { return it }
		}
		return emptyList()
	}

	fun extractFromScriptText(context: MangaLoaderContext, rawScript: String): List<String>? {
		if (rawScript.isBlank()) return null
		val unpacked = if (rawScript.contains("eval(function") && rawScript.contains(".split(")) {
			unpackDeanEdwards(rawScript) ?: rawScript
		} else {
			rawScript
		}

		if (unpacked.contains("ReaderBlob.start(")) {
			runCatchingCancellable { decodeReaderBlob(context, unpacked) }
				.getOrNull()
				?.takeIf { it.isNotEmpty() }
				?.let { return it }
		}

		if (unpacked.contains("ts_reader.run(")) {
			runCatchingCancellable { extractTsReaderImages(context, unpacked) }
				.getOrNull()
				?.takeIf { it.isNotEmpty() }
				?.let { return it }
		}

		return null
	}

	fun decodeReaderBlob(context: MangaLoaderContext, script: String): List<String> {
		val jsonPayload = extractCallJsonObject(script, "ReaderBlob.start(") ?: return emptyList()
		val blobJson = JSONObject(jsonPayload)
		val keyBytes = context.decodeBase64(blobJson.getString("k"))
		val ivStr = blobJson.getStringOrNull("iv") ?: blobJson.getString("V")
		val ivBytes = context.decodeBase64(ivStr)
		val cipherBytes = context.decodeBase64(blobJson.getString("c"))

		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
		val decryptedJson = JSONObject(String(cipher.doFinal(cipherBytes), Charsets.UTF_8))

		val skipIndices = HashSet<Int>()
		decryptedJson.optJSONArray("s")?.let { skipArr ->
			for (i in 0 until skipArr.length()) {
				val idx = skipArr.optInt(i, -1)
				if (idx >= 0) skipIndices.add(idx)
			}
		}

		val pArray = decryptedJson.optJSONArray("p") ?: return emptyList()
		val urls = ArrayList<String>(pArray.length())
		for (i in 0 until pArray.length()) {
			if (i in skipIndices) continue
			val url = pArray.optString(i).trim()
			if (isValidImageUrl(url)) {
				urls.add(normalizeUrl(url))
			}
		}
		return urls
	}

	fun extractTsReaderImages(context: MangaLoaderContext, script: String): List<String> {
		val rawPayload = extractCallJsonObject(script, "ts_reader.run(") ?: return emptyList()
		val sanitized = rawPayload
			.replace(TRAILING_ARRAY_COMMA_REGEX, "]")
			.replace(TRAILING_OBJ_COMMA_REGEX, "}")
		val root = JSONObject(sanitized)
		val sources = root.optJSONArray("sources") ?: return emptyList()
		val urls = ArrayList<String>()
		for (s in 0 until sources.length()) {
			val sourceObj = sources.optJSONObject(s) ?: continue
			val images = sourceObj.optJSONArray("images") ?: continue
			for (i in 0 until images.length()) {
				val raw = images.optString(i).replace("\\/", "/").replace("\\", "").trim()
				if (raw.isEmpty() || raw.contains("readerarea.svg", ignoreCase = true)) continue
				val candidate = if (
					!raw.startsWith("http://") &&
					!raw.startsWith("https://") &&
					!raw.startsWith("//") &&
					!raw.startsWith("/")
				) {
					runCatching { String(context.decodeBase64(raw), Charsets.UTF_8).trim() }.getOrDefault(raw)
				} else {
					raw
				}
				if (isValidImageUrl(candidate)) {
					urls.add(normalizeUrl(candidate))
				}
			}
			if (urls.isNotEmpty()) break
		}
		return urls
	}

	fun isValidImageUrl(url: String): Boolean {
		return url.isNotBlank() &&
			!url.startsWith("data:", ignoreCase = true) &&
			!url.contains("readerarea.svg", ignoreCase = true)
	}

	fun normalizeUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

	fun extractCallJsonObject(text: String, callPrefix: String): String? {
		val prefixIdx = text.indexOf(callPrefix)
		if (prefixIdx == -1) return null
		val objStart = text.indexOf('{', prefixIdx + callPrefix.length)
		if (objStart == -1) return null
		var depth = 0
		var inString = false
		var escaped = false
		for (i in objStart until text.length) {
			val ch = text[i]
			if (inString) {
				if (escaped) {
					escaped = false
				} else if (ch == '\\') {
					escaped = true
				} else if (ch == '"') {
					inString = false
				}
				continue
			}
			when (ch) {
				'"' -> inString = true
				'{' -> depth++
				'}' -> {
					depth--
					if (depth == 0) {
						return text.substring(objStart, i + 1)
					}
				}
			}
		}
		return null
	}

	fun unpackDeanEdwards(packedScript: String): String? {
		val args = findPackedScriptArgs(packedScript) ?: return null
		var payload = args.payload
		val base = args.base
		if (base !in 2..62) return null
		val dictionary = args.symtab.split("|")
		val decodedLookup = HashMap<String, String>(dictionary.size)
		for (i in dictionary.indices) {
			val word = dictionary[i]
			if (word.isNotEmpty()) {
				decodedLookup[encodeBase(i, base)] = word
			}
		}
		payload = WORD_TOKEN_REGEX.replace(payload) { token ->
			decodedLookup[token.value] ?: token.value
		}
		return payload
			.replace("\\/", "/")
			.replace("\\'", "'")
			.replace("\\\"", "\"")
	}

	private class PackedArgs(val payload: String, val base: Int, val symtab: String)

	private fun findPackedScriptArgs(text: String): PackedArgs? {
		val len = text.length
		var searchFrom = 0
		while (searchFrom < len) {
			val braceIdx = text.indexOf('}', searchFrom)
			if (braceIdx == -1) return null
			searchFrom = braceIdx + 1

			var i = skipWs(text, braceIdx + 1)
			if (i >= len || text[i] != '(') continue
			i = skipWs(text, i + 1)
			if (i >= len || (text[i] != '\'' && text[i] != '"')) continue

			val payloadEnd = findJsStringEnd(text, i) ?: continue
			val payload = text.substring(i + 1, payloadEnd)

			i = skipWs(text, payloadEnd + 1)
			if (i >= len || text[i] != ',') continue
			i = skipWs(text, i + 1)

			val baseStart = i
			while (i < len && text[i].isDigit()) i++
			if (i == baseStart) continue
			val base = text.substring(baseStart, i).toIntOrNull() ?: continue

			i = skipWs(text, i)
			if (i >= len || text[i] != ',') continue
			i = skipWs(text, i + 1)

			val countStart = i
			while (i < len && text[i].isDigit()) i++
			if (i == countStart) continue

			i = skipWs(text, i)
			if (i >= len || text[i] != ',') continue
			i = skipWs(text, i + 1)
			if (i >= len || (text[i] != '\'' && text[i] != '"')) continue

			val symtabEnd = findJsStringEnd(text, i) ?: continue
			val symtab = text.substring(i + 1, symtabEnd)

			i = skipWs(text, symtabEnd + 1)
			if (!text.startsWith(".split", i)) continue

			return PackedArgs(payload, base, symtab)
		}
		return null
	}

	private fun skipWs(text: String, start: Int): Int {
		var i = start
		while (i < text.length && text[i].isWhitespace()) i++
		return i
	}

	private fun findJsStringEnd(text: String, quoteIdx: Int): Int? {
		val quote = text[quoteIdx]
		var i = quoteIdx + 1
		while (i < text.length) {
			val ch = text[i]
			if (ch == '\\') {
				i += 2
			} else if (ch == quote) {
				return i
			} else {
				i++
			}
		}
		return null
	}

	private fun encodeBase(value: Int, radix: Int): String {
		if (value == 0) return "0"
		var n = value
		val sb = StringBuilder()
		while (n > 0) {
			sb.append(BASE62_ALPHABET[n % radix])
			n /= radix
		}
		return sb.reverse().toString()
	}
}
