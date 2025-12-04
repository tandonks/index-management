# Dynamic Template Approach for Cardinality Sketches

## Why Dynamic Templates?

The rollup target index uses **dynamic templates** instead of explicit field mappings for a good reason:

### How Rollup Indices Work

1. **No explicit field mappings**: The rollup target index is created with ONLY dynamic templates (see `opendistro-rollup-target.json`)
2. **Fields created on-demand**: When rollup documents are indexed, fields are created dynamically based on the rollup job configuration
3. **Variable field names**: Different rollup jobs can have different source fields, so we can't predict all field names in advance

### Why Cardinality Needs a Dynamic Template

Unlike other metrics (sum, max, min, avg) which use standard numeric types that OpenSearch can infer, cardinality sketches require:

- **Binary type**: HLL++ sketches must be stored as binary data
- **No doc_values**: Binary fields don't support doc values and must explicitly disable them
- **Pattern matching**: Since we don't know field names in advance, we use `path_match: "*.hll_sketch.sketch"` to match any sketch field

### Comparison with Other Metrics

| Metric Type | Field Pattern | Type | How It's Mapped |
|-------------|---------------|------|-----------------|
| Sum | `value.sum` | `float` | OpenSearch infers from numeric data |
| Max | `value.max` | `float` | OpenSearch infers from numeric data |
| Min | `value.min` | `float` | OpenSearch infers from numeric data |
| Avg | `value.avg.sum`, `value.avg.value_count` | `float`, `long` | OpenSearch infers from numeric data |
| Cardinality | `value.hll_sketch.sketch` | `binary` | **Requires dynamic template** |

### The Dynamic Template

```json
{
  "cardinality_sketches": {
    "path_match": "*.hll_sketch.sketch",
    "mapping": {
      "type": "binary",
      "doc_values": false
    }
  }
}
```

This template:
- Matches any field ending in `.hll_sketch.sketch` (e.g., `value.hll_sketch.sketch`, `user_id.hll_sketch.sketch`)
- Sets the type to `binary` (required for HLL++ sketch storage)
- Disables doc_values (binary fields don't support them)

## Why Not Explicit Mappings?

Explicit mappings would require:
1. Knowing all possible field names in advance (impossible with dynamic rollup jobs)
2. Creating mappings for every possible source field (not scalable)
3. Updating mappings every time a new rollup job is added (error-prone)

## Pattern Update

Changed from `*.sketch` to `*.hll_sketch.sketch` to be more specific and match the actual field structure:
- Old: `*.sketch` (too broad, could match unrelated fields)
- New: `*.hll_sketch.sketch` (precise, matches only cardinality sketch fields)

This ensures the dynamic template only applies to cardinality sketch fields and doesn't interfere with other potential sketch-related fields in the future.
