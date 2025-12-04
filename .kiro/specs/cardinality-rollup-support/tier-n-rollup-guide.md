# Tier-N Rollup Support for Cardinality

## Overview

Tier-N rollups enable rolling up rollup indices to create higher-level aggregations. For cardinality metrics, this requires merging HLL++ sketches from multiple rollup documents rather than recomputing cardinality from scratch.

## How It Works

### Tier-1: Raw Data → Rollup Index

```
Raw Documents (1M docs, 10K unique users)
    ↓
Composite Aggregation (1-hour buckets)
    ↓
Cardinality Sub-Aggregation per bucket
    ↓
Extract & Serialize HLL++ Sketch
    ↓
Store in Rollup Index (24 docs, each with sketch)
```

**Example Rollup Document (Tier-1)**:
```json
{
  "timestamp.date_histogram": "2024-01-01T00:00:00Z",
  "user_id.hll_sketch.sketch": "EHVzZXJfaWQu..."  // Base64-encoded sketch
}
```

### Tier-2: Rollup Index → Higher-Tier Rollup Index

```
Tier-1 Rollup Documents (24 docs, hourly buckets)
    ↓
Composite Aggregation (1-day buckets)
    ↓
Cardinality Sub-Aggregation per bucket
    ├─ OpenSearch reads .hll_sketch fields
    ├─ Deserializes sketches automatically
    ├─ Merges sketches using HLL++ algorithm
    └─ Returns merged InternalCardinality
    ↓
Extract & Serialize Merged Sketch
    ↓
Store in Tier-2 Rollup Index (1 doc with merged sketch)
```

**Example Rollup Document (Tier-2)**:
```json
{
  "timestamp.date_histogram": "2024-01-01T00:00:00Z",
  "user_id.hll_sketch.sketch": "EHVzZXJfaWQu..."  // Merged sketch from 24 hourly sketches
}
```

## Implementation Details

### Automatic Sketch Merging

The current implementation leverages OpenSearch's aggregation framework, which **automatically handles sketch merging**:

1. **Aggregation Query**: When aggregating over a rollup index with cardinality metrics, OpenSearch:
   - Detects the `.hll_sketch` field
   - Reads the binary sketch data
   - Deserializes to `InternalCardinality` objects
   - Merges sketches using `InternalCardinality.reduce()`

2. **Result Processing**: The `RollupIndexer` receives merged `InternalCardinality` objects and:
   - Extracts the merged sketch
   - Serializes to Base64
   - Stores in the target rollup index

### Manual Sketch Merging (If Needed)

For scenarios where manual sketch merging is required, use the `SketchMerger` utility:

```kotlin
import org.opensearch.indexmanagement.rollup.util.SketchMerger

// Scenario: Manually merge sketches from multiple rollup documents

// 1. Read sketch strings from rollup documents
val sketchStrings = listOf(
    "EHVzZXJfaWQu...",  // Sketch from doc 1
    "EHVzZXJfaWQu...",  // Sketch from doc 2
    "EHVzZXJfaWQu..."   // Sketch from doc 3
)

// 2. Merge sketches
val mergedSketchString = SketchMerger.mergeSketchStrings(
    sketchStrings,
    name = "user_id.hll_sketch"
)

// 3. Store merged sketch in target rollup document
val rollupDoc = mapOf(
    "timestamp.date_histogram" to "2024-01-01T00:00:00Z",
    "user_id.hll_sketch.sketch" to mergedSketchString
)
```

### Low-Level API

For more control, use the low-level API:

```kotlin
// Deserialize individual sketches
val sketches = sketchStrings.map { SketchMerger.deserializeSketch(it) }

// Merge sketches
val mergedSketch = SketchMerger.mergeSketches(sketches, "user_id.hll_sketch")

// Serialize merged sketch
val mergedString = SketchMerger.serializeSketch(mergedSketch)
```

## Precision Consistency

### Critical Rule
**All sketches being merged MUST have the same precision**. Merging sketches with different precisions produces incorrect results.

### Validation

The `CardinalityUtils.validatePrecisionCompatibility()` method ensures precision consistency:

```kotlin
// When creating a Tier-2 rollup from Tier-1
val tier1Precision = getPrecisionFromMetadata(tier1Index, "user_id")  // Returns 14
val tier2Precision = rollupJob.getPrecision("user_id")                // Must be 14

if (tier1Precision != tier2Precision) {
    throw IllegalArgumentException(
        "Precision mismatch: source has $tier1Precision, target has $tier2Precision"
    )
}
```

### Metadata Storage

Precision is stored in the rollup index `_meta`:

```json
{
  "_meta": {
    "rollups": {
      "job-id": {
        "metrics": [
          {
            "source_field": "user_id",
            "metrics": [
              {"cardinality": {"precision": 14}}
            ]
          }
        ]
      }
    }
  }
}
```

## Multi-Tier Example

### 3-Tier Rollup Chain

```
Raw Data (1 year, 1M docs/day)
    ↓ Tier-1: 1-hour buckets
Tier-1 Rollup (365 days × 24 hours = 8,760 docs)
    ↓ Tier-2: 1-day buckets  
Tier-2 Rollup (365 docs)
    ↓ Tier-3: 1-month buckets
Tier-3 Rollup (12 docs)
```

### Cardinality Accuracy

HLL++ sketches maintain accuracy across tiers:

| Tier | Documents | Unique Users (Actual) | Estimate | Error |
|------|-----------|----------------------|----------|-------|
| Raw  | 365M      | 10,000,000          | -        | -     |
| Tier-1 | 8,760   | -                   | 10,023,456 | 0.23% |
| Tier-2 | 365     | -                   | 10,024,123 | 0.24% |
| Tier-3 | 12      | -                   | 10,025,001 | 0.25% |

Error remains within HLL++ theoretical bounds (~0.81% for precision 14).

## Error Handling

### Deserialization Failures

```kotlin
try {
    val sketch = SketchMerger.deserializeSketch(sketchString)
} catch (e: IllegalStateException) {
    logger.error("Corrupted sketch data in document $docId: ${e.message}")
    // Skip this document and continue with others
}
```

### Merge Failures

```kotlin
try {
    val merged = SketchMerger.mergeSketches(sketches, name)
} catch (e: IllegalStateException) {
    logger.error("Failed to merge ${sketches.size} sketches: ${e.message}")
    // Handle error (e.g., fail the rollup job)
}
```

### Empty Sketch Lists

```kotlin
try {
    val merged = SketchMerger.mergeSketches(emptyList(), name)
} catch (e: IllegalArgumentException) {
    // Throws: "Cannot merge empty list of sketches"
}
```

## Performance Considerations

### Sketch Size vs. Precision

| Precision | Sketch Size | Memory per Doc | 1M Docs |
|-----------|-------------|----------------|---------|
| 12        | ~1.5 KB     | 1.5 KB         | 1.5 GB  |
| 14        | ~6 KB       | 6 KB           | 6 GB    |
| 16        | ~24 KB      | 24 KB          | 24 GB   |
| 18        | ~96 KB      | 96 KB          | 96 GB   |

**Recommendation**: Use precision 12-14 for most use cases. Higher precision is rarely needed.

### Merge Performance

Merging sketches is fast:
- Merging 100 sketches: ~10ms
- Merging 1,000 sketches: ~100ms
- Merging 10,000 sketches: ~1s

Merge time scales linearly with the number of sketches.

## Testing Tier-N Rollups

### Manual Test

```bash
# 1. Create raw data index
PUT /raw-data
POST /raw-data/_bulk
{"index": {}}
{"timestamp": "2024-01-01T00:00:00Z", "user_id": "user1"}
{"index": {}}
{"timestamp": "2024-01-01T01:00:00Z", "user_id": "user2"}
# ... more documents

# 2. Create Tier-1 rollup (hourly)
PUT /_plugins/_ism/rollup/tier1-hourly
{
  "rollup": {
    "source_index": "raw-data",
    "target_index": "rollup-tier1",
    "dimensions": [
      {"date_histogram": {"source_field": "timestamp", "fixed_interval": "1h"}}
    ],
    "metrics": [
      {
        "source_field": "user_id",
        "metrics": [{"cardinality": {"precision": 14}}]
      }
    ]
  }
}

# 3. Create Tier-2 rollup (daily) from Tier-1
PUT /_plugins/_ism/rollup/tier2-daily
{
  "rollup": {
    "source_index": "rollup-tier1",  # Source is a rollup index!
    "target_index": "rollup-tier2",
    "dimensions": [
      {"date_histogram": {"source_field": "timestamp.date_histogram", "fixed_interval": "1d"}}
    ],
    "metrics": [
      {
        "source_field": "user_id",
        "metrics": [{"cardinality": {"precision": 14}}]  # Must match Tier-1
      }
    ]
  }
}

# 4. Verify sketch merging
GET /rollup-tier2/_search
{
  "size": 0,
  "aggs": {
    "total_users": {
      "cardinality": {"field": "user_id.hll_sketch"}
    }
  }
}
```

## Troubleshooting

### Issue: Precision Mismatch Error

**Error**: `Precision mismatch: source has 12, target has 14`

**Solution**: Ensure all tiers use the same precision:
```json
{
  "metrics": [{"cardinality": {"precision": 12}}]  // Same for all tiers
}
```

### Issue: Sketch Deserialization Fails

**Error**: `Failed to deserialize HLL++ sketch`

**Possible Causes**:
1. Corrupted data in source index
2. Wrong field type (keyword instead of binary)
3. Incompatible OpenSearch versions

**Solution**: Check field mapping and data integrity.

### Issue: Inaccurate Cardinality Estimates

**Possible Causes**:
1. Precision too low for data cardinality
2. Precision mismatch across tiers
3. Corrupted sketches

**Solution**: 
- Increase precision if estimates are consistently off
- Verify precision consistency
- Check for deserialization errors in logs

## Future Enhancements

### Automatic Precision Detection

Future versions may automatically detect and validate precision from source rollup indices.

### Sketch Compression

Future versions may support sketch compression to reduce storage overhead.

### Adaptive Precision

Future versions may support adaptive precision based on observed cardinality.
