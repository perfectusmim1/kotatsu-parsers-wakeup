package org.koitharu.kotatsu.parsers.site.mangareader.tr

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.net.URLDecoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGACIX", "Mangacix", "tr")
internal class Mangacix(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGACIX, 16) {

	override val configKeyDomain = ConfigKey.Domain("mangacix.net")

	init {
		setFirstPage(1)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val json = apiGet("https://$domain/secure/bootstrap-data")
		val rawData = json.getStringOrNull("data")
		val tags = if (rawData != null) {
			val decoded = URLDecoder.decode(
				Base64.getDecoder().decode(rawData).decodeToString(),
				"UTF-8",
			)
			val genres = JSONObject(decoded)
				.optJSONObject("settings")
				?.optJSONArray("browse.genres")
				?: JSONArray()
			buildSet(genres.length()) {
				for (i in 0 until genres.length()) {
					val key = genres.optString(i).trim()
					if (key.isNotEmpty()) {
						add(MangaTag(key = key, title = key.toTitleCase(sourceLocale), source = source))
					}
				}
			}
		} else {
			emptySet()
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/secure/titles?perPage=")
			append(pageSize)
			append("&page=")
			append(page)
			append("&order=")
			append(
				when (order) {
					SortOrder.UPDATED -> "updated_at:desc"
					SortOrder.NEWEST -> "created_at:desc"
					SortOrder.RATING -> "user_score:desc"
					SortOrder.ALPHABETICAL -> "name:asc"
					else -> "popularity:desc"
				},
			)
			if (!filter.query.isNullOrEmpty()) {
				append("&query=")
				append(filter.query.urlEncoded())
			}
			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				append(filter.tags.joinToString(",") { it.key.urlEncoded() })
			}
			if (filter.year != 0) {
				append("&released=")
				append(filter.year)
				append(',')
				append(filter.year)
			}
		}
		val data = apiGet(url)
			.optJSONObject("pagination")
			?.optJSONArray("data")
			?: return emptyList()
		return data.mapJSONNotNull(::parseTitleItem)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val titleId = extractTitleId(manga.url)
		val titleObj = apiGet("https://$domain/secure/titles/$titleId").getJSONObject("title")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val resolvedTitle = titleObj.getStringOrNull("name") ?: manga.title
		val episodes = titleObj.optJSONArray("episodes") ?: JSONArray()
		val chapters = episodes.mapJSONNotNull { ep ->
			val epNumStr = ep.getStringOrNull("episode_number") ?: return@mapJSONNotNull null
			val epNum = epNumStr.toFloatOrNull() ?: return@mapJSONNotNull null
			if (epNum < 0f) return@mapJSONNotNull null
			val chUrl = "/books/$titleId/episode/$epNumStr"
			val epName = ep.getStringOrNull("name")?.takeIf { it.length > 2 } ?: "$epNumStr. Bölüm"
			val translator = ep.optJSONArray("translators")
				?.optJSONObject(0)
				?.optJSONObject("translatorDetails")
				?.getStringOrNull("name")
			MangaChapter(
				id = generateUid(chUrl),
				title = epName,
				number = epNum,
				volume = 0,
				url = chUrl,
				uploadDate = dateFormat.parseSafe(ep.getStringOrNull("release_date")),
				scanlator = translator,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }

		val genres = titleObj.optJSONArray("genres")?.mapJSONNotNullToSet { g ->
			val key = g.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
			val displayName = g.getStringOrNull("display_name") ?: key.toTitleCase(sourceLocale)
			MangaTag(key = key, title = displayName, source = source)
		}.orEmpty()

		val authors = titleObj.optJSONArray("credits")?.mapJSONNotNullToSet { c ->
			val dept = c.optJSONObject("pivot")?.getStringOrNull("department")
			if (dept == "writing") c.getStringOrNull("name") else null
		}.orEmpty()

		val altTitles = setOfNotNull(
			titleObj.getStringOrNull("name_english"),
			titleObj.getStringOrNull("name_romanji"),
			titleObj.getStringOrNull("original_title"),
		).filterNotTo(HashSet()) { it.equals(resolvedTitle, ignoreCase = true) }

		val state = when {
			titleObj.opt("series_ended") == true -> MangaState.FINISHED
			titleObj.opt("series_ended") == false -> MangaState.ONGOING
			else -> null
		}

		return manga.copy(
			title = resolvedTitle,
			altTitles = altTitles,
			description = titleObj.getStringOrNull("description"),
			coverUrl = titleObj.getStringOrNull("poster") ?: manga.coverUrl,
			tags = genres,
			authors = authors,
			state = state,
			chapters = chapters,
			contentRating = if (titleObj.getBooleanOrDefault("adult", false) || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val titleId = extractTitleId(chapter.url)
		val epNumStr = chapter.url.substringAfterLast('/')
		val epNum = epNumStr.toFloatOrNull() ?: chapter.number
		val titleObj = apiGet("https://$domain/secure/titles/$titleId?episodeNumber=$epNumStr").getJSONObject("title")
		val imagesArr = titleObj.optJSONArray("episode_images") ?: return emptyList()
		val filtered = imagesArr.mapJSONNotNull { img ->
			val imgEp = img.getFloatOrDefault("episode_number", epNum)
			if (imgEp == epNum) img else null
		}
		val primaryTranslator = filtered.firstNotNullOfOrNull { it.opt("translatorId")?.takeIf { v -> v !== JSONObject.NULL }?.toString() }
		val selected = if (primaryTranslator != null) {
			filtered.filter { it.opt("translatorId")?.toString() == primaryTranslator }
		} else {
			filtered
		}
		return selected
			.sortedBy { it.getIntOrDefault("item_order", 0) }
			.mapNotNull { img ->
				val imgUrl = img.getStringOrNull("url") ?: return@mapNotNull null
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				)
			}
	}

	private fun parseTitleItem(jo: JSONObject): Manga? {
		val id = jo.getIntOrDefault("id", -1)
		if (id <= 0) return null
		val name = jo.getStringOrNull("name") ?: return null
		val href = "/books/$id"
		val rating = jo.getFloatOrDefault("mal_vote_average", -1f)
			.takeIf { it > 0f }
			?.div(10f)
			?: jo.getFloatOrDefault("tmdb_vote_average", -1f).takeIf { it > 0f }?.div(10f)
			?: RATING_UNKNOWN
		val altTitles = setOfNotNull(
			jo.getStringOrNull("name_english"),
			jo.getStringOrNull("name_romanji"),
		).filterNotTo(HashSet()) { it.equals(name, ignoreCase = true) }

		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = "https://$domain$href",
			coverUrl = jo.getStringOrNull("poster"),
			title = name,
			altTitles = altTitles,
			rating = rating,
			tags = emptySet(),
			authors = emptySet(),
			state = null,
			source = source,
			contentRating = if (jo.getBooleanOrDefault("adult", false) || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	private suspend fun apiGet(url: String): JSONObject {
		val headers = Headers.Builder()
			.add("Accept", "application/json")
			.add("X-E-H", buildXehHeader(url))
			.build()
		return webClient.httpGet(url, headers).parseJson()
	}

	private fun extractTitleId(url: String): Long {
		return Regex("""/(?:books|titles)/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
			?: url.trim('/').substringAfterLast('/').toLong()
	}

	private fun buildXehHeader(url: String): String {
		val query = url.substringAfter('?', "")
		val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, GCMParameterSpec(128, iv))
		val encrypted = cipher.doFinal(("{version}$query").toByteArray(Charsets.UTF_8))
		val encoder = Base64.getEncoder()
		return "${encoder.encodeToString(encrypted)}.${encoder.encodeToString(iv)}"
	}

	private companion object {
		private val SECRET_KEY = SecretKeySpec(
			"i4C7R2fXGocdYgFLzCbDlsJjukf8G58b".toByteArray(Charsets.ISO_8859_1),
			"AES",
		)
	}
}
