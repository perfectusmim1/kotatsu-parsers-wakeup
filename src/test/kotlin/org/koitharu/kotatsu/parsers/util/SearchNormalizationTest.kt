package org.koitharu.kotatsu.parsers.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SearchNormalizationTest {

	@Test
	fun normalizeSearchQueryNormalizesPunctuationAndPreservesUnderscores() {
		assertEquals("Gear that s Way Too Good", "Gear that’s Way Too Good!".normalizeSearchQuery())
		assertEquals("Re Zero Starting Life", "Re:Zero - Starting Life".normalizeSearchQuery())
		assertEquals("A B Test", "A/B ~ Test?!".normalizeSearchQuery())
		assertEquals("title_name", "title_name".normalizeSearchQuery())
		assertEquals("俺だけレベルアップな件", "「俺だけレベルアップな件」".normalizeSearchQuery())
		assertEquals("", "   --- : !?   ".normalizeSearchQuery())
	}

	@Test
	fun sanitizeMangaSearchTitleStripsNoiseTagsWithoutManglingTitles() {
		assertEquals("Gear that s Way Too Good", "Gear that’s Way Too Good (Raw)".sanitizeMangaSearchTitle())
		assertEquals("Gear that s Way Too Good", "Gear that’s Way Too Good [RAW]".sanitizeMangaSearchTitle())
		assertEquals("Gear that s Way Too Good", "Gear that's Way Too Good Raw".sanitizeMangaSearchTitle())
		assertEquals("Solo Leveling", "Solo Leveling (Official) [Completed]".sanitizeMangaSearchTitle())
		assertEquals("Mi Timida Novia", "[COLOR] Mi Timida Novia [END]".sanitizeMangaSearchTitle())
		assertEquals("Some Series", "Some Series - Uncensored (Official, Colored)".sanitizeMangaSearchTitle())

		// Legitimate title words must remain intact
		assertEquals("The Beginning After the End", "The Beginning After the End".sanitizeMangaSearchTitle())
		assertEquals("World s End Harem", "World’s End Harem".sanitizeMangaSearchTitle())
		assertEquals("Raw Hero", "Raw Hero".sanitizeMangaSearchTitle())
		assertEquals("StRAWberry 100", "StRAWberry 100%".sanitizeMangaSearchTitle())
	}

	@Test
	fun buildMangaSearchQueriesIncludesTitlePrefixAndAltTitles() {
		val queries = buildMangaSearchQueries(
			title = "My Super Long Title: Return of the Hero (Official) [End]",
			altTitles = listOf("Ore no Title (Raw)", "My Super Long Title"),
			maxQueries = 6,
		)
		assertEquals(
			listOf(
				"My Super Long Title Return of the Hero",
				"My Super Long Title",
				"Ore no Title",
				"My Super Long",
			),
			queries,
		)
	}

	@Test
	fun mangaTitleMatchDistanceIgnoresPunctuationAndNoiseTags() {
		val exact = mangaTitleMatchDistance(
			candidateTitle = "Solo Leveling (Official) [Completed]",
			candidateAltTitles = emptySet(),
			seedTitles = listOf("Solo Leveling!"),
		)
		assertEquals(0f, exact)

		val altMatch = mangaTitleMatchDistance(
			candidateTitle = "Different Primary",
			candidateAltTitles = listOf("Na Honjaman Level Up [Raw]"),
			seedTitles = listOf("Solo Leveling", "Na Honjaman Level Up"),
		)
		assertEquals(0f, altMatch)

		assertTrue(
			isSimilarMangaTitle(
				candidateTitle = "Beginning After the End",
				candidateAltTitles = emptySet(),
				seedTitles = listOf("The Beginning After the End"),
			),
		)

		assertFalse(
			isSimilarMangaTitle(
				candidateTitle = "Totally Unrelated Romance Manga",
				candidateAltTitles = emptySet(),
				seedTitles = listOf("Solo Leveling"),
			),
		)
	}

	@Test
	fun containsNormalizedQueryMatchesAcrossPunctuation() {
		assertTrue("Gear that’s Way Too Good (Raw)".containsNormalizedQuery("Gear that s Way Too Good"))
		assertTrue("Re:Zero - Starting Life".containsNormalizedQuery("Re Zero Starting"))
		assertFalse("Solo Leveling".containsNormalizedQuery("One Piece"))
	}
}
