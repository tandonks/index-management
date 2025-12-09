/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup.util

/**
 * DEPRECATED: Sketch precision conversion is not feasible with current OpenSearch APIs.
 *
 * OpenSearch's CardinalityAggregationBuilder creates sketches with a fixed precision
 * that cannot be easily changed after creation. Converting HLL++ sketch precision
 * requires access to the original hash values, which are not available from the
 * aggregation result.
 *
 * Current approach: Use a fixed precision (14) for all cardinality rollups to match
 * the default precision used by CardinalityAggregationBuilder.
 */
object SketchPrecisionConverter {
    /**
     * Returns the fixed precision used for all cardinality rollups.
     * This matches the default precision used by OpenSearch's CardinalityAggregationBuilder.
     */
    const val FIXED_CARDINALITY_PRECISION = 14
}
