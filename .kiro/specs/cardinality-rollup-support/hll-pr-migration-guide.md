# HLL PR Migration Guide

## Overview

This document outlines the changes needed to migrate from binary type fallback to native HLL field type once OpenSearch PR #20129 merges.

---

## Current Implementation (Binary Fallback)

### Serialization (RollupIndexer.kt)
```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): String {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)  // Serializes InternalCardinality
    val bytes = output.bytes().toBytesRef().bytes
    return Base64.getEncoder().encodeToString(bytes)  // Base64 encoding
}
```

### Field Mapping (RollupMappingUtils.kt)
```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

### Dynamic Template (opendistro-rollup-target.json)
```json
{
  "cardinality_sketches": {
    "path_match": "*.hll",
    "mapping": {
      "type": "binary",
      "doc_values": false
    }
  }
}
```

---

## Required Changes After HLL PR Merges

### Change 1: Update Serialization (RollupIndexer.kt)

**From**:
```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): String {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)
    val bytes = output.bytes().toBytesRef().bytes
    return Base64.getEncoder().encodeToString(bytes)
}
```

**To**:
```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray {
    // Extract the raw HLL++ sketch from InternalCardinality
    val sketch = cardinality.getSketch()  // Returns AbstractHyperLogLogPlusPlus
    
    // Serialize just the sketch (not the entire InternalCardinality)
    val output = BytesStreamOutput()
    sketch.writeTo(output)
    
    // Return raw bytes (no Base64 encoding)
    return output.bytes().toBytesRef().bytes
}
```

**Key Changes**:
1. Extract raw sketch with `cardinality.getSketch()`
2. Serialize sketch with `sketch.writeTo()` (not `cardinality.writeTo()`)
3. Return `ByteArray` instead of `String`
4. Remove Base64 encoding

**Why**:
- `HllFieldMapper.validateSketchData()` expects `AbstractHyperLogLogPlusPlus` format
- Uses `AbstractHyperLogLogPlusPlus.readFrom()` for deserialization
- HLL field type handles binary data directly (no Base64 needed)

### Change 2: Update Field Mapping (RollupMappingUtils.kt)

**From**:
```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

**To**:
```kotlin
val hllMapping = """"hll":{"type":"hll","precision":$precision,"doc_values":true}"""
```

**Key Changes**:
1. Change type from `"binary"` to `"hll"`
2. Add `"precision":$precision` parameter
3. Change `"doc_values":false` to `"doc_values":true`

**Why**:
- HLL field type requires precision in mapping
- Doc values must be enabled for aggregations
- HLL field type provides specialized handling

### Change 3: Update Dynamic Template (opendistro-rollup-target.json)

**From**:
```json
{
  "cardinality_sketches": {
    "path_match": "*.hll",
    "mapping": {
      "type": "binary",
      "doc_values": false
    }
  }
}
```

**To**:
```json
{
  "cardinality_sketches": {
    "path_match": "*.hll",
    "mapping": {
      "type": "hll",
      "doc_values": true
    },
    "_comment": "Note: precision cannot be specified in dynamic templates, must be set in explicit mappings"
  }
}
```

**Key Changes**:
1. Change type from `"binary"` to `"hll"`
2. Change `"doc_values":false` to `"doc_values":true`
3. Add comment about precision limitation

**Why**:
- Dynamic templates don't support precision parameter
- Explicit mappings (via `buildCardinalityFieldMappings()`) will include precision
- Doc values required for HLL field type

### Change 4: Update Return Type (RollupIndexer.kt)

**From**:
```kotlin
is InternalCardinality -> {
    aggResults[it.name] = extractHLLSketch(it)  // Returns String
}
```

**To**:
```kotlin
is InternalCardinality -> {
    aggResults[it.name] = extractHLLSketch(it)  // Returns ByteArray
}
```

**No code change needed** - just ensure the return type change is compatible with indexing.

---

## Verification Steps

### Step 1: Verify Sketch Extraction

Test that `cardinality.getSketch()` returns `AbstractHyperLogLogPlusPlus`:

```kotlin
val cardinality: InternalCardinality = ...
val sketch = cardinality.getSketch()
println("Sketch type: ${sketch.javaClass.name}")
println("Sketch precision: ${sketch.precision()}")
```

Expected output:
```
Sketch type: org.opensearch.search.aggregations.metrics.HyperLogLogPlusPlus
Sketch precision: 14
```

### Step 2: Verify Serialization Format

Test that serialized sketch can be deserialized:

```kotlin
// Serialize
val sketch = cardinality.getSketch()
val output = BytesStreamOutput()
sketch.writeTo(output)
val bytes = output.bytes().toBytesRef().bytes

// Deserialize
val input = BytesArray(bytes).streamInput()
val deserializedSketch = AbstractHyperLogLogPlusPlus.readFrom(input, BigArrays.NON_RECYCLING_INSTANCE)

println("Original precision: ${sketch.precision()}")
println("Deserialized precision: ${deserializedSketch.precision()}")
println("Serialization successful: ${sketch.precision() == deserializedSketch.precision()}")
```

Expected output:
```
Original precision: 14
Deserialized precision: 14
Serialization successful: true
```

### Step 3: Test Field Mapping Creation

Verify field mapping includes precision:

```kotlin
val rollup = Rollup(
    metrics = listOf(
        RollupMetrics(
            sourceField = "user_id",
            targetField = "user_id",
            metrics = listOf(Cardinality(precision = 14))
        )
    )
)

val mapping = RollupMappingUtils.buildCardinalityFieldMappings(rollup)
println(mapping)
```

Expected output:
```json
{"properties":{"user_id":{"properties":{"hll":{"type":"hll","precision":14,"doc_values":true}}}}}
```

### Step 4: Test End-to-End Indexing

Create a rollup job and verify sketches are stored correctly:

```bash
# Create rollup job
PUT _plugins/_ism/rollups/test-rollup
{
  "rollup": {
    "source_index": "raw-data",
    "target_index": "rollup-index",
    "metrics": [{
      "source_field": "user_id",
      "metrics": [{"cardinality": {"precision": 14}}]
    }]
  }
}

# Check field mapping
GET rollup-index/_mapping

# Verify HLL field type
# Should show: "user_id.hll": {"type": "hll", "precision": 14, "doc_values": true}
```

### Step 5: Test Query Rewriting

Verify cardinality queries work on rollup index:

```bash
# Query rollup index
GET rollup-index/_search
{
  "size": 0,
  "aggs": {
    "unique_users": {
      "cardinality": {"field": "user_id"}
    }
  }
}

# Should return accurate cardinality estimate
# No errors about binary field type
```

---

## Rollback Plan

If issues arise after migration:

### Rollback Step 1: Revert Serialization

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): String {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)
    val bytes = output.bytes().toBytesRef().bytes
    return Base64.getEncoder().encodeToString(bytes)
}
```

### Rollback Step 2: Revert Field Mapping

```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

### Rollback Step 3: Revert Dynamic Template

```json
{
  "type": "binary",
  "doc_values": false
}
```

### Rollback Step 4: Delete and Recreate Rollup Indices

```bash
# Delete rollup indices with HLL type
DELETE rollup-index-*

# Recreate rollup jobs (will use binary type)
```

---

## Timeline

### Phase 1: Preparation (Current)
- ✅ Code is structured to accept precision parameter
- ✅ Comments indicate where changes are needed
- ✅ Migration guide documented

### Phase 2: HLL PR Merges
- 🔄 Monitor OpenSearch PR #20129 status
- 🔄 Test HLL field type in development environment
- 🔄 Verify serialization format compatibility

### Phase 3: Migration
- 🔄 Update serialization code
- 🔄 Update field mappings
- 🔄 Update dynamic templates
- 🔄 Run verification tests

### Phase 4: Validation
- 🔄 Test Tier-1 rollups
- 🔄 Test Tier-N rollups
- 🔄 Test query rewriting
- 🔄 Performance testing

---

## Summary

**Current State**: Using binary type fallback with Base64 encoding
**Target State**: Using native HLL field type with raw sketch bytes
**Migration Effort**: 4 code changes + testing
**Risk Level**: Low (clear rollback path, well-documented)
**Benefits**: Better performance, automatic sketch merging, query rewriting support

The migration is straightforward and low-risk. The code is already structured to support the changes, and we have a clear rollback plan if needed.
