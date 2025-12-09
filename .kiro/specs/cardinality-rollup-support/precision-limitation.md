# Cardinality Precision Limitation

## Problem

OpenSearch's `CardinalityAggregationBuilder` does not expose a way to set HLL precision when aggregating raw data. The precision is hardcoded to 14 in the OpenSearch core code.

### What This Means

When you create a rollup job with:
```json
{
  "metrics": [{
    "source_field": "user_id",
    "metrics": [{"cardinality": {"precision": 12}}]
  }]
}
```

**What happens:**
1. The rollup job creates an HLL field mapping with precision 12
2. The cardinality aggregation creates sketches with precision 14 (ignoring your setting)
3. When indexing, you get: `HLL++ sketch precision mismatch: expected 12, got 14`

## Current Solution

**Use precision 14 (OpenSearch's default) for all cardinality metrics:**

```json
{
  "metrics": [{
    "source_field": "user_id",
    "metrics": [{"cardinality": {"precision": 14}}]
  }]
}
```

Or simply omit the precision parameter (defaults to 14):

```json
{
  "metrics": [{
    "source_field": "user_id",
    "metrics": [{"cardinality": {}}]
  }]
}
```

### Why Precision 14?

- **Accuracy**: ~0.81% error rate (very good for most use cases)
- **Memory**: ~6KB per sketch (reasonable for most deployments)
- **Compatibility**: Matches OpenSearch's default, avoiding mismatch errors

## Root Cause

The issue exists even with the HLL field type (PR #20129) because:

1. **HLL field type works correctly** for tier-n rollups (rollup → rollup)
   - `HllCardinalityAggregator` respects the field's precision
   - Sketches are merged correctly

2. **Standard cardinality aggregation has hardcoded precision** for raw data
   - When aggregating raw fields (not HLL fields), uses `CardinalityAggregator`
   - This aggregator has precision hardcoded to 14
   - No way to configure it

## Future Solution

### OpenSearch Core Enhancement Required

Add precision parameter to `CardinalityAggregationBuilder`:

```java
// Proposed API
CardinalityAggregationBuilder builder = new CardinalityAggregationBuilder("unique_users")
    .field("user_id")
    .precision(12);  // NEW: Set HLL precision for raw data aggregation
```

This would allow the standard `CardinalityAggregator` to create sketches with custom precision when aggregating raw data.

**Status**: Requires OpenSearch core changes
**Tracking**: File an issue at https://github.com/opensearch-project/OpenSearch/issues
**Timeline**: Unknown

## Workarounds for Custom Precision

If you absolutely need custom precision before OpenSearch 3.4:

### Workaround 1: Pre-compute HLL Sketches

Instead of rolling up raw data, pre-compute HLL sketches with your desired precision and store them in the source index:

```python
from datasketch import HyperLogLog

# Create sketch with precision 12
hll = HyperLogLog(p=12)
for value in user_ids:
    hll.update(value.encode('utf8'))

# Store sketch in source document
doc = {
    "timestamp": "2024-01-01T00:00:00Z",
    "user_id_hll": base64.b64encode(hll.serialize())
}
```

Then rollup the pre-computed sketches (tier-n rollup).

**Pros**: Full precision control
**Cons**: Requires application changes, more complex data pipeline

### Workaround 2: Accept Precision 14

For most use cases, precision 14 is sufficient:

| Precision | Error Rate | Memory per Sketch | Recommended For |
|-----------|------------|-------------------|-----------------|
| 10        | ~3.25%     | ~1.5 KB          | Low cardinality (<10K unique values) |
| 12        | ~1.6%      | ~1.5 KB          | Medium cardinality (10K-1M) |
| 14        | ~0.81%     | ~6 KB            | High cardinality (1M-100M) ✅ **Default** |
| 16        | ~0.41%     | ~24 KB           | Very high cardinality (100M+) |

**Pros**: Works today, no workarounds needed
**Cons**: Slightly higher memory usage than precision 12

## Testing with Custom Precision

If you want to test with custom precision (knowing it will fail):

```kotlin
// This will create mapping with precision 10
val rollup = Rollup(
    metrics = listOf(
        RollupMetrics(
            sourceField = "user_id",
            metrics = listOf(Cardinality(precision = 10))
        )
    )
)

// But aggregation will create sketches with precision 14
// Result: Precision mismatch error during indexing
```

**Expected error:**
```
HLL++ sketch precision mismatch for field [user_id.hll]: expected 10, got 14
```

## Recommendations

### For New Rollup Jobs

✅ **Use precision 14 (default)**
```json
{"cardinality": {}}
```

### For Existing Rollup Jobs with Custom Precision

If you have existing rollup jobs with precision != 14:

1. **Stop the rollup job**
2. **Update to precision 14**:
   ```bash
   PUT /_plugins/_rollup/jobs/my-rollup-job
   {
     "rollup": {
       "metrics": [{
         "source_field": "user_id",
         "metrics": [{"cardinality": {"precision": 14}}]
       }]
     }
   }
   ```
3. **Delete and recreate the target index** (to update mapping)
4. **Restart the rollup job**

### For Production Deployments

- **Document the limitation** in your rollup job configurations
- **Use precision 14** consistently across all tiers
- **Monitor for OpenSearch 3.4** release with native HLL field type
- **Plan migration** to custom precision once HLL field type is available

## Summary

**Current State**: Precision must be 14 for raw data rollups due to OpenSearch limitation

**Future State**: Custom precision will be fully supported with HLL field type (OpenSearch 3.4+)

**Action**: Use precision 14 (default) for all cardinality metrics until OpenSearch 3.4
