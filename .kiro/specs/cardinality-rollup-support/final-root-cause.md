# Final Root Cause: Query Rewriting Not Working

## The Discovery

From the terms aggregation test on Tier-1 rollup:
```json
{
  "key": "electronics",
  "doc_count": 2,
  "cardinality_value": {"value": 2}  // ← Equals doc_count!
}
```

**The cardinality equals the document count**, which means it's computing cardinality of the `value` field (a numeric field), not the `value.hll` field (the HLL sketch).

## The Two Problems

### Problem 1: Query Rewriting Doesn't Work
**Terms/Date Histogram Aggregations**:
```
User query: cardinality(field="value")
Expected: Rewrite to cardinality(field="value.hll")
Actual: No rewriting, queries "value" directly
Result: Wrong cardinality (equals doc_count)
```

### Problem 2: Composite Aggregations Return 0 for HLL Fields
**Composite Aggregations**:
```
Query: cardinality(field="value.hll")  // Somehow this field is used
Result: Returns 0
```

From the logs, we see `value.hll` in the aggregation name, suggesting the composite aggregation IS querying the HLL field, but getting 0.

## Why This Happens

### Query Rewriting Feature
The HLL PR includes a query rewriting feature that should:
1. Intercept cardinality aggregation requests
2. Check if the field has an `.hll` subfield
3. Rewrite the query to use the HLL field

**But this feature either**:
- Isn't implemented in the version we're using
- Isn't enabled by default
- Requires configuration
- Has a bug

### Composite Aggregation Issue
Even when the HLL field is queried directly (in composite aggregations), it returns 0.

This suggests composite aggregations have a separate issue with HLL fields.

## The Evidence

### Terms Aggregation (Wrong Result)
```bash
curl 'http://localhost:9200/rollup-index-1/_search' -d '{
  "aggs": {
    "by_category": {
      "terms": { "field": "category" },
      "aggs": {
        "cardinality_value": { "cardinality": { "field": "value" } }
      }
    }
  }
}'
```

**Result**: `cardinality_value = doc_count` (wrong!)

### Composite Aggregation (Returns 0)
```
Composite aggregation with cardinality sub-agg
```

**Result**: `value.hll = 0` (from logs)

## The Solution

We need to **explicitly use `.hll` fields** in rollup queries instead of relying on query rewriting.

### For Tier-2+ Rollups

When rolling up a rollup index (Tier-2, Tier-3, etc.), the system should:

1. **Detect that source is a rollup index**
2. **Use `.hll` fields explicitly** for cardinality metrics
3. **Don't rely on query rewriting**

### Implementation

When building the aggregation query for a Tier-N rollup:

```kotlin
// Current (relies on query rewriting):
CardinalityAggregationBuilder("value.hll")
    .field("value")  // ← Expects rewriting to "value.hll"

// Fixed (explicit HLL field):
CardinalityAggregationBuilder("value.hll")
    .field("value.hll")  // ← Explicitly use HLL field
```

## Where to Fix

We need to find where the rollup creates aggregation queries and modify it to:

1. **Check if source is a rollup index**
2. **If yes, use `.hll` suffix for cardinality fields**
3. **If no, use regular field name**

### Pseudo-code

```kotlin
fun buildCardinalityAggregation(fieldName: String, isSourceRollup: Boolean): CardinalityAggregationBuilder {
    val actualFieldName = if (isSourceRollup) {
        "$fieldName.hll"  // Use HLL field for rollup sources
    } else {
        fieldName  // Use regular field for raw data sources
    }
    
    return CardinalityAggregationBuilder("$fieldName.hll")
        .field(actualFieldName)
        .precisionThreshold(precision)
}
```

## But Wait - Composite Aggregations Still Return 0!

Even if we explicitly use `value.hll`, the composite aggregation returns 0.

This means we STILL have the composite aggregation issue to solve.

### Two-Part Fix Required

1. **Part 1**: Explicitly use `.hll` fields (fixes terms/date_histogram aggregations)
2. **Part 2**: Fix why composite aggregations return 0 for HLL fields

## Next Steps

### Immediate: Fix Query Field Names

1. Find where rollup builds aggregation queries
2. Add logic to use `.hll` suffix when source is a rollup index
3. Test with terms aggregation - should now return correct cardinality

### After That: Fix Composite Aggregation

Once terms aggregations work, we still need to solve why composite aggregations return 0.

Options:
1. Fix composite aggregation support for HLL fields
2. Use alternative aggregation approach (nested terms/date_histogram)
3. Manual sketch merging as fallback

## Timeline

### Part 1: Fix Field Names (2-4 hours)
- Find aggregation building code: 1 hour
- Implement fix: 1 hour
- Test: 1-2 hours

### Part 2: Fix Composite Aggregations (TBD)
- Depends on root cause
- Could be 4 hours to 4 days

## Success Criteria

### Part 1 Success
- Terms aggregation on Tier-1 rollup returns correct cardinality
- Date histogram aggregation on Tier-1 rollup returns correct cardinality

### Part 2 Success
- Composite aggregation on Tier-1 rollup returns correct cardinality
- Tier-2 rollups work correctly
- Multi-tier rollups work correctly

## Key Insight

**We were looking at serialization when the problem was in query construction!**

The sketches are stored correctly, the HLL field type works, but we're not querying the right field.

This is actually good news - it's easier to fix query construction than to fix serialization formats or OpenSearch core issues!
