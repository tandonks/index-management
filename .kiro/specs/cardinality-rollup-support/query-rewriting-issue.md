# Query Rewriting Issue: The Missing Piece

## The Realization

Users query: `"cardinality": { "field": "value" }`

The system should automatically rewrite to: `"cardinality": { "field": "value.hll" }`

## What This Means

When you ran:
```json
{
  "terms": { "field": "category" },
  "aggs": {
    "cardinality_value": {
      "cardinality": { "field": "value" }
    }
  }
}
```

On the Tier-1 rollup index, the system should have:
1. Detected this is a rollup index
2. Detected `value` has a cardinality metric
3. Rewritten the query to use `value.hll`
4. Returned the cardinality from the HLL sketch

## The Question

**Did your query return the correct cardinality?**

If YES:
- ✅ Query rewriting works for terms aggregations
- ✅ HLL field type works with terms aggregations
- ❌ Query rewriting doesn't work for composite aggregations (or composite doesn't support HLL)

If NO (returned wrong value or 0):
- ❌ Query rewriting doesn't work at all
- This would be a different issue

## The Real Issue Might Be

### Hypothesis 1: Query Rewriting Doesn't Work for Composite Aggregations

**Terms aggregation** (works):
```
User query: cardinality(field="value")
↓ Query rewriting
Actual query: cardinality(field="value.hll")
↓ HLL field type
Returns correct cardinality ✅
```

**Composite aggregation** (broken):
```
User query: composite + cardinality(field="value")
↓ Query rewriting doesn't happen?
Actual query: composite + cardinality(field="value")
↓ Tries to compute cardinality on numeric field
Returns 0 or wrong value ❌
```

### Hypothesis 2: Composite Aggregations Bypass Query Rewriting

The query rewriting mechanism might not intercept composite aggregations, so:
- Regular aggregations get rewritten ✅
- Composite aggregations don't get rewritten ❌

## How Query Rewriting Works (from HLL PR)

The HLL PR should include a query rewriting component that:
1. Intercepts cardinality aggregation requests
2. Checks if the field has an HLL sketch (`.hll` subfield)
3. Rewrites the query to use the HLL field
4. Passes the rewritten query to the aggregation framework

**But**: This might only work for certain aggregation types!

## The Critical Test

We need to check what field is actually being queried in the composite aggregation.

### Add Logging to See Actual Field

In the rollup indexer, when we see `InternalCardinality`, log what field it was computed on:

```kotlin
is InternalCardinality -> {
    logger.info("InternalCardinality - name: ${it.name}, value: ${it.value()}")
    logger.info("InternalCardinality - metadata: ${it.metadata}")
    // Try to get the field name if available
}
```

This will tell us if the composite aggregation is:
- Querying `value` (query rewriting didn't happen)
- Querying `value.hll` (query rewriting happened, but HLL field returns 0)

## Implications

### If Query Rewriting Doesn't Work for Composite Aggregations

**Problem**: The composite aggregation is trying to compute cardinality on the numeric `value` field, not the HLL sketch.

**Why it returns 0**: The numeric field might not have the right data, or cardinality on a single numeric value per bucket is always 1 or 0.

**Solution**: We need to either:
1. Fix query rewriting to work with composite aggregations
2. Manually specify `value.hll` in the rollup query
3. Use a different aggregation approach

### If Query Rewriting Works But HLL Field Returns 0

**Problem**: The HLL field type doesn't work correctly in composite aggregation context.

**Solution**: Fix composite aggregation support for HLL fields in OpenSearch.

## Next Steps

1. **Check your terms aggregation results**: Did it return the correct cardinality value?

2. **Add logging** to see what's happening in the composite aggregation

3. **Test manually specifying `.hll`**: Try running the Tier-2 rollup but manually change the query to use `value.hll` instead of `value`

## The Key Question

**When you ran the terms aggregation on Tier-1 rollup with `"field": "value"`, what cardinality did it return?**

- If it returned the correct cardinality → Query rewriting works for terms
- If it returned 0 or wrong value → Query rewriting doesn't work at all

This will tell us exactly where the problem is!
