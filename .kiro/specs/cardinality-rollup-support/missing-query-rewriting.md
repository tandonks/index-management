# Missing Query Rewriting Implementation

## The Problem

The implementation summary claims query rewriting was completed:
> ### Phase 4: Query Rewriting (Task 6)
> - ✅ Cardinality aggregation rewriting (`user_id` → `user_id.hll_sketch`)
> - ✅ Integration with existing RollupInterceptor

**But the code doesn't exist!**

Searches for:
- `RollupUtils` - Not found
- `RollupInterceptor` - Not found
- `rewrite` / `Rewrite` - Not found

## The Evidence

When you queried the Tier-1 rollup with:
```json
{
  "cardinality": { "field": "value" }
}
```

It returned `cardinality = doc_count`, proving it queried the numeric `value` field, not `value.hll`.

## What Needs to Be Implemented

### For User Queries (Future - HLL PR Feature)

This is what the HLL PR should provide - automatic query rewriting for user queries.

**Not our responsibility** - this is OpenSearch core functionality.

### For Rollup-to-Rollup Queries (Our Responsibility)

When creating a Tier-2+ rollup, **we** build the aggregation query. We need to use `.hll` fields explicitly.

## Where to Implement

The rollup system builds aggregation queries somewhere to query the source index. We need to find that code and modify it to:

1. **Detect if source is a rollup index**
2. **For cardinality metrics, use `.hll` suffix**

### Pseudo-code

```kotlin
fun buildAggregationForMetric(metric: Metric, sourceField: String, isSourceRollup: Boolean): AggregationBuilder {
    return when (metric) {
        is Cardinality -> {
            val fieldToQuery = if (isSourceRollup) {
                "$sourceField.hll"  // Query HLL field in rollup index
            } else {
                sourceField  // Query raw field in source data
            }
            
            CardinalityAggregationBuilder("$sourceField.hll")  // Output field name
                .field(fieldToQuery)  // Input field name
                .precisionThreshold(metric.precision)
        }
        // ... other metrics
    }
}
```

## The Fix

We need to:

1. **Find where rollup builds aggregation queries**
2. **Add logic to detect if source is a rollup index**
3. **Use `.hll` suffix for cardinality when source is rollup**

This will fix:
- ✅ Tier-2+ rollup aggregations
- ✅ Composite aggregations (if they work with HLL fields)

This won't fix:
- ❌ User queries on rollup indices (requires HLL PR query rewriting)

## Timeline

- Find aggregation building code: 30 minutes
- Implement fix: 1 hour
- Test: 1 hour
- **Total: 2.5 hours**

## Next Steps

1. Search for where rollup creates `SearchRequest` or `SearchSourceBuilder`
2. Find where metrics are converted to aggregation builders
3. Add `.hll` suffix logic for cardinality metrics
4. Test with Tier-2 rollup
