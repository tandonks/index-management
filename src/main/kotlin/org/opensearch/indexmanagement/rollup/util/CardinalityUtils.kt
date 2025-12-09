/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup.util

import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.RollupMetrics
import org.opensearch.indexmanagement.rollup.model.metric.Cardinality

object CardinalityUtils {
    const val HLL_FIELD_TYPE = "hll"
    const val HLL_SKETCH_SUFFIX = "hll_sketch"

    /**
     * Builds field mappings for cardinality metrics in a rollup job.
     * Returns a map of field names to their mapping properties.
     */
    fun buildCardinalityFieldMappings(rollupMetrics: List<RollupMetrics>): Map<String, Map<String, Any>> {
        val properties = mutableMapOf<String, Map<String, Any>>()

        rollupMetrics.forEach { metric ->
            metric.metrics.forEach { m ->
                if (m is Cardinality) {
                    val sketchField = "${metric.targetField}.$HLL_SKETCH_SUFFIX"
                    val precision = Cardinality.precisionFromThreshold(m.precisionThreshold)
                    properties[sketchField] = mapOf(
                        "type" to HLL_FIELD_TYPE,
                        "precision" to precision,
                    )
                }
            }
        }

        return properties
    }

    /**
     * Builds metadata for cardinality metrics to be stored in _meta.rollup section.
     * Returns a list of metric metadata maps.
     */
    fun buildCardinalityMetadata(rollupMetrics: List<RollupMetrics>): List<Map<String, Any>> {
        val metricsMetadata = mutableListOf<Map<String, Any>>()

        rollupMetrics.forEach { rollupMetric ->
            val metricsList = mutableListOf<Map<String, Any>>()

            rollupMetric.metrics.forEach { metric ->
                if (metric is Cardinality) {
                    metricsList.add(
                        mapOf(
                            "cardinality" to mapOf(
                                "precision_threshold" to metric.precisionThreshold,
                            ),
                        ),
                    )
                }
            }

            if (metricsList.isNotEmpty()) {
                metricsMetadata.add(
                    mapOf(
                        "source_field" to rollupMetric.sourceField,
                        "target_field" to rollupMetric.targetField,
                        "metrics" to metricsList,
                    ),
                )
            }
        }

        return metricsMetadata
    }

    /**
     * Extracts precision value from rollup metadata for a specific field.
     * Returns null if precision is not found.
     */
    @Suppress("ReturnCount")
    fun getPrecisionFromMetadata(metadata: Map<*, *>, fieldName: String): Int? {
        val metrics = metadata["metrics"] as? List<*> ?: return null

        metrics.forEach { metricObj ->
            val metric = metricObj as? Map<*, *> ?: return@forEach
            if (metric["source_field"] == fieldName || metric["target_field"] == fieldName) {
                val metricsList = metric["metrics"] as? List<*> ?: return@forEach
                metricsList.forEach { m ->
                    val metricMap = m as? Map<*, *> ?: return@forEach
                    val cardinality = metricMap["cardinality"] as? Map<*, *>
                    if (cardinality != null) {
                        return cardinality["precision"] as? Int
                    }
                }
            }
        }

        return null
    }

    /**
     * Validates that precision values are compatible between source and target rollup indices.
     * Returns null if validation passes, or an error message if validation fails.
     */
    @Suppress("ReturnCount")
    fun validatePrecisionCompatibility(
        sourceMetadata: Map<*, *>?,
        targetMetrics: List<RollupMetrics>,
    ): String? {
        if (sourceMetadata == null) {
            // No source metadata means this is a Tier-1 rollup, no validation needed
            return null
        }

        targetMetrics.forEach { targetMetric ->
            targetMetric.metrics.forEach { metric ->
                if (metric is Cardinality) {
                    val sourcePrecision = getPrecisionFromMetadata(sourceMetadata, targetMetric.sourceField)
                    val targetPrecision = Cardinality.precisionFromThreshold(metric.precisionThreshold)

                    if (sourcePrecision != null && sourcePrecision != targetPrecision) {
                        return "Precision mismatch for field ${targetMetric.sourceField}: " +
                            "source has precision $sourcePrecision but target specifies $targetPrecision " +
                            "(from precision_threshold ${metric.precisionThreshold}). " +
                            "Multi-tier rollups require matching precision values."
                    }
                }
            }
        }

        return null
    }

    /**
     * Gets the sketch field name for a given target field.
     */
    fun getSketchFieldName(targetField: String): String = "$targetField.$HLL_SKETCH_SUFFIX"

    /**
     * Checks if a rollup job has any cardinality metrics.
     */
    fun hasCardinalityMetrics(rollup: Rollup): Boolean = rollup.metrics.any { rollupMetric ->
        rollupMetric.metrics.any { it is Cardinality }
    }
}
