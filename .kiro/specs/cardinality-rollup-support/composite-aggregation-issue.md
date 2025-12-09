# Composite Aggregation Issue with HLL Fields

## The Discovery

The cardinality returns 0 **during the composite aggregation**, not during serialization!

### Evidence

**Logs show cardinality = 0 in the aggregation results**:
```
Bucket key: {timestamp.date_histogram=1683108000000, category.terms=electronics}, 
docCount: 1, 
aggregations: [value.hll: {"value.hll":{"value":0}}, ...]
```

**But regular search aggregation works**:
- Querying Tier-1 rollup with cardinality aggregation returns correct values
- This proves the sketches are stored and readable correctly

## The Problem

**Composite aggregations with cardinality sub-aggregations on HLL fields don't work correctly.**

### What's Happening

1. **Tier-1 Rollup** (source = raw data):
   ```json
   {
     "composite": {
       "sources": [
         { "timestamp": { "date_histogram": {...} } },
         { "category": { "terms": {...} } }
       ]
     },
     "aggs": {
       "value.hll": { "cardinality": { "field": "user_id" } }
     }
   }
   ```
   ✅ Works - aggregating over raw `user_id` field

2. **Tier-2 Rollup** (source = Tier-1 rollup):
   ```json
   {
     "composite": {
       "sources": [
         { "timestamp": { "date_histogram": {...} } },
         { "category": { "terms": {...} } }
       ]
     },
     "aggs": {
       "value.hll": { "cardinality": { "field": "user_id.hll" } }
     }
   }
   ```
   ❌ Returns 0 - aggregating over HLL field `user_id.hll`

## Why This Happens

### Hypothesis 1: Composite Aggregation Doesn't Support HLL Fields

Composite aggregations might not properly route to `HllCardinalityAggregator` for sub-aggregations.

**Evidence**:
- Regular aggregations work (they use `HllCardinalityAggregator`)
- Composite aggregations return 0 (might use different code path)

### Hypothesis 2: HLL Field Type Not Recognized in Composite Context

The composite aggregation framework might not recognize HLL fields as valid for cardinality aggregations.

**Evidence**:
- The aggregation completes without error
- But returns 0 instead of throwing an error or using the correct aggregator

### Hypothesis 3: Sub-Aggregation Context Issue

Composite aggregations create a different context for sub-aggregations that doesn't properly initialize HLL field data.

**Evidence**:
- Regular aggregations have full context
- Composite sub-aggregations might have limited context

## Testing the Hypothesis

### Test 1: Regular Cardinality Aggregation on Tier-1 Rollup

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "unique_users": {
      "cardinality": { "field": "user_id.hll" }
    }
  }
}
```

**Expected**: Returns correct cardinality (you said this works)

### Test 2: Terms Aggregation with Cardinality Sub-Agg on Tier-1 Rollup

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "by_category": {
      "terms": { "field": "category.terms" },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Question**: Does this work? If yes, the issue is specific to composite aggregations.

### Test 3: Composite Aggregation with Cardinality Sub-Agg on Tier-1 Rollup

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "composite_agg": {
      "composite": {
        "sources": [
          { "category": { "terms": { "field": "category.terms" } } }
        ]
      },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Question**: Does this return 0? If yes, we've confirmed the issue is with composite aggregations.

## The Real Issue

Looking at the HLL PR analysis, I don't see any mention of composite aggregation support!

### From HLL PR Analysis

The PR adds:
- ✅ `HllFieldMapper` - field type registration
- ✅ `HllFieldData` - field data access
- ✅ `HllCardinalityAggregator` - cardinality aggregation support

**But**: No mention of composite aggregation integration!

### How Aggregations Work

```java
// CardinalityAggregatorFactory
if (config.fieldContext() != null) {
    MappedFieldType fieldType = config.fieldContext().fieldType();
    if (fieldType instanceof HllFieldMapper.HllFieldType hllFieldType) {
        // Use HllCardinalityAggregator
        return new HllCardinalityAggregator(...);
    }
}
// Otherwise use regular CardinalityAggregator
```

**Question**: Does composite aggregation go through this same code path?

## Potential Root Causes

### Option 1: Composite Aggregation Uses Different Factory

Composite aggregations might create sub-aggregators differently, bypassing the HLL field type check.

### Option 2: Field Context Not Available in Composite Sub-Aggs

The `config.fieldContext()` might be null or incomplete in composite sub-aggregation context.

### Option 3: HLL Field Data Not Accessible in Composite Context

The composite aggregation framework might not properly initialize field data for HLL fields.

### Option 4: Composite Aggregation Bug

There might be a bug in how composite aggregations handle custom field types.

## Investigation Steps

### Step 1: Verify Regular vs Composite Behavior

Run the three tests above to confirm:
1. Regular cardinality on HLL field: ✅ Works
2. Terms + cardinality sub-agg on HLL field: ❓ Test this
3. Composite + cardinality sub-agg on HLL field: ❌ Returns 0

### Step 2: Check OpenSearch Source

Look at how composite aggregations create sub-aggregators:
```java
// CompositeAggregator or similar
// How does it create sub-aggregators?
// Does it pass the correct context?
```

### Step 3: Check HLL PR for Composite Support

Search the HLL PR for:
- "composite"
- "CompositeAggregator"
- Any tests with composite aggregations

### Step 4: Add Debug Logging

Add logging to see which aggregator is being used:
```kotlin
// In the aggregation result processing
when (it) {
    is InternalCardinality -> {
        logger.info("InternalCardinality class: ${it.javaClass.name}")
        logger.info("InternalCardinality value: ${it.value()}")
        // Check if it's using HllCardinalityAggregator or regular CardinalityAggregator
    }
}
```

## Potential Solutions

### Solution 1: Use Different Aggregation Type

Instead of composite aggregation, use terms aggregation with sub-aggregations:
```json
{
  "aggs": {
    "by_timestamp": {
      "date_histogram": { "field": "timestamp.date_histogram" },
      "aggs": {
        "by_category": {
          "terms": { "field": "category.terms" },
          "aggs": {
            "unique_users": {
              "cardinality": { "field": "user_id.hll" }
            }
          }
        }
      }
    }
  }
}
```

**Pros**: Might work if the issue is specific to composite aggregations
**Cons**: Different structure, might not fit rollup framework

### Solution 2: Fix Composite Aggregation Support

If composite aggregations don't support HLL fields, we need to:
1. Identify why they don't work
2. Fix the composite aggregation framework
3. Or contribute to OpenSearch to add support

### Solution 3: Manual Sketch Merging

If composite aggregations can't use HLL fields, we might need to:
1. Read the HLL field values directly
2. Manually merge the sketches
3. Return the merged result

**This is complex and defeats the purpose of the HLL field type!**

### Solution 4: Wait for HLL PR to Add Composite Support

The HLL PR might need to add explicit composite aggregation support.

## Next Steps

### Immediate

1. **Run Test 2** (terms + cardinality sub-agg) to see if it works
2. **Run Test 3** (composite + cardinality sub-agg) to confirm the issue
3. **Check the logs** to see which aggregator class is being used

### If Composite Aggregations Don't Support HLL

1. **Search OpenSearch issues** for composite + HLL
2. **Check HLL PR** for composite aggregation tests
3. **Contact OpenSearch team** about composite aggregation support
4. **Consider alternative aggregation approaches**

### If It's a Bug

1. **File an issue** with OpenSearch
2. **Provide reproduction steps**
3. **Consider workarounds** in the meantime

## Impact on Rollup

This is a **critical issue** because:
- Rollups use composite aggregations for efficiency
- Without composite aggregation support, Tier-N rollups won't work
- We might need to redesign the rollup aggregation approach

## Conclusion

The issue is **not with serialization** - it's with **composite aggregations not supporting HLL fields**.

This is a much bigger issue than we initially thought, and might require:
1. Changes to OpenSearch core (composite aggregation framework)
2. Changes to HLL PR (add composite aggregation support)
3. Or changes to ISM rollup (use different aggregation approach)

**We need to test and confirm this hypothesis immediately!**
