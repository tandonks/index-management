/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup

import org.apache.logging.log4j.LogManager
import org.opensearch.ExceptionsHelper
import org.opensearch.action.DocWriteRequest
import org.opensearch.action.bulk.BackoffPolicy
import org.opensearch.action.bulk.BulkItemResponse
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.XContentType
import org.opensearch.core.rest.RestStatus
import org.opensearch.indexmanagement.opensearchapi.retry
import org.opensearch.indexmanagement.opensearchapi.suspendUntil
import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.RollupStats
import org.opensearch.indexmanagement.rollup.settings.RollupSettings.Companion.ROLLUP_INGEST_BACKOFF_COUNT
import org.opensearch.indexmanagement.rollup.settings.RollupSettings.Companion.ROLLUP_INGEST_BACKOFF_MILLIS
import org.opensearch.indexmanagement.rollup.util.RollupFieldValueExpressionResolver
import org.opensearch.indexmanagement.rollup.util.getInitialDocValues
import org.opensearch.indexmanagement.util.IndexUtils.Companion.ODFE_MAGIC_NULL
import org.opensearch.indexmanagement.util.IndexUtils.Companion.hashToFixedSize
import org.opensearch.search.aggregations.bucket.composite.InternalComposite
import org.opensearch.search.aggregations.metrics.InternalAvg
import org.opensearch.search.aggregations.metrics.InternalMax
import org.opensearch.search.aggregations.metrics.InternalMin
import org.opensearch.search.aggregations.metrics.InternalSum
import org.opensearch.search.aggregations.metrics.InternalValueCount
import org.opensearch.transport.RemoteTransportException
import org.opensearch.transport.client.Client

@Suppress("ThrowsCount", "ComplexMethod")
class RollupIndexer(
    settings: Settings,
    clusterService: ClusterService,
    private val client: Client,
) {
    private val logger = LogManager.getLogger(javaClass)

    @Volatile private var retryIngestPolicy =
        BackoffPolicy.constantBackoff(ROLLUP_INGEST_BACKOFF_MILLIS.get(settings), ROLLUP_INGEST_BACKOFF_COUNT.get(settings))

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ROLLUP_INGEST_BACKOFF_MILLIS, ROLLUP_INGEST_BACKOFF_COUNT) { millis, count ->
            retryIngestPolicy = BackoffPolicy.constantBackoff(millis, count)
        }
    }

    @Suppress("ReturnCount")
    suspend fun indexRollups(rollup: Rollup, internalComposite: InternalComposite): RollupIndexResult {
        try {
            // Log all bucket aggregations
            internalComposite.buckets.forEach { bucket ->
                val aggDetails = bucket.aggregations.map { "${it.name}: $it" }.joinToString(", ")
                logger.info("Bucket key: {}, docCount: {}, aggregations: [{}]", bucket.key, bucket.docCount, aggDetails)
            }
            var requestsToRetry = convertResponseToRequests(rollup, internalComposite)
            logger.info("Generated {} index requests for rollup {}", requestsToRetry.size, rollup.id)
            var stats = RollupStats(0, 0, requestsToRetry.size.toLong(), 0, 0)
            val nonRetryableFailures = mutableListOf<BulkItemResponse>()
            if (requestsToRetry.isNotEmpty()) {
                retryIngestPolicy.retry(logger, listOf(RestStatus.TOO_MANY_REQUESTS)) {
                    if (it.seconds >= (Rollup.ROLLUP_LOCK_DURATION_SECONDS / 2)) {
                        throw ExceptionsHelper.convertToOpenSearchException(
                            IllegalStateException("Cannot retry ingestion with a delay more than half of the rollup lock TTL"),
                        )
                    }
                    val bulkRequest = BulkRequest().add(requestsToRetry)
                    val bulkResponse: BulkResponse = client.suspendUntil { bulk(bulkRequest, it) }
                    logger.info("Bulk response: hasFailures={}, took={}ms, items={}", bulkResponse.hasFailures(), bulkResponse.took.millis, bulkResponse.items?.size ?: 0)
                    stats = stats.copy(indexTimeInMillis = stats.indexTimeInMillis + bulkResponse.took.millis)
                    val retryableFailures = mutableListOf<BulkItemResponse>()
                    (bulkResponse.items ?: arrayOf()).forEachIndexed { index, item ->
                        if (item.isFailed) {
                            logger.error("Failed to index document {}: status={}, error={}", index, item.status(), item.failureMessage)
                            if (item.status() == RestStatus.TOO_MANY_REQUESTS) {
                                retryableFailures.add(item)
                            } else {
                                nonRetryableFailures.add(item)
                            }
                        } else {
                            logger.debug("Successfully indexed document {}: id={}, index={}", index, item.id, item.index)
                        }
                    }
                    val successfulDocs = (bulkResponse.items?.size ?: 0) - retryableFailures.size - nonRetryableFailures.size
                    logger.info("Bulk indexing result for rollup {}: successful={}, retryable={}, failed={}", rollup.id, successfulDocs, retryableFailures.size, nonRetryableFailures.size)
                    requestsToRetry = retryableFailures.map { retryableFailure -> bulkRequest.requests()[retryableFailure.itemId] as IndexRequest }

                    if (requestsToRetry.isNotEmpty()) {
                        val retryCause = retryableFailures.first().failure.cause
                        throw ExceptionsHelper.convertToOpenSearchException(retryCause)
                    }
                }
            }
            if (nonRetryableFailures.isNotEmpty()) {
                logger.error("Failed to index ${nonRetryableFailures.size} documents")
                throw ExceptionsHelper.convertToOpenSearchException(nonRetryableFailures.first().failure.cause)
            }
            logger.info("Successfully indexed {} documents for rollup {}", stats.documentsProcessed, rollup.id)

            // Verify documents were actually indexed by querying the target index
            try {
                val targetIndexName = RollupFieldValueExpressionResolver.resolve(rollup, rollup.targetIndex)
                val searchRequest = org.opensearch.action.search.SearchRequest(targetIndexName)
                    .source(org.opensearch.search.builder.SearchSourceBuilder().size(5).query(org.opensearch.index.query.QueryBuilders.matchAllQuery()))
                val searchResponse: org.opensearch.action.search.SearchResponse = client.suspendUntil { search(searchRequest, it) }
                logger.info(
                    "Verification query for rollup {} target index {}: total hits={}, returned docs={}",
                    rollup.id, targetIndexName, searchResponse.hits.totalHits?.value ?: 0, searchResponse.hits.hits.size,
                )
                searchResponse.hits.hits.take(3).forEachIndexed { index, hit ->
                    logger.info("Sample doc {}: id={}, source={}", index, hit.id, hit.sourceAsString)
                }
            } catch (e: Exception) {
                logger.warn("Failed to verify indexed documents for rollup {}: {}", rollup.id, e.message)
            }

            return RollupIndexResult.Success(stats)
        } catch (e: RemoteTransportException) {
            logger.error(e.message, e.cause)
            return RollupIndexResult.Failure(cause = ExceptionsHelper.unwrapCause(e) as Exception)
        } catch (e: Exception) {
            logger.error(e.message, e.cause)
            return RollupIndexResult.Failure(cause = e)
        }
    }

    // TODO: Doc counts for aggregations are showing the doc counts of the rollup docs and not the raw data which is expected...
    //  Elastic has a PR for a _doc_count mapping which we might be able to use but its in PR and they could change it
    //  Is there a way we can overwrite doc_count? On request/response? https://github.com/elastic/elasticsearch/pull/58339
    //  Perhaps try to save it in what will most likely be the correct way for that PR so we can reuse in the future?
    @Suppress("ComplexMethod")
    fun convertResponseToRequests(job: Rollup, internalComposite: InternalComposite): List<DocWriteRequest<*>> {
        val requests = mutableListOf<DocWriteRequest<*>>()
        internalComposite.buckets.forEach {
            val docId = job.id + "#" + it.key.entries.joinToString("#") { it.value?.toString() ?: ODFE_MAGIC_NULL }
            val documentId = hashToFixedSize(docId)

            val mapOfKeyValues = job.getInitialDocValues(it.docCount)
            val aggResults = mutableMapOf<String, Any?>()
            it.key.entries.forEach { aggResults[it.key] = it.value }
            it.aggregations.forEach {
                when (it) {
                    is InternalSum -> aggResults[it.name] = it.value
                    // TODO: Need to redo the logic in corresponding doXContentBody of InternalMax and InternalMin
                    is InternalMax -> if (it.value.isInfinite()) aggResults[it.name] = null else aggResults[it.name] = it.value
                    is InternalMin -> if (it.value.isInfinite()) aggResults[it.name] = null else aggResults[it.name] = it.value
                    is InternalValueCount -> aggResults[it.name] = it.value
                    is InternalAvg -> aggResults[it.name] = it.value
                    else -> error("Found aggregation in composite result that is not supported [${it.type} - ${it.name}]")
                }
            }
            mapOfKeyValues.putAll(aggResults)
            val targetIndexResolvedName = RollupFieldValueExpressionResolver.resolve(job, job.targetIndex)

            // Log the document content being indexed
            logger.info("Creating index request for rollup {}: docId={}, targetIndex={}, content={}", job.id, documentId, targetIndexResolvedName, mapOfKeyValues)

            val indexRequest =
                IndexRequest(targetIndexResolvedName)
                    .id(documentId)
                    .source(mapOfKeyValues, XContentType.JSON)
            requests.add(indexRequest)
        }
        return requests
    }
}

sealed class RollupIndexResult {
    data class Success(val stats: RollupStats) : RollupIndexResult()

    data class Failure(
        val message: String = "An error occurred while indexing to the rollup target index",
        val cause: Exception,
    ) : RollupIndexResult()
}
