package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.*
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

public class RelatedMangaFinder(
    private val parsers: Collection<MangaParser>,
) {

    public suspend operator fun invoke(seed: Manga): List<Manga> = withContext(Dispatchers.Default) {
        coroutineScope {
            parsers.singleOrNull()?.let { parser ->
                findRelatedImpl(this, parser, seed)
            } ?: parsers.map { parser ->
                async {
                    findRelatedImpl(this, parser, seed)
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun findRelatedImpl(scope: CoroutineScope, parser: MangaParser, seed: Manga): List<Manga> {
        val queries = buildMangaSearchQueries(seed.title, seed.altTitles)
        if (queries.isEmpty()) {
            return emptyList()
        }
        val seedTitles = buildList(1 + seed.altTitles.size) {
            add(seed.title)
            addAll(seed.altTitles)
        }
        val sortOrder = if (SortOrder.RELEVANCE in parser.availableSortOrders) {
            SortOrder.RELEVANCE
        } else {
            parser.availableSortOrders.first()
        }
        val scoredById = LinkedHashMap<Long, Pair<Manga, Float>>()
        val batches = queries.map { keyword ->
            scope.async {
                runCatchingCancellable {
                    parser.getList(
                        0,
                        sortOrder,
                        MangaListFilter(query = keyword),
                    )
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        for (batch in batches) {
            for (candidate in batch) {
                if (candidate.id == seed.id || candidate.url == seed.url) {
                    continue
                }
                val score = mangaTitleMatchDistance(candidate.title, candidate.altTitles, seedTitles)
                if (!score.isFinite()) {
                    continue
                }
                val existing = scoredById[candidate.id]
                if (existing == null || score < existing.second) {
                    scoredById[candidate.id] = candidate to score
                }
            }
        }
        return scoredById.values
            .sortedBy { it.second }
            .map { it.first }
    }
}
