# Field Structure Simplification

## Changes Made

We've simplified the cardinality field structure to be cleaner and more consistent with the HLL field type from OpenSearch PR #20129.

### Before (Complex Structure)
```json
{
  "user_id": {
    "properties": {
      "hll_sketch": {
        "properties": {
          "sketch": {"type": "hll"}
        }
      }
    }
  }
}
```

**Field path**: `user_id.hll_sketch.sketch`
**Issues**:
- Too many nesting levels
- Redundant naming (`hll_sketch.sketch`)
- More complex query rewriting

### After (Simplified Structure)
```json
{
  "user_id": {
    "properties": {
      "hll": {"type": "hll", "doc_values": false}
    }
  }
}
```

**Field path**: `user_id.hll`
**Benefits**:
- ✅ Cleaner, simpler structure
- ✅ Consistent with other metrics (`value.sum`, `value.max`, etc.)
- ✅ Easier query rewriting
- ✅ More intuitive field naming

## Files Modified

### 1. RollupMetrics.kt
```kotlin
// Before
is Cardinality -> "$targetField.hll_sketch"

// After
is Cardinality -> "$targetField.hll"
```

### 2. RollupIndexer.kt
```kotlin
// Before
aggResults["${it.name}.sketch"] = extractHLLSketch(it)

// After
aggResults[it.name] = extractHLLSketch(it)
```

**Explanation**: The aggregation name is already `user_id.hll`, so we don't need to add `.sketch`.

### 3. RollupMappingUtils.kt
```kotlin
// Before
val hllMapping = """
    "hll_sketch":{
        "properties":{
            "sketch":{"type":"hll","doc_values":false}
        }
    }
"""

// After
val hllMapping = """"hll":{"type":"hll","doc_values":false}"""
```

### 4. opendistro-rollup-target.json
```json
// Before
{
  "path_match": "*.hll_sketch.sketch",
  "mapping": {"type": "hll", "doc_values": false}
}

// After
{
  "path_match": "*.hll",
  "mapping": {"type": "hll", "doc_values": false}
}
```

### 5. RollupUtils.kt
```kotlin
// Before
// Rewritten query: cardinality(field="user_id.hll_sketch")

// After
// Rewritten query: cardinality(field="user_id.hll")
```

## Impact on Usage

### Rollup Index Mapping
```json
{
  "value": {
    "properties": {
      "sum": {"type": "float"},
      "max": {"type": "float"},
      "min": {"type": "float"},
      "hll": {"type": "hll", "doc_values": false}  // Simplified!
    }
  }
}
```

### Rollup Documents
```json
{
  "timestamp.date_histogram": "2024-01-01T00:00:00Z",
  "category.terms": "electronics",
  "value.sum": 1599.99,
  "value.max": 1599.99,
  "value.min": 1599.99,
  "value.hll": "EHZhbHVlLmhsbF9za2V0Y2j..."  // Simplified field name!
}
```

### Query Rewriting
```
Customer Query:    cardinality(field="value")
Rewritten Query:   cardinality(field="value.hll")  // Cleaner!
```

## Consistency with Other Metrics

All metrics now follow the same pattern:

| Metric | Field Name | Type |
|--------|------------|------|
| Sum | `value.sum` | `float` |
| Max | `value.max` | `float` |
| Min | `value.min` | `float` |
| Avg | `value.avg` | (scripted) |
| Value Count | `value.value_count` | `long` |
| **Cardinality** | **`value.hll`** | **`hll`** |

## Alignment with OpenSearch HLL PR #20129

The simplified structure better aligns with the HLL field type:
- Direct `type: "hll"` field (not nested under `.sketch`)
- Cleaner field paths for the HLL field mapper
- More intuitive for the HLL field data access

## Migration Notes

**Breaking Change**: This is a breaking change from the previous implementation.

**If you have existing rollup indices**:
1. Old indices use `user_id.hll_sketch.sketch`
2. New indices will use `user_id.hll`
3. Query rewriting will need to detect which format is used

**Recommendation**: Since this is still in development, this is the right time to make this change before any production usage.

## Summary

The field structure simplification:
- ✅ Reduces nesting from 3 levels to 2 levels
- ✅ Makes field names more intuitive (`user_id.hll` vs `user_id.hll_sketch.sketch`)
- ✅ Aligns better with OpenSearch HLL field type
- ✅ Maintains consistency with other rollup metrics
- ✅ Simplifies query rewriting logic
