# Design Document

## Overview

This design extends the ISM Rollup framework to support cardinality aggregations using HyperLogLog++ (HLL++) sketches. The implementation enables accurate distinct-count computation across multi-tier rollup chains by storing and merging serialized HLL++ sketches rather than just numeric estimates.

The design builds upon the core OpenSearch HLL field mapper (PR #20129) which provides:
- `hll` field type for storing binary HLL++ sketches
- `HllFieldMapper` for field mapping and validation
- `HllFieldData` for accessing sketches during aggregations

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Request                             │
│              (Rollup Job Config or Search Query)                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      RollupRunner                                │
│  - Validates rollup job configuration                            │
│  - Detects source index type (raw vs rollup)                     │
│  - Delegates to RollupIndexer                                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     RollupIndexer                                │
│  - Builds composite aggregation request                          │
│  - Processes aggregation response                                │
│  - Extracts/merges HLL++ sketches                                │
│  - Indexes rollup documents                                      │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  RollupMetadataService                           │
│  - Creates target index with mappings                            │
│  - Stores precision in _meta                                     │
│  - Validates precision compatibility                             │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    RollupInterceptor                             │
│  - Intercepts search requests                                    │
│  - Rewrites cardinality aggregations                             │
│  - Maps source fields to .hll_sketch fields                      │
└─────────────────────────────────────────────────────────────────┘
```


### Data Flow

#### Tier-1 Rollup (Raw Data → Rollup Index)

```
Raw Documents → Composite Aggregation → Cardinality Sub-Agg → Extract Sketch
                                                                      │
                                                                      ▼
                                                            Merge Sketches per Bucket
                                                                      │
                                                                      ▼
                                                            Serialize & Store
                                                                      │
                                                                      ▼
                                                        Rollup Doc with .hll_sketch field
```

#### Tier-N Rollup (Rollup Index → Higher-Tier Rollup Index)

```
Rollup Documents → Read .hll_sketch fields → Deserialize Sketches
                                                      │
                                                      ▼
                                            Merge Sketches per Bucket
                                                      │
                                                      ▼
                                            Serialize Merged Sketch
                                                      │
                                                      ▼
                                    Higher-Tier Rollup Doc with .hll_sketch field
```

#### Search Query Flow

```
User Query (cardinality agg) → RollupInterceptor → Detect Rollup Index
                                                            │
                                                            ▼
                                                    Rewrite Query
                                                            │
                                                            ▼
                                            Map field → field.hll_sketch
                                                            │
                                                            ▼
                                            Execute on Rollup Index
                                                            │
                                                            ▼
                                            Deserialize & Merge Sketches
                                                            │
                                                            ▼
                                            Return Cardinality Estimate
```


## Components and Interfaces

### 1. Cardinality Metric Model

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/model/metric/Cardinality.kt`

**Current State:** Basic structure exists but needs precision parameter support.

**Required Changes:**
- Add optional `precision` parameter (default: 12, range: 4-18)
- Update serialization/deserialization to handle precision
- Update `toXContent` and `parse` methods

**Interface:**
```kotlin
class Cardinality(
    val precision: Int = HyperLogLogPlusPlus.DEFAULT_PRECISION
) : Metric(Type.CARDINALITY) {
    init {
        require(precision in AbstractHyperLogLog.MIN_PRECISION..AbstractHyperLogLog.MAX_PRECISION) {
            "Precision must be between ${AbstractHyperLogLog.MIN_PRECISION} and ${AbstractHyperLogLog.MAX_PRECISION}"
        }
    }
    
    // Serialization methods
    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder
    override fun writeTo(out: StreamOutput)
    
    companion object {
        fun parse(xcp: XContentParser): Cardinality
    }
}
```

### 2. RollupMetrics Enhancement

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/model/RollupMetrics.kt`

**Current State:** Already handles Cardinality in the metrics list.

**Required Changes:**
- Update `targetFieldWithType` to return appropriate field name for cardinality
- Field naming: `<targetField>.hll_sketch` for the sketch storage

**Key Method:**
```kotlin
fun targetFieldWithType(metric: Metric): String = when (metric) {
    is Cardinality -> "$targetField.hll_sketch"
    // ... other metrics
}
```

### 3. RollupIndexer

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/RollupIndexer.kt`

**Required Changes:**

#### 3a. Source Type Detection
**Note:** Multi-tier rollup PR #1533 already implements source type detection. We'll leverage this existing logic.

```kotlin
// Already implemented in PR #1533
private fun isRollupIndex(indexName: String): Boolean {
    return clusterService.state().metadata.index(indexName)
        ?.settings?.get("index.plugins.rollup_index") != null
}
```

#### 3b. Aggregation Building (Tier-1)
**Note:** Multi-tier rollup PR #1533 implements field name mapping for rollup sources. We'll extend this for cardinality.

```kotlin
private fun buildCardinalityAggregation(
    metric: Cardinality,
    sourceField: String,
    isRollupSource: Boolean
): CardinalityAggregationBuilder {
    // For Tier-N rollups, source field is already the sketch field
    val fieldToAggregate = if (isRollupSource) {
        "$sourceField.hll_sketch"
    } else {
        sourceField
    }
    
    return CardinalityAggregationBuilder(sourceField)
        .field(fieldToAggregate)
        .precisionThreshold(calculatePrecisionThreshold(metric.precision))
}

private fun calculatePrecisionThreshold(precision: Int): Long {
    // HLL++ uses 2^precision registers
    return (1L shl precision)
}
```


#### 3c. Sketch Extraction (Tier-1)
```kotlin
private fun extractHllSketch(
    cardinalityAgg: InternalCardinality
): BytesReference {
    // Access the internal HLL++ sketch from the aggregation result
    val sketch: AbstractHyperLogLogPlusPlus = cardinalityAgg.getSketch()
    
    // Serialize the sketch to bytes
    val output = BytesStreamOutput()
    sketch.writeTo(output)
    return output.bytes()
}
```

#### 3d. Sketch Merging (Tier-N)
```kotlin
private suspend fun mergeHllSketches(
    sourceIndex: String,
    bucketKey: Map<String, Any>,
    sketchField: String,
    precision: Int
): BytesReference {
    // Query to get all sketches for this bucket
    val searchRequest = buildSketchSearchRequest(sourceIndex, bucketKey, sketchField)
    val response = client.suspendUntil { listener ->
        search(searchRequest, listener)
    }
    
    // Deserialize and merge sketches
    val mergedSketch = HyperLogLogPlusPlus(precision, BigArrays.NON_RECYCLING_INSTANCE)
    
    response.hits.forEach { hit ->
        val sketchBytes = hit.sourceAsMap[sketchField] as BytesReference
        val sketch = AbstractHyperLogLogPlusPlus.readFrom(
            sketchBytes.streamInput(),
            BigArrays.NON_RECYCLING_INSTANCE
        )
        mergedSketch.merge(sketch)
    }
    
    // Serialize merged sketch
    val output = BytesStreamOutput()
    mergedSketch.writeTo(output)
    return output.bytes()
}
```

#### 3e. Document Indexing
```kotlin
private fun buildRollupDocument(
    bucket: CompositeAggregation.Bucket,
    metrics: List<RollupMetrics>,
    isRollupSource: Boolean
): Map<String, Any> {
    val doc = mutableMapOf<String, Any>()
    
    // Add dimensions
    bucket.key.forEach { (field, value) ->
        doc[field] = value
    }
    
    // Add metrics
    metrics.forEach { rollupMetric ->
        rollupMetric.metrics.forEach { metric ->
            when (metric) {
                is Cardinality -> {
                    val sketchField = "${rollupMetric.targetField}.hll_sketch"
                    val sketch = if (isRollupSource) {
                        // Tier-N: merge existing sketches
                        mergeHllSketches(
                            sourceIndex,
                            bucket.key,
                            "${rollupMetric.sourceField}.hll_sketch",
                            metric.precision
                        )
                    } else {
                        // Tier-1: extract from cardinality aggregation
                        val cardAgg = bucket.aggregations.get(rollupMetric.sourceField) as InternalCardinality
                        extractHllSketch(cardAgg)
                    }
                    doc[sketchField] = sketch
                }
                // ... handle other metrics
            }
        }
    }
    
    return doc
}
```


### 4. RollupMetadataService

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/RollupMetadataService.kt`

**Required Changes:**

#### 4a. Mapping Creation
```kotlin
private fun buildMappingsForCardinality(
    rollupMetrics: List<RollupMetrics>
): Map<String, Any> {
    val properties = mutableMapOf<String, Any>()
    
    rollupMetrics.forEach { metric ->
        metric.metrics.forEach { m ->
            when (m) {
                is Cardinality -> {
                    val sketchField = "${metric.targetField}.hll_sketch"
                    properties[sketchField] = mapOf(
                        "type" to "hll",
                        "precision" to m.precision
                    )
                }
                // ... other metrics
            }
        }
    }
    
    return properties
}
```

#### 4b. Metadata Storage
```kotlin
private fun buildRollupMetadata(
    rollup: Rollup
): Map<String, Any> {
    val metadata = mutableMapOf<String, Any>()
    
    val metricsMetadata = rollup.metrics.map { rollupMetric ->
        val metricsList = rollupMetric.metrics.map { metric ->
            when (metric) {
                is Cardinality -> mapOf(
                    "cardinality" to mapOf(
                        "precision" to metric.precision
                    )
                )
                // ... other metrics
            }
        }
        
        mapOf(
            "source_field" to rollupMetric.sourceField,
            "target_field" to rollupMetric.targetField,
            "metrics" to metricsList
        )
    }
    
    metadata["metrics"] = metricsMetadata
    return metadata
}
```

#### 4c. Precision Validation
```kotlin
fun validatePrecisionCompatibility(
    sourceIndex: String,
    targetMetrics: List<RollupMetrics>
): ValidationResult {
    val sourceMetadata = getSourceRollupMetadata(sourceIndex)
    
    targetMetrics.forEach { targetMetric ->
        targetMetric.metrics.forEach { metric ->
            if (metric is Cardinality) {
                val sourceMetric = findSourceMetric(
                    sourceMetadata,
                    targetMetric.sourceField
                )
                
                if (sourceMetric != null) {
                    val sourcePrecision = sourceMetric.precision
                    if (sourcePrecision != metric.precision) {
                        return ValidationResult.failure(
                            "Precision mismatch for field ${targetMetric.sourceField}: " +
                            "source has precision $sourcePrecision but target specifies ${metric.precision}"
                        )
                    }
                }
            }
        }
    }
    
    return ValidationResult.success()
}
```


### 5. RollupInterceptor

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/interceptor/RollupInterceptor.kt`

**Current State:** Already handles CardinalityAggregationBuilder in `getAggregationMetadata`.

**Required Changes:**

#### 5a. Query Rewriting
```kotlin
private fun rewriteCardinalityAggregation(
    agg: CardinalityAggregationBuilder,
    fieldMappings: Map<String, String>,
    isRollupIndex: Boolean
): AggregationBuilder {
    if (!isRollupIndex) {
        return agg // No rewrite needed for raw indices
    }
    
    val sourceField = agg.field()
    val sketchField = "$sourceField.hll_sketch"
    
    // Verify the sketch field exists in mappings
    if (!fieldMappings.containsKey(sketchField)) {
        throw IllegalArgumentException(
            "Cardinality field $sourceField not found in rollup index. " +
            "Expected sketch field: $sketchField"
        )
    }
    
    // Rewrite to use sketch field
    return CardinalityAggregationBuilder(agg.name)
        .field(sketchField)
        .precisionThreshold(agg.precisionThreshold())
}
```

#### 5b. Aggregation Rewriting in Search Request
```kotlin
private fun rewriteAggregations(
    aggregations: Collection<AggregationBuilder>,
    fieldMappings: Map<String, String>,
    isRollupIndex: Boolean
): List<AggregationBuilder> {
    return aggregations.map { agg ->
        val rewritten = when (agg) {
            is CardinalityAggregationBuilder -> 
                rewriteCardinalityAggregation(agg, fieldMappings, isRollupIndex)
            // ... other aggregation types
            else -> agg
        }
        
        // Recursively rewrite sub-aggregations
        if (agg.subAggregations.isNotEmpty()) {
            val rewrittenSubs = rewriteAggregations(
                agg.subAggregations,
                fieldMappings,
                isRollupIndex
            )
            rewrittenSubs.forEach { sub ->
                rewritten.subAggregation(sub)
            }
        }
        
        rewritten
    }
}
```

### 6. Field Mapping Utilities

**File:** `src/main/kotlin/org/opensearch/indexmanagement/rollup/util/RollupUtils.kt`

**New Utility Functions:**

```kotlin
/**
 * Populates field mappings for cardinality metrics
 */
fun Rollup.populateCardinalityFieldMappings(): Set<RollupFieldMapping> {
    val mappings = mutableSetOf<RollupFieldMapping>()
    
    metrics.forEach { rollupMetric ->
        rollupMetric.metrics.forEach { metric ->
            if (metric is Cardinality) {
                mappings.add(
                    RollupFieldMapping(
                        RollupFieldMapping.FieldType.METRIC,
                        rollupMetric.sourceField,
                        Metric.Type.CARDINALITY.type
                    )
                )
            }
        }
    }
    
    return mappings
}

/**
 * Gets the sketch field name for a cardinality metric
 */
fun getSketchFieldName(targetField: String): String {
    return "$targetField.hll_sketch"
}

/**
 * Extracts precision from rollup metadata
 */
fun getPrecisionFromMetadata(
    metadata: Map<String, Any>,
    fieldName: String
): Int? {
    val metrics = metadata["metrics"] as? List<Map<String, Any>> ?: return null
    
    metrics.forEach { metric ->
        if (metric["source_field"] == fieldName) {
            val metricsList = metric["metrics"] as? List<Map<String, Any>>
            metricsList?.forEach { m ->
                val cardinality = m["cardinality"] as? Map<String, Any>
                if (cardinality != null) {
                    return cardinality["precision"] as? Int
                }
            }
        }
    }
    
    return null
}
```


## Data Models

### Cardinality Metric Configuration

```kotlin
data class Cardinality(
    val precision: Int = HyperLogLogPlusPlus.DEFAULT_PRECISION
) : Metric(Type.CARDINALITY)
```

**Serialization Format (XContent):**
```json
{
  "cardinality": {
    "precision": 12
  }
}
```

**Serialization Format (StreamOutput):**
- Precision is written as VInt

### Rollup Document Schema

**Tier-1 Rollup Document:**
```json
{
  "timestamp": "2025-12-03T10:00:00Z",
  "application_id": "app-123",
  "user_id.hll_sketch": "<binary-data>",
  "response_time.avg": 245.7
}
```

**Field Types:**
- Dimensions: Original types (date, keyword, etc.)
- `.hll_sketch`: Binary field type `hll` from core OpenSearch
- Other metrics: double, long, etc.

### Rollup Metadata Schema

```json
{
  "_meta": {
    "rollup": {
      "rollup_job_id": {
        "rollup_id": "rollup_job_id",
        "source_index": "source_index_name",
        "dimensions": [...],
        "metrics": [
          {
            "source_field": "user_id",
            "target_field": "user_id",
            "metrics": [
              {
                "cardinality": {
                  "precision": 12
                }
              }
            ]
          }
        ]
      }
    }
  }
}
```


## Error Handling

### Validation Errors

#### 1. Invalid Precision
**Trigger:** User specifies precision outside range [4, 18]

**Error Message:**
```
"Precision must be between 4 and 18, got: {value}"
```

**Handling:** Reject rollup job creation at validation time

#### 2. Precision Mismatch in Multi-Tier Rollup
**Trigger:** Source rollup index has different precision than target

**Error Message:**
```
"Precision mismatch for field {field_name}: source has precision {source_precision} but target specifies {target_precision}. Multi-tier rollups require matching precision values."
```

**Handling:** Reject rollup job creation during metadata validation

#### 3. Missing Sketch Field
**Trigger:** Query references cardinality field that doesn't exist in rollup index

**Error Message:**
```
"Cardinality field {field_name} not found in rollup index. Expected sketch field: {field_name}.hll_sketch"
```

**Handling:** Return error during query rewriting

### Runtime Errors

#### 4. Sketch Deserialization Failure
**Trigger:** Corrupted or incompatible sketch data

**Error Message:**
```
"Failed to deserialize HLL sketch for field {field_name} in document {doc_id}: {error_details}"
```

**Handling:** 
- Log error with document ID
- Skip corrupted document
- Continue processing other documents
- Include warning in rollup job status

#### 5. Sketch Merge Failure
**Trigger:** Attempting to merge sketches with incompatible precision

**Error Message:**
```
"Cannot merge HLL sketches with different precision values: {precision1} and {precision2} for field {field_name}"
```

**Handling:**
- Fail the rollup job execution
- Update job status to FAILED
- Include error details in metadata

### Unsupported Operations

#### 6. Sorting on HLL Field
**Trigger:** User attempts to sort by cardinality field

**Error Message:**
```
"Sorting is not supported on HLL sketch fields. Field: {field_name}.hll_sketch"
```

**Handling:** Return error during query validation

#### 7. Script Access to HLL Field
**Trigger:** User attempts to access HLL field in script

**Error Message:**
```
"HLL sketch fields do not support script access. Field: {field_name}.hll_sketch"
```

**Handling:** Return error during query execution


## Testing Strategy

### Unit Tests

#### 1. Cardinality Metric Model Tests
**File:** `CardinalityTests.kt`

**Test Cases:**
- Parse cardinality metric with default precision
- Parse cardinality metric with custom precision
- Validate precision range (4-18)
- Reject invalid precision values
- Serialize and deserialize cardinality metric
- XContent round-trip testing

#### 2. RollupMetrics Tests
**File:** `RollupMetricsTests.kt`

**Test Cases:**
- Generate correct target field name for cardinality (`.hll_sketch`)
- Handle multiple metrics including cardinality
- Validate cardinality metric in metrics list

#### 3. RollupMetadataService Tests
**File:** `RollupMetadataServiceTests.kt`

**Test Cases:**
- Create mappings with HLL field type
- Store precision in metadata
- Validate precision compatibility (matching)
- Validate precision compatibility (mismatched - should fail)
- Handle missing precision in source (use default)
- Extract precision from metadata

#### 4. RollupInterceptor Tests
**File:** `RollupInterceptorTests.kt`

**Test Cases:**
- Detect cardinality aggregation in query
- Rewrite cardinality aggregation for rollup index
- Do not rewrite cardinality aggregation for raw index
- Rewrite nested cardinality aggregations
- Handle missing sketch field error
- Validate field mapping for cardinality

### Integration Tests

#### 5. Tier-1 Rollup Tests
**File:** `CardinalityRollupIT.kt`

**Test Cases:**
- Create rollup job with cardinality metric
- Execute Tier-1 rollup on raw data
- Verify HLL sketch stored in target index
- Verify sketch field has correct type
- Verify precision stored in metadata
- Query cardinality on rollup index
- Compare cardinality estimate with raw data

#### 6. Tier-N Rollup Tests
**File:** `MultiTierCardinalityRollupIT.kt`

**Test Cases:**
- Create Tier-2 rollup from Tier-1 rollup index
- Verify sketches are merged correctly
- Verify precision is preserved across tiers
- Query cardinality on Tier-2 index
- Compare estimates across all tiers
- Test 3-tier rollup chain (minute → hour → day)

#### 7. Precision Validation Tests
**File:** `CardinalityPrecisionValidationIT.kt`

**Test Cases:**
- Create rollup with custom precision
- Reject Tier-2 rollup with mismatched precision
- Accept Tier-2 rollup with matching precision
- Handle default precision inheritance
- Test multiple cardinality fields with different precisions

#### 8. Query Rewriting Tests
**File:** `CardinalityQueryRewriteIT.kt`

**Test Cases:**
- Query cardinality on rollup index
- Query cardinality with composite aggregations
- Query cardinality with filters
- Query cardinality with multiple cardinality aggregations
- Verify query results match expected estimates


### End-to-End Tests

#### 9. Complete Rollup Lifecycle Test
**Test Scenario:**
1. Create source index with sample data (1M documents, 10K unique users)
2. Create Tier-1 rollup job (1-minute buckets) with cardinality on user_id
3. Execute rollup and verify completion
4. Query cardinality on rollup index
5. Create Tier-2 rollup job (1-hour buckets)
6. Execute Tier-2 rollup
7. Query cardinality on Tier-2 index
8. Verify cardinality estimates are within expected error bounds

**Expected Results:**
- Tier-1 cardinality estimate: ~10K ± 1.6% (precision 12)
- Tier-2 cardinality estimate: ~10K ± 1.6% (precision 12)
- Both estimates should be close to actual value

#### 10. ISM Policy Integration Test
**Test Scenario:**
1. Create ISM policy with rollup action including cardinality
2. Attach policy to source index
3. Trigger policy execution
4. Verify rollup index created with correct mappings
5. Verify cardinality data populated
6. Query and validate results

### Performance Tests

#### 11. Sketch Size and Storage Tests
**Test Cases:**
- Measure sketch size for different precision values
- Verify storage overhead compared to numeric cardinality
- Test compression effectiveness on sketch data

**Expected Results:**
- Precision 12: ~1KB per sketch
- Precision 14: ~4KB per sketch
- Precision 16: ~16KB per sketch

#### 12. Merge Performance Tests
**Test Cases:**
- Measure time to merge 100 sketches
- Measure time to merge 1000 sketches
- Compare merge time vs. recomputing from raw data

**Expected Results:**
- Sketch merging should be significantly faster than raw data aggregation
- Merge time should scale linearly with number of sketches


## Design Decisions and Rationales

### 1. Store Only HLL Sketch (Not Numeric Value)

**Decision:** Store only the serialized HLL++ sketch in rollup documents, not the numeric cardinality estimate.

**Rationale:**
- Sketches are mergeable; numeric values are not
- Enables accurate multi-tier rollups
- Numeric estimate can be computed on-demand from sketch
- Reduces storage overhead (no redundant data)
- Aligns with the design goal of supporting multi-tier aggregation

**Trade-off:** Slightly higher query-time computation (deserialize + estimate), but this is negligible compared to I/O savings.

### 2. Precision Stored in Metadata, Not Per-Document

**Decision:** Store precision once in `_meta.rollup` section, not in each rollup document.

**Rationale:**
- Precision is constant for all documents in a rollup job
- Reduces storage overhead
- Simplifies validation logic
- Aligns with how other rollup metadata is stored

**Trade-off:** Requires metadata lookup during validation, but this is a one-time operation per job.

### 3. Strict Precision Validation (No Auto-Promotion)

**Decision:** Reject multi-tier rollups with mismatched precision values.

**Rationale:**
- Ensures correctness (merging different-precision sketches produces incorrect results)
- Simpler implementation (no complex promotion logic)
- Clear error messages help users understand the requirement
- Can be relaxed in future if core OpenSearch adds sketch promotion support

**Trade-off:** Less flexible for users, but prevents silent data corruption.

### 4. Field Naming Convention: `<field>.hll_sketch`

**Decision:** Use `.hll_sketch` suffix for sketch fields.

**Rationale:**
- Consistent with existing rollup field naming (e.g., `.avg`, `.sum`)
- Clear indication that field contains sketch data
- Easy to map source field to sketch field
- Avoids conflicts with other metric types

**Alternative Considered:** `<field>.cardinality.hll_sketch` - rejected as too verbose.

### 5. Reuse Core OpenSearch HLL Infrastructure

**Decision:** Depend on core OpenSearch `hll` field type and HLL++ implementation.

**Rationale:**
- Avoids code duplication
- Leverages well-tested, optimized implementation
- Ensures compatibility with future core improvements
- Reduces maintenance burden

**Trade-off:** Requires core OpenSearch PR #20129 to be merged first.

### 6. Transparent Query Rewriting

**Decision:** Automatically rewrite cardinality queries to use sketch fields, without requiring users to change query syntax.

**Rationale:**
- Better user experience (same queries work on raw and rollup indices)
- Reduces migration friction
- Consistent with how other rollup metrics work
- Hides implementation details from users

**Trade-off:** More complex interceptor logic, but worth it for usability.


## Implementation Phases

### Phase 1: Core Model and Metadata Support
**Goal:** Enable cardinality metric configuration and metadata storage

**Dependencies:** None

**Components:**
- Update `Cardinality` class with precision parameter
- Update `RollupMetrics.targetFieldWithType()` for cardinality
- Update `RollupMetadataService` to create HLL field mappings
- Update `RollupMetadataService` to store precision in metadata
- Add precision validation logic

**Deliverables:**
- Users can configure cardinality metrics in rollup jobs
- Target indices have correct HLL field mappings
- Precision is stored and validated

**Estimated Effort:** 2-3 days

### Phase 2: Tier-1 Rollup Support
**Goal:** Enable sketch computation and storage from raw data

**Dependencies:** 
- Phase 1 complete
- Core OpenSearch HLL PR #20129 merged

**Components:**
- Implement cardinality aggregation building for Tier-1
- Implement sketch extraction from `InternalCardinality`
- Implement sketch serialization and storage
- Update document indexing logic

**Deliverables:**
- Tier-1 rollups compute and store HLL sketches
- Sketches are correctly serialized and indexed
- Rollup documents contain `.hll_sketch` fields

**Estimated Effort:** 3-4 days

### Phase 3: Tier-N Rollup Support (Optional - depends on PR #1533)
**Goal:** Enable sketch merging for multi-tier rollups

**Dependencies:**
- Phase 2 complete
- Multi-tier rollup PR #1533 merged (or implement in parallel)

**Components:**
- Leverage source type detection from PR #1533
- Extend field name mapping for `.hll_sketch` fields
- Implement sketch reading from rollup indices
- Implement sketch deserialization
- Implement sketch merging logic
- Update document indexing for merged sketches

**Deliverables:**
- Tier-N rollups merge existing sketches
- Multi-tier rollup chains work correctly
- Cardinality estimates remain accurate across tiers

**Estimated Effort:** 3-4 days

**Note:** This phase can be deferred if PR #1533 is not yet merged. Tier-1 cardinality support is valuable on its own.

### Phase 4: Query Rewriting Support
**Goal:** Enable transparent cardinality queries on rollup indices

**Dependencies:** Phase 2 complete (Phase 3 optional)

**Components:**
- Update `RollupInterceptor` to detect cardinality aggregations
- Implement query rewriting for cardinality
- Implement field mapping for sketch fields
- Handle nested and composite aggregations

**Deliverables:**
- Users can query cardinality on rollup indices
- Queries are automatically rewritten
- Results match expected estimates

**Estimated Effort:** 2-3 days

### Phase 5: Testing and Validation
**Goal:** Ensure correctness and performance

**Dependencies:** Phases 1, 2, 4 complete (Phase 3 optional)

**Components:**
- Implement unit tests for all components
- Implement integration tests for rollup execution
- Implement end-to-end tests for complete workflows
- Implement performance tests for sketch operations

**Deliverables:**
- Comprehensive test coverage
- Validated accuracy of cardinality estimates
- Performance benchmarks

**Estimated Effort:** 4-5 days

### Total Estimated Effort
- **Minimum (Tier-1 only):** 11-15 days
- **Full (with Tier-N):** 14-19 days


## Dependencies

### External Dependencies

#### 1. Core OpenSearch HLL Field Mapper (PR #20129)
**Status:** Draft PR, not yet merged

**What We're Leveraging:**

##### A. HllFieldMapper (`org.opensearch.index.mapper.HllFieldMapper`)
**Purpose:** Provides the `hll` field type for storing binary HLL++ sketches

**Key Features We Use:**
- Field type registration: Allows us to specify `"type": "hll"` in index mappings
- Precision parameter: Configurable precision (4-18) stored in field mapping
- Binary doc values storage: Efficient storage of serialized sketches
- Field validation: Ensures precision is within valid range

**How We Use It:**
```kotlin
// In RollupMetadataService, we create mappings like:
val properties = mapOf(
    "user_id.hll_sketch" to mapOf(
        "type" to "hll",
        "precision" to 12
    )
)
```

##### B. HllFieldData (`org.opensearch.index.fielddata.plain.HllFieldData`)
**Purpose:** Provides access to HLL++ sketches stored in doc values

**Key Features We Use:**
- `getSketch(docId)`: Retrieves deserialized HLL++ sketch for a document
- Efficient binary doc values reading
- Integration with OpenSearch field data infrastructure

**How We Use It:**
```kotlin
// During Tier-N rollup, we read sketches from source documents:
val fieldData = indexFieldDataService.getForField(sketchField)
val leafFieldData = fieldData.load(context)
val sketch = leafFieldData.getSketch(docId)
```

##### C. HyperLogLogPlusPlus (`org.opensearch.search.aggregations.metrics.HyperLogLogPlusPlus`)
**Purpose:** Core HLL++ algorithm implementation

**Key Features We Use:**
- `merge(otherSketch)`: Merges two HLL++ sketches
- `cardinality()`: Computes cardinality estimate from sketch
- `writeTo(StreamOutput)`: Serializes sketch to bytes
- `readFrom(StreamInput)`: Deserializes sketch from bytes
- Precision management: Handles different precision values

**How We Use It:**
```kotlin
// Tier-1: Extract sketch from cardinality aggregation
val cardAgg = bucket.aggregations.get(fieldName) as InternalCardinality
val sketch = cardAgg.getSketch() // Returns HyperLogLogPlusPlus

// Tier-N: Merge multiple sketches
val mergedSketch = HyperLogLogPlusPlus(precision, BigArrays.NON_RECYCLING_INSTANCE)
sketches.forEach { sketch ->
    mergedSketch.merge(sketch)
}

// Serialize for storage
val output = BytesStreamOutput()
mergedSketch.writeTo(output)
val sketchBytes = output.bytes()
```

##### D. HllCardinalityAggregator (`org.opensearch.search.aggregations.metrics.HllCardinalityAggregator`)
**Purpose:** Aggregator that works directly with HLL sketch fields

**Key Features We Use:**
- Aggregates pre-computed HLL sketches from `hll` field type
- Merges sketches across documents in aggregation buckets
- Returns cardinality estimate from merged sketch

**How We Use It:**
```kotlin
// During query rewriting, cardinality aggregations on rollup indices
// automatically use HllCardinalityAggregator when field type is 'hll'
val rewrittenAgg = CardinalityAggregationBuilder("unique_users")
    .field("user_id.hll_sketch") // Field type 'hll' triggers HllCardinalityAggregator
```

##### E. AbstractHyperLogLogPlusPlus (`org.opensearch.search.aggregations.metrics.AbstractHyperLogLogPlusPlus`)
**Purpose:** Abstract base class for HLL++ implementations

**Key Features We Use:**
- Common interface for HLL++ operations
- Serialization/deserialization methods
- Precision constants: `MIN_PRECISION` (4), `MAX_PRECISION` (18), `DEFAULT_PRECISION` (12)

**How We Use It:**
```kotlin
// Validation in Cardinality metric
init {
    require(precision in AbstractHyperLogLog.MIN_PRECISION..AbstractHyperLogLog.MAX_PRECISION) {
        "Precision must be between ${AbstractHyperLogLog.MIN_PRECISION} and ${AbstractHyperLogLog.MAX_PRECISION}"
    }
}
```

**Impact:** This is a hard dependency. ISM cardinality support cannot be implemented until this PR is merged.

**What We DON'T Need to Implement:**
- HLL++ algorithm (already in core)
- Sketch serialization/deserialization (already in core)
- Field type registration (already in core)
- Aggregator for HLL fields (already in core)

**What We DO Need to Implement:**
- Rollup-specific logic for extracting sketches from aggregations
- Rollup-specific logic for merging sketches across buckets
- Metadata management for precision values
- Query rewriting to map source fields to `.hll_sketch` fields

**Mitigation:** 
- Track PR progress closely
- Provide feedback on PR to ensure it meets ISM requirements
- Consider contributing to the PR if needed

#### 2. OpenSearch Core Aggregations
**Components Used:**
- `CardinalityAggregationBuilder` - For building cardinality aggregations
- `InternalCardinality` - For accessing aggregation results
- `CompositeAggregationBuilder` - For dimension grouping

**Status:** Already available in OpenSearch core

### Internal Dependencies

#### 1. Multi-Tier Rollup Support (PR #1533)
**Status:** In review, not yet merged

**Components Provided:**
- Source type detection (`isRollupIndex`)
- Field name mapping for rollup sources
- Interval compatibility validation
- Metric availability validation
- Template variable resolution

**Impact:** Soft dependency. Cardinality can be implemented independently, but multi-tier cardinality rollups require this PR.

**Integration Points:**
- Leverage existing source type detection
- Extend field name mapping for `.hll_sketch` fields
- Use existing metric validation framework
- Follow established patterns for Tier-N rollups

**Mitigation:**
- Implement cardinality for Tier-1 rollups first (no dependency)
- Add Tier-N support after PR #1533 merges
- Ensure design is compatible with multi-tier framework

#### 2. Existing Rollup Framework
**Components:**
- `RollupIndexer` - Will be extended for cardinality
- `RollupMetadataService` - Will be extended for HLL mappings
- `RollupInterceptor` - Will be extended for query rewriting
- `RollupMetrics` - Already supports cardinality in model

**Status:** Available, requires extensions

#### 3. Rollup Metadata Format
**Requirement:** Backward-compatible metadata format

**Approach:** Add cardinality-specific fields to existing metadata structure without breaking existing rollup jobs

## Backward Compatibility

### Existing Rollup Jobs
- Rollup jobs without cardinality metrics continue to work unchanged
- No migration required for existing rollup indices
- Existing query rewriting logic unaffected

### Adding Cardinality to Existing Jobs
- Users can create new rollup jobs with cardinality on same source index
- Existing rollup data remains valid
- New rollup indices will have additional `.hll_sketch` fields

### Metadata Format
- New `precision` field in cardinality metric metadata
- Absent precision field defaults to 12 (backward compatible)
- Existing metadata fields unchanged


## Future Enhancements

### 1. Support for Other Sketch-Based Metrics

**Metrics to Consider:**
- Percentiles (using TDigest sketches)
- Percentile Ranks (using TDigest sketches)
- Top-N (using Count-Min sketch or similar)

**Approach:**
- Generalize the sketch storage and merging framework
- Create abstract `SketchMetric` base class
- Implement specific sketch types as subclasses

**Benefits:**
- Enables more advanced analytics on rollup data
- Consistent approach for all sketch-based metrics
- Reduces implementation complexity for future metrics

### 2. Sketch Compression

**Goal:** Reduce storage overhead for HLL sketches

**Approaches:**
- Use sparse representation for low-cardinality sketches
- Apply compression algorithms (e.g., LZ4, Snappy)
- Store sketches in columnar format for better compression

**Benefits:**
- Lower storage costs
- Faster I/O during sketch reading
- More efficient network transfer

### 3. Adaptive Precision

**Goal:** Automatically adjust precision based on cardinality

**Approach:**
- Start with low precision for low-cardinality fields
- Automatically promote to higher precision when needed
- Store precision per document or per bucket

**Benefits:**
- Optimizes storage for different cardinality ranges
- Better accuracy for high-cardinality fields
- Lower overhead for low-cardinality fields

**Challenges:**
- Complex merging logic
- Requires core OpenSearch support for precision promotion
- Backward compatibility concerns

### 4. Cardinality Estimation Optimization

**Goal:** Improve query-time performance for cardinality estimates

**Approaches:**
- Cache frequently accessed sketches
- Pre-compute estimates during indexing
- Use approximate merging for very large sketch counts

**Benefits:**
- Faster query response times
- Lower CPU usage during queries
- Better scalability for large rollup indices

### 5. Multi-Field Cardinality

**Goal:** Support cardinality across multiple fields (e.g., unique user-session pairs)

**Approach:**
- Extend cardinality metric to accept multiple source fields
- Concatenate field values before hashing
- Store combined sketch

**Benefits:**
- Enables more complex analytics
- Reduces need for custom scripting
- Consistent with other multi-field aggregations


## Security Considerations

### 1. Sketch Data Access Control

**Concern:** HLL sketches contain approximate information about distinct values

**Mitigation:**
- Sketches inherit access control from source index
- Field-level security applies to `.hll_sketch` fields
- Document-level security applies to rollup documents

**Implementation:**
- No special security handling needed (relies on OpenSearch security)
- Sketches are treated like any other field

### 2. Precision as Information Leakage

**Concern:** Precision value could reveal information about data characteristics

**Mitigation:**
- Precision is stored in index metadata (already protected)
- Precision is a configuration choice, not derived from data
- No additional risk beyond existing rollup metadata

### 3. Sketch Deserialization Vulnerabilities

**Concern:** Malicious sketch data could exploit deserialization

**Mitigation:**
- Use OpenSearch core deserialization (already hardened)
- Validate sketch format before deserialization
- Catch and handle deserialization exceptions gracefully

**Implementation:**
- Wrap deserialization in try-catch blocks
- Log suspicious deserialization failures
- Fail gracefully without exposing internal details

## Monitoring and Observability

### Metrics to Track

#### 1. Rollup Execution Metrics
- Number of cardinality metrics processed
- Sketch extraction time (Tier-1)
- Sketch merge time (Tier-N)
- Sketch serialization/deserialization time

#### 2. Storage Metrics
- Total sketch storage size
- Average sketch size per precision level
- Sketch compression ratio (if implemented)

#### 3. Query Metrics
- Cardinality query rewrite count
- Sketch deserialization time during queries
- Sketch merge time during queries
- Query accuracy (compared to raw data)

#### 4. Error Metrics
- Precision validation failures
- Sketch deserialization failures
- Sketch merge failures
- Query rewrite failures

### Logging

#### Debug Level
- Sketch extraction details
- Sketch merge operations
- Query rewriting details
- Field mapping resolutions

#### Info Level
- Rollup job execution with cardinality metrics
- Precision validation results
- Target index creation with HLL fields

#### Warn Level
- Sketch deserialization failures (with recovery)
- Precision mismatches (before rejection)
- Missing sketch fields (with fallback)

#### Error Level
- Rollup job failures due to cardinality issues
- Unrecoverable sketch corruption
- Query rewriting failures


## Documentation Requirements

### User Documentation

#### 1. Rollup Configuration Guide
**Content:**
- How to add cardinality metrics to rollup jobs
- Precision parameter explanation and recommendations
- Examples of single-tier and multi-tier rollup configurations
- Storage and accuracy trade-offs

**Location:** OpenSearch documentation site

#### 2. Query Guide
**Content:**
- How to query cardinality on rollup indices
- Query syntax (same as raw indices)
- Expected accuracy and error bounds
- Performance considerations

**Location:** OpenSearch documentation site

#### 3. Multi-Tier Rollup Guide
**Content:**
- How to chain rollup jobs with cardinality
- Precision compatibility requirements
- Best practices for rollup hierarchies
- Troubleshooting precision mismatches

**Location:** OpenSearch documentation site

### Developer Documentation

#### 4. Architecture Documentation
**Content:**
- Component overview and interactions
- Data flow diagrams
- Sketch storage format
- Metadata schema

**Location:** ISM plugin repository (docs/ directory)

#### 5. API Documentation
**Content:**
- Cardinality metric model
- RollupMetrics API changes
- RollupMetadataService API changes
- Utility functions

**Location:** Code comments and KDoc

#### 6. Testing Guide
**Content:**
- How to run cardinality rollup tests
- How to add new test cases
- Performance testing procedures
- Accuracy validation methods

**Location:** ISM plugin repository (TESTING.md)

### Release Notes

#### 7. Feature Announcement
**Content:**
- Overview of cardinality support
- Key benefits (multi-tier accuracy, storage efficiency)
- Usage examples
- Known limitations

**Location:** OpenSearch release notes

#### 8. Migration Guide
**Content:**
- How to add cardinality to existing rollup workflows
- Backward compatibility notes
- Breaking changes (none expected)

**Location:** OpenSearch upgrade guide

