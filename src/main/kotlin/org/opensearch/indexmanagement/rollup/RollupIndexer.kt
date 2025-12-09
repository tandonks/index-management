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
import org.opensearch.common.io.stream.BytesStreamOutput
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
import org.opensearch.search.aggregations.metrics.InternalCardinality
import org.opensearch.search.aggregations.metrics.InternalMax
import org.opensearch.search.aggregations.metrics.InternalMin
import org.opensearch.search.aggregations.metrics.InternalSum
import org.opensearch.search.aggregations.metrics.InternalValueCount
import org.opensearch.transport.RemoteTransportException
import org.opensearch.transport.client.Client

@Suppress("ThrowsCount", "CyclomaticComplexMethod")
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
                    stats = stats.copy(indexTimeInMillis = stats.indexTimeInMillis + bulkResponse.took.millis)
                    val retryableFailures = mutableListOf<BulkItemResponse>()
                    (bulkResponse.items ?: arrayOf()).filter { it.isFailed }.forEach { failedResponse ->
                        if (failedResponse.status() == RestStatus.TOO_MANY_REQUESTS) {
                            retryableFailures.add(failedResponse)
                        } else {
                            nonRetryableFailures.add(failedResponse)
                        }
                    }
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

    // NOTE: Tier-N Rollup Support (Rolling up rollup indices)
    // When the source index is a rollup index, cardinality sketches need special handling:
    // 1. The aggregation will return InternalCardinality with merged sketches from source rollup docs
    // 2. We extract and serialize the merged sketch (same as Tier-1)
    // 3. For manual sketch merging (if needed), use SketchMerger utility:
    //    - SketchMerger.deserializeSketch() to read sketches from rollup docs
    //    - SketchMerger.mergeSketches() to combine multiple sketches
    //    - SketchMerger.serializeSketch() to store the merged result
    // The current implementation handles both Tier-1 and Tier-N transparently through OpenSearch's
    // aggregation framework, which automatically merges sketches when aggregating over rollup indices.

    @Suppress("CyclomaticComplexMethod")
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

                    is InternalCardinality -> {
                        // Store only the sketch for multi-tier rollup support
                        // The cardinality estimate can be computed from the sketch when needed
                        aggResults[it.name] = extractHLLSketch(it)
                    }

                    else -> error("Found aggregation in composite result that is not supported [${it.type} - ${it.name}]")
                }
            }
            logger.info("Computed aggResults: {}", aggResults)
            mapOfKeyValues.putAll(aggResults)
            val targetIndexResolvedName = RollupFieldValueExpressionResolver.resolve(job, job.targetIndex)
            val indexRequest =
                IndexRequest(targetIndexResolvedName)
                    .id(documentId)
                    .source(mapOfKeyValues, XContentType.JSON)
            requests.add(indexRequest)
        }
        return requests
    }

    /**
     * Extracts and serializes the HLL++ sketch from an InternalCardinality aggregation result.
     *
     * The HLL field type (from OpenSearch HLL PR #20129) expects sketches in AbstractHyperLogLogPlusPlus
     * format, which is what this method produces. The HllFieldMapper will deserialize these bytes using
     * AbstractHyperLogLogPlusPlus.readFrom() during indexing and HllFieldData.getSketch() during aggregation.
     *
     * @param cardinality The InternalCardinality aggregation result containing the HLL++ sketch
     * @return Raw byte array of the serialized HLL++ sketch
     * @throws IllegalStateException if sketch extraction or serialization fails
     */
    private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
        // Extract the HLL++ sketch from InternalCardinality and serialize it
        // The HLL field type expects raw sketch bytes from AbstractHyperLogLogPlusPlus.writeTo()
        val output = BytesStreamOutput()
        // Access the sketch through reflection since it's a private field
        val sketchField = cardinality.javaClass.getDeclaredField("counts")
        sketchField.isAccessible = true
        val sketch = sketchField.get(cardinality) as org.opensearch.search.aggregations.metrics.AbstractHyperLogLogPlusPlus

        // Log sketch details before serialization
        // Use the public precision() method from AbstractCardinalityAlgorithm
        val actualPrecision = sketch.precision()
        logger.info(
            "Extracting HLL sketch for field {}: actualPrecision={}, cardinalityValue={}, sketchClass={}",
            cardinality.name,
            actualPrecision,
            cardinality.value,
            sketch.javaClass.simpleName,
        )

        // Serialize just the sketch, not the full InternalCardinality
        // AbstractHyperLogLogPlusPlus.writeTo() requires bucket ordinal (0 for single bucket)
        sketch.writeTo(0L, output)
        val bytes = output.bytes().toBytesRef().bytes

        // Log serialized bytes details
        logger.info(
            "Serialized HLL sketch for field {}: byteLength={}, firstByte={} (precision marker)",
            cardinality.name,
            bytes.size,
            bytes[0],
        )

        bytes
    } catch (e: Exception) {
        logger.error("Failed to extract HLL++ sketch from cardinality aggregation: ${e.message}", e)
        throw IllegalStateException("Failed to serialize HLL++ sketch for storage", e)
    }
}

sealed class RollupIndexResult {
    data class Success(val stats: RollupStats) : RollupIndexResult()

    data class Failure(
        val message: String = "An error occurred while indexing to the rollup target index",
        val cause: Exception,
    ) : RollupIndexResult()
}
