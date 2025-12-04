# Cardinality Sketch Field Mapping Fix

## Problem

The cardinality sketch field was being created with the wrong type:
- **Expected**: `type: "binary"` (for efficient binary data storage)
- **Actual**: `type: "keyword"` (treating Base64 string as text)

## Root Cause

Dynamic templates in `opendistro-rollup-target.json` don't apply to fields that are explicitly created by the first indexed document. When the first rollup document was indexed:

1. OpenSearch saw a Base64-encoded string in the `value.hll_sketch.sketch` field
2. It inferred the type as `keyword` (string type)
3. The dynamic template pattern `*.hll_sketch.sketch` never got a chance to apply

## Solution

Created **explicit field mappings** that are applied when the rollup index is created, before any documents are indexed.

### Changes Made

1. **Created `RollupMappingUtils.kt`**:
   - `buildCardinalityMappings()`: Analyzes rollup job configuration and builds explicit mappings for cardinality metrics
   - `addCardinalityMapping()`: Creates the nested structure `targetField.hll_sketch.sketch` with `type: "binary"`

2. **Updated `RollupMapperService.kt`**:
   - Modified `createTargetIndex()` to accept the `Rollup` parameter
   - Merges base rollup mappings with cardinality-specific mappings
   - Ensures sketch fields are created with correct type before first document is indexed

3. **Updated `opendistro-rollup-target.json`**:
   - Changed dynamic template pattern from `*.sketch` to `*.hll_sketch.sketch` for better specificity
   - Dynamic template now serves as a fallback for any edge cases

### Result

New rollup indices will have the correct mapping:

```json
{
  "value": {
    "properties": {
      "hll_sketch": {
        "properties": {
          "sketch": {
            "type": "binary",      // ✅ Correct!
            "doc_values": false
          },
          "value": {
            "type": "long"
          }
        }
      }
    }
  }
}
```

## Why Both Explicit Mappings AND Dynamic Templates?

- **Explicit mappings**: Ensure correct type for known cardinality metrics in the rollup job
- **Dynamic templates**: Provide fallback for edge cases or future extensions

This layered approach provides robustness and flexibility.

## Testing

To verify the fix:
1. Delete existing rollup index: `DELETE rollup-index-1`
2. Re-run the rollup job
3. Check the mapping: `GET rollup-index-1/_mapping`
4. Verify `value.hll_sketch.sketch` has `type: "binary"`

## Impact

- **Storage efficiency**: Binary storage is more efficient than keyword storage for sketch data
- **Query performance**: Binary fields are optimized for the operations we need
- **Correctness**: Ensures sketches can be properly deserialized for multi-tier rollups
