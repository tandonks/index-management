/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup.util

import org.apache.logging.log4j.LogManager
import org.opensearch.common.io.stream.BytesStreamOutput
import org.opensearch.core.common.io.stream.BytesStreamInput
import org.opensearch.search.aggregations.metrics.InternalCardinality
import java.util.Base64

/**
 * Utility class for merging HLL++ sketches from rollup indices.
 * Used in Tier-N rollups where sketches from multiple rollup documents need to be merged.
 */
object SketchMerger {
    private val logger = LogManager.getLogger(javaClass)

    /**
     * Deserializes a Base64-encoded sketch string back to an InternalCardinality object.
     *
     * @param sketchString Base64-encoded serialized InternalCardinality
     * @param fieldName Optional field name for better error messages
     * @param docId Optional document ID for better error messages
     * @return Deserialized InternalCardinality object containing the HLL++ sketch
     * @throws IllegalStateException if deserialization fails
     */
    fun deserializeSketch(sketchString: String, fieldName: String = "unknown", docId: String? = null): InternalCardinality = try {
        val bytes = Base64.getDecoder().decode(sketchString)
        val input = BytesStreamInput(bytes)
        InternalCardinality(input)
    } catch (e: Exception) {
        val errorMsg = CardinalityValidation.createDeserializationErrorMessage(fieldName, docId, e)
        logger.error(errorMsg, e)
        throw IllegalStateException(errorMsg, e)
    }

    /**
     * Merges multiple HLL++ sketches into a single sketch.
     * This is used when rolling up rollup indices (Tier-N rollups).
     *
     * @param sketches List of InternalCardinality objects to merge
     * @param fieldName Optional field name for better error messages
     * @return Merged InternalCardinality object
     * @throws IllegalArgumentException if sketches list is empty
     * @throws IllegalStateException if merge fails
     */
    @Suppress("UnusedParameter")
    fun mergeSketches(sketches: List<InternalCardinality>, name: String, fieldName: String = "unknown"): InternalCardinality {
        require(sketches.isNotEmpty()) { "Cannot merge empty list of sketches for field '$fieldName'" }

        return try {
            if (sketches.size == 1) {
                // If only one sketch, return it directly (with updated name if needed)
                sketches.first()
            } else {
                // Merge all sketches using OpenSearch's reduce mechanism
                // InternalCardinality.reduce() handles the HLL++ sketch merging
                val reduced = sketches.first().reduce(sketches, null)
                reduced as InternalCardinality
            }
        } catch (e: Exception) {
            val errorMsg = CardinalityValidation.createMergeErrorMessage(fieldName, sketches.size, e)
            logger.error(errorMsg, e)
            throw IllegalStateException(errorMsg, e)
        }
    }

    /**
     * Serializes an InternalCardinality object to a Base64-encoded string.
     * This is the same serialization used in Tier-1 rollups.
     *
     * @param cardinality The InternalCardinality object to serialize
     * @return Base64-encoded string representation
     * @throws IllegalStateException if serialization fails
     */
    fun serializeSketch(cardinality: InternalCardinality): String = try {
        val output = BytesStreamOutput()
        cardinality.writeTo(output)
        val bytes = output.bytes().toBytesRef().bytes
        Base64.getEncoder().encodeToString(bytes)
    } catch (e: Exception) {
        logger.error("Failed to serialize HLL++ sketch: ${e.message}", e)
        throw IllegalStateException("Failed to serialize HLL++ sketch", e)
    }

    /**
     * Merges sketch strings directly without intermediate deserialization.
     * Convenience method that combines deserialize → merge → serialize.
     *
     * @param sketchStrings List of Base64-encoded sketch strings
     * @param name The name for the merged cardinality aggregation
     * @param fieldName Optional field name for better error messages
     * @return Base64-encoded merged sketch string
     * @throws IllegalArgumentException if sketchStrings list is empty
     * @throws IllegalStateException if any operation fails
     */
    fun mergeSketchStrings(sketchStrings: List<String>, name: String, fieldName: String = "unknown"): String {
        require(sketchStrings.isNotEmpty()) { "Cannot merge empty list of sketch strings for field '$fieldName'" }

        val sketches = sketchStrings.mapIndexed { index, sketchString ->
            deserializeSketch(sketchString, fieldName, "sketch_$index")
        }
        val merged = mergeSketches(sketches, name, fieldName)
        return serializeSketch(merged)
    }
}
