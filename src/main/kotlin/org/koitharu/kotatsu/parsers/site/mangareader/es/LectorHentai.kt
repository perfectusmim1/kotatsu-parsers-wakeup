package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("LECTORHENTAI", "LectorHentai", "es", ContentType.HENTAI)
internal class LectorHentai(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LECTORHENTAI, "lectorhentai.com", pageSize = 25, searchPageSize = 25) {
	override val listUrl = "/tipo/all"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					val normalized = filter.query.normalizeSearchQuery().ifEmpty { filter.query.trim() }
					append(listUrl)
					append("?s=")
					append(normalized.urlEncoded())
					append("&page=")
					append(page.toString())
				}

				else -> {
					append(listUrl)
					append("?order=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.ALPHABETICAL_DESC -> "titlereverse"
							SortOrder.NEWEST -> "latest"
							SortOrder.POPULARITY -> "popular"
							SortOrder.UPDATED -> "update"
							else -> ""
						},
					)

					filter.tags.forEach {
						append("&")
						append("genre[]".urlEncoded())
						append("=")
						append(it.key)
					}

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append("&status=")
							when (it) {
								MangaState.ONGOING -> append("ongoing")
								MangaState.FINISHED -> append("completed")
								MangaState.PAUSED -> append("hiatus")
								else -> append("")
							}
						}
					}

					append("&page=")
					append(page.toString())
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override val selectChapter = ".releases"
	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val isoDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
		val shortEsFormat = SimpleDateFormat("d MMM yyyy", sourceLocale)
		val timeEl = docs.selectFirst("time")
		val uploadDate = timeEl?.attrOrNull("datetime")?.let { isoDateFormat.parseSafe(it) }
			?.takeIf { it > 0L }
			?: timeEl?.text()?.let { shortEsFormat.parseSafe(it).takeIf { d -> d > 0L } ?: dateFormat.parseSafe(it) }
			?: 0L
		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val url = element.selectFirst("a")?.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(url),
				title = element.selectFirst("h2")?.textOrNull(),
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
		return parseInfo(docs, manga, chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = webClient.httpGet(chapterUrl).parseHtml()
		val imageUrls = extractPagesFromScripts(docs)
			.ifEmpty { extractPagesFromHtml(docs) }
		if (imageUrls.isEmpty()) {
			docs.parseFailed("Cannot extract chapter pages")
		}
		return imageUrls.map { rawUrl ->
			val resolvedUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
			MangaPage(
				id = generateUid(resolvedUrl),
				url = resolvedUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun extractPagesFromScripts(docs: Document): List<String> {
		val scripts = docs.select("script")
			.asSequence()
			.map { it.data() }
			.filter { it.contains("ReaderBlob") || it.contains("ts_reader") || it.contains("eval(function") }
			.toList()

		for (scriptData in scripts) {
			val unpacked = if (scriptData.contains("eval(function") && scriptData.contains(".split(")) {
				unpackDeanEdwards(scriptData) ?: scriptData
			} else {
				scriptData
			}

			if (unpacked.contains("ReaderBlob.start(")) {
				val decrypted = runCatchingCancellable { decodeReaderBlob(unpacked) }.getOrNull().orEmpty()
				if (decrypted.isNotEmpty()) {
					return decrypted
				}
			}

			if (unpacked.contains("ts_reader.run(")) {
				val readerUrls = runCatchingCancellable { extractTsReaderImages(unpacked) }.getOrNull().orEmpty()
				if (readerUrls.isNotEmpty()) {
					return readerUrls
				}
			}
		}
		return emptyList()
	}

	private fun decodeReaderBlob(script: String): List<String> {
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
				urls.add(url)
			}
		}
		return urls
	}

	private fun extractTsReaderImages(script: String): List<String> {
		val jsonPayload = extractCallJsonObject(script, "ts_reader.run(") ?: return emptyList()
		val root = JSONObject(jsonPayload)
		val sources = root.optJSONArray("sources") ?: return emptyList()
		val urls = ArrayList<String>()
		for (s in 0 until sources.length()) {
			val sourceObj = sources.optJSONObject(s) ?: continue
			val images = sourceObj.optJSONArray("images") ?: continue
			for (i in 0 until images.length()) {
				val raw = images.optString(i).replace("\\/", "/").trim()
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
					urls.add(candidate)
				}
			}
			if (urls.isNotEmpty()) break
		}
		return urls
	}

	private fun extractPagesFromHtml(docs: Document): List<String> {
		return docs.select(selectPage).mapNotNull { img ->
			val url = img.src()?.trim() ?: return@mapNotNull null
			url.takeIf(::isValidImageUrl)
		}
	}

	private fun isValidImageUrl(url: String): Boolean {
		return url.isNotBlank() &&
			!url.startsWith("data:", ignoreCase = true) &&
			!url.contains("readerarea.svg", ignoreCase = true)
	}

	private fun extractCallJsonObject(text: String, callPrefix: String): String? {
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

	private fun unpackDeanEdwards(packedScript: String): String? {
		val match = PACKED_SCRIPT_REGEX.find(packedScript) ?: return null
		var payload = match.groupValues[2]
		val base = match.groupValues[3].toIntOrNull() ?: return null
		if (base !in 2..62) return null
		val dictionary = match.groupValues[6].split("|")
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

	private companion object {
		private const val BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
		private val PACKED_SCRIPT_REGEX = Regex(
			"""(?s)\}\s*\(\s*(['"])((?:\\.|(?!\1)[^\\])*)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])((?:\\.|(?!\5)[^\\])*)\5\.split""",
		)
		private val WORD_TOKEN_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")
	}
}
