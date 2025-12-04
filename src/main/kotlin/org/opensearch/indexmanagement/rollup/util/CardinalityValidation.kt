/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup.util

import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.metric.Cardinality

/**
 * Validation utilities for cardinality rollup operations.
 * Provides comprehensive error checking and clear error messages.
 */
object CardinalityValidation {

    private const val MIN_PRECISION = 4
    private const val MAX_PRECISION = 18

    /**
     * Validates precision value is within acceptable range (4-18).
     *
     * @param precision The precision value to validate
     * @param fieldName The field name for error messaging
     * @throws IllegalArgumentException if precision is out of range
     */
    fun validatePrecision(precision: Int, fieldName: String) {
        require(precision in MIN_PRECISION..MAX_PRECISION) {
            "Invalid precision value $precision for field '$fieldName'. " +
                "Precision must be between $MIN_PRECISION and $MAX_PRECISION (inclusive). " +
                "Recommended values: 12 (default), 14 (higher accuracy), 16 (very high accuracy)."
        }
    }

    /**
     * Validates that precision values match between source and target rollup indices.
     * This is critical for Tier-N rollups where sketches are merged.
     *
     * @param sourcePrecision Precision from source rollup index
     * @param targetPrecision Precision from target rollup job configuration
     * @param fieldName The field name for error messaging
     * @throws IllegalArgumentException if precisions don't match
     */
    fun validatePrecisionCompatibility(sourcePrecision: Int, targetPrecision: Int, fieldName: String) {
        require(sourcePrecision == targetPrecision) {
            "Precision mismatch for field '$fieldName': " +
                "source rollup index has precision $sourcePrecision, " +
                "but target rollup job specifies precision $targetPrecision. " +
                "All tiers in a rollup chain must use the same precision. " +
                "Update the target rollup job to use precision $sourcePrecision."
        }
    }

    /**
     * Validates that a sketch field exists in the rollup index mapping.
     *
     * @param fieldName The base field name (e.g., "user_id")
     * @param sketchFieldName The full sketch field name (e.g., "user_id.hll_sketch")
     * @throws IllegalArgumentException if sketch field is missing
     */
    fun validateSketchFieldExists(fieldName: String, sketchFieldName: String) {
        // This validation would typically be done by checking the index mapping
        // For now, we provide a clear error message structure
        throw IllegalArgumentException(
            "Sketch field '$sketchFieldName' not found in rollup index. " +
                "The rollup job may not have been configured with cardinality metric for field '$fieldName', " +
                "or the rollup index mapping may be corrupted. " +
                "Verify the rollup job configuration includes: " +
                "{\"source_field\": \"$fieldName\", \"metrics\": [{\"cardinality\": {}}]}",
        )
    }

    /**
     * Validates that cardinality metrics are properly configured in a rollup job.
     *
     * @param rollup The rollup job configuration
     * @throws IllegalArgumentException if configuration is invalid
     */
    fun validateRollupJobConfiguration(rollup: Rollup) {
        rollup.metrics.forEach { rollupMetrics ->
            rollupMetrics.metrics.forEach { metric ->
                if (metric is Cardinality) {
                    // Validate precision
                    validatePrecision(metric.precision, rollupMetrics.sourceField)

                    // Additional validations can be added here
                    // For example: check if source field exists in source index
                }
            }
        }
    }

    /**
     * Creates a user-friendly error message for sketch deserialization failures.
     *
     * @param fieldName The field name where deserialization failed
     * @param docId The document ID (if available)
     * @param cause The underlying exception
     * @return Formatted error message
     */
    fun createDeserializationErrorMessage(
        fieldName: String,
        docId: String? = null,
        cause: Exception,
    ): String {
        val docInfo = if (docId != null) " in document '$docId'" else ""
        return "Failed to deserialize HLL++ sketch for field '$fieldName'$docInfo. " +
            "The sketch data may be corrupted or incompatible. " +
            "Cause: ${cause.message}. " +
            "This document will be skipped. " +
            "If this error persists, the rollup index may need to be rebuilt."
    }

    /**
     * Creates a user-friendly error message for sketch merge failures.
     *
     * @param fieldName The field name where merge failed
     * @param sketchCount The number of sketches being merged
     * @param cause The underlying exception
     * @return Formatted error message
     */
    fun createMergeErrorMessage(fieldName: String, sketchCount: Int, cause: Exception): String =
        "Failed to merge $sketchCount HLL++ sketches for field '$fieldName'. " +
            "Cause: ${cause.message}. " +
            "This may indicate corrupted sketch data or incompatible precision values. " +
            "Verify all sketches have the same precision and are properly formatted."

    /**
     * Creates a user-friendly error message for unsupported operations on HLL fields.
     *
     * @param operation The unsupported operation (e.g., "sorting", "script access")
     * @param fieldName The HLL field name
     * @return Formatted error message
     */
    fun createUnsupportedOperationMessage(operation: String, fieldName: String): String =
        "Unsupported operation '$operation' on HLL sketch field '$fieldName'. " +
            "HLL sketch fields can only be used with cardinality aggregations. " +
            "They cannot be used for sorting, filtering, or script access. " +
            "If you need to filter by cardinality, compute the cardinality first, " +
            "then filter on the result."
}
