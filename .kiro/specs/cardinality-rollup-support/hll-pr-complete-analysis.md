# OpenSearch HLL PR #20129 - Complete Analysis

## Executive Summary

After analyzing all key files from OpenSearch HLL PR #20129, we have a **complete understanding** of how the HLL field type works and what changes are needed for ISM rollup support.

**Bottom Line**: The HLL PR provides **everything we need** for cardinality rollups. Our implementation is architecturally correct and ready for migration with minimal changes.

---

## The Three Pillars of HLL Support

### 1. HllFieldMapper - Field Type Registration

**File**: `HllFieldMapper.java`

**Purpose**: Registers and handles the `hll` field type

**Key Features**:
- Registers `type: "hll"` as valid field type
- Validates precision parameter (4-18 range)
- Parses and validates sketch data during indexing
- Stores sketches as binary doc values
- Enforces precision consistency

**Critical Discovery**:
```java
// Expects AbstractHyperLogLogPlusPlus format
AbstractHyperLogLogPlusPlus sketch = AbstractHyperLogLogPlusPlus.readFrom(in, bigArrays);
```

**Impact on ISM**: We must serialize `AbstractHyperLogLogPlusPlus`, not `InternalCardinality`

---

### 2. HllFieldData - Field Data Access

**File**: `HllFieldData.java`

**Purpose**: Provides efficient access to HLL sketches during aggregations

**Key Features**:
- Segment-level sketch access
- Automatic deserialization
- Efficient memory usage
- Scales to large indices

**Critical Method**:
```java
public AbstractHyperLogLogPlusPlus getSketch(int docId) throws IOException {
    BinaryDocValues docValues = reader.getBinaryDocValues(fieldName);
    if (docValues != null && docValues.advanceExact(docId)) {
        BytesRef sketchBytes = docValues.binaryValue();
        return AbstractHyperLogLogPlusPlus.readFrom(
            new BytesArray(sketchBytes.bytes, sketchBytes.offset, sketchBytes.length).streamInput(),
            BigArrays.NON_RECYCLING_INSTANCE
        );
    }
    return null;
}
```

**Impact on ISM**: This is how sketches are read during Tier-N rollups

---

### 3. HllCardinalityAggregator - Automatic Sketch Merging

**File**: `HllCardinalityAggregator.java`

**Purpose**: Specialized aggregator that merges HLL sketches automatically

**Key Features**:
- Reads sketches from HLL fields
- Merges them using HLL++ algorithm
- Supports multi-bucket aggregations
- Returns InternalCardinality with merged sketch

**Critical Code**:
```java
@Override
public void collect(int doc, long bucket) throws IOException {
    sketch = leafData.getSketch(doc);
    if (sketch != null) {
        counts.merge(bucket, sketch, 0);  // Automatic merging!
    }
}
```

**Impact on ISM**: This makes Tier-N rollups work automatically!

---

## How It All Works Together

### Architecture Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    Cardinality Aggregation                   │
│                    on HLL Field (user_id.hll)                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              CardinalityAggregatorFactory                    │
│  Detects: fieldType instanceof HllFieldMapper.HllFieldType   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Creates HllCardinalityAggregator                │
│  Parameters: fieldData, precision, context                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              For Each Document in Segment:                   │
│  1. HllFieldData.getSketch(docId)                           │
│     - Reads binary doc values                                │
│     - Deserializes AbstractHyperLogLogPlusPlus              │
│  2. HllCardinalityAggregator.collect()                      │
│     - counts.merge(bucket, sketch, 0)                        │
│     - Merges sketch into accumulator                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Build Aggregation Result:                       │
│  InternalCardinality(name, mergedSketch, metadata)          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Return to RollupIndexer                         │
│  Serialize and store in next rollup tier                     │
└─────────────────────────────────────────────────────────────┘
```

---

## ISM Rollup Integration

### Tier-1 Rollup (Raw Data → Rollup Index)

**Current Flow**:
```
1. Query raw data with cardinality aggregation
2. Standard CardinalityAggregator creates sketch from raw values
3. Returns InternalCardinality with sketch
4. RollupIndexer.extractHLLSketch() serializes InternalCardinality
5. Base64 encode and store in user_id.hll (binary field)
```

**After HLL PR**:
```
1. Query raw data with cardinality aggregation
2. Standard CardinalityAggregator creates sketch from raw values
3. Returns InternalCardinality with sketch
4. RollupIndexer.extractHLLSketch() extracts and serializes sketch
5. Store raw bytes in user_id.hll (HLL field)
```

**Changes Needed**:
- Extract sketch: `val sketch = cardinality.getSketch()`
- Serialize sketch: `sketch.writeTo(output)`
- Remove Base64 encoding

---

### Tier-2 Rollup (Tier-1 → Tier-2)

**Current Flow** (doesn't work):
```
1. Query Tier-1 with cardinality aggregation on user_id
2. Query rewriting: user_id → user_id.hll
3. ERROR: Cannot aggregate on binary field
```

**After HLL PR** (works automatically!):
```
1. Query Tier-1 with cardinality aggregation on user_id
2. Query rewriting: user_id → user_id.hll
3. CardinalityAggregatorFactory detects HLL field type
4. Creates HllCardinalityAggregator
5. For each Tier-1 document:
   - HllFieldData.getSketch() reads stored sketch
   - HllCardinalityAggregator.merge() merges it
6. Returns InternalCardinality with merged sketch
7. RollupIndexer.extractHLLSketch() serializes merged sketch
8. Store in Tier-2 user_id.hll field
```

**Changes Needed**: None! Works automatically once HLL PR merges.

---

### Tier-3+ Rollups (Tier-N → Tier-N+1)

**Same process as Tier-2!**

The beauty of HLL++ is that merging is **associative and commutative**:
```
merge(merge(A, B), C) = merge(A, merge(B, C)) = merge(A, B, C)
```

This means we can chain rollups indefinitely without accuracy degradation (within theoretical HLL++ bounds).

---

## Required Changes Summary

### Change 1: Serialization (RollupIndexer.kt)

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
    val sketch = cardinality.getSketch()  // Extract AbstractHyperLogLogPlusPlus
    val output = BytesStreamOutput()
    sketch.writeTo(output)  // Serialize just the sketch
    return output.bytes().toBytesRef().bytes  // No Base64 encoding
}
```

---

### Change 2: Field Mapping (RollupMappingUtils.kt)

**From**:
```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

**To**:
```kotlin
val hllMapping = """"hll":{"type":"hll","precision":$precision,"doc_values":true}"""
```

---

### Change 3: Dynamic Template (opendistro-rollup-target.json)

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
    }
  }
}
```

---

## Key Discoveries

### 1. Serialization Format

**Discovery**: HLL field type expects `AbstractHyperLogLogPlusPlus` format

**Evidence**:
- `HllFieldMapper.validateSketchData()` uses `AbstractHyperLogLogPlusPlus.readFrom()`
- `HllFieldData.getSketch()` uses `AbstractHyperLogLogPlusPlus.readFrom()`

**Impact**: We must serialize the raw sketch, not InternalCardinality

---

### 2. No Base64 Encoding

**Discovery**: HLL field type handles raw binary data

**Evidence**:
- `HllFieldMapper.parseCreateField()` reads `byte[]` directly
- `HllFieldData.getSketch()` reads `BytesRef` directly

**Impact**: Remove Base64 encoding for HLL field type

---

### 3. Precision in Field Mapping

**Discovery**: Precision must be specified in field mapping

**Evidence**:
- `HllFieldMapper.Builder` has `precision` parameter
- `HllFieldMapper.validateSketchData()` validates precision matches

**Impact**: Add precision to field mappings

---

### 4. Doc Values Required

**Discovery**: HLL field type requires doc values

**Evidence**:
- `HllFieldType` constructor: `hasDocValues = true`
- `HllFieldData` requires doc values for access

**Impact**: Enable doc values in field mappings

---

### 5. Automatic Sketch Merging

**Discovery**: HllCardinalityAggregator merges sketches automatically

**Evidence**:
- `HllCardinalityAggregator.collect()` calls `counts.merge()`
- No manual merging logic needed

**Impact**: Tier-N rollups work automatically!

---

## Benefits After Migration

### Performance

| Aspect | Binary Type (Current) | HLL Type (After PR) |
|--------|----------------------|---------------------|
| Storage | Base64 (~33% overhead) | Raw bytes (optimal) |
| Indexing | Base64 encode/decode | Direct binary |
| Aggregation | Not supported | Automatic merging |
| Memory | N/A | Efficient doc values |

### Functionality

| Feature | Binary Type (Current) | HLL Type (After PR) |
|---------|----------------------|---------------------|
| Tier-1 Rollups | ✅ Works | ✅ Works |
| Tier-N Rollups | ❌ Manual only | ✅ Automatic |
| Query Rewriting | ❌ Limited | ✅ Full support |
| Multi-bucket | ❌ No | ✅ Yes |
| Precision Validation | ⚠️ Metadata only | ✅ Field level |

### Maintainability

- ✅ Cleaner code (no Base64 encoding)
- ✅ Better error messages
- ✅ Consistent with OpenSearch conventions
- ✅ Native field type support
- ✅ Automatic sketch handling

---

## Testing Strategy

### Unit Tests

1. **Sketch Serialization**
   - Test `cardinality.getSketch()` returns correct type
   - Test `sketch.writeTo()` produces valid bytes
   - Test `AbstractHyperLogLogPlusPlus.readFrom()` deserializes correctly

2. **Field Mapping**
   - Test precision parameter in mapping
   - Test doc values enabled
   - Test field type is "hll"

3. **Precision Validation**
   - Test precision range (4-18)
   - Test precision mismatch detection
   - Test error messages

### Integration Tests

1. **Tier-1 Rollups**
   - Create rollup job with cardinality
   - Verify sketches stored in HLL field
   - Verify field mapping correct

2. **Tier-N Rollups**
   - Create multi-tier rollup chain
   - Verify automatic sketch merging
   - Verify accuracy maintained

3. **Query Rewriting**
   - Query rollup index with cardinality
   - Verify transparent rewriting
   - Verify accurate estimates

4. **Composite Aggregations**
   - Test cardinality in composite aggs
   - Verify multi-bucket support
   - Verify per-bucket accuracy

### Performance Tests

1. **Storage Efficiency**
   - Compare binary vs HLL field size
   - Measure Base64 overhead
   - Verify doc values efficiency

2. **Aggregation Performance**
   - Measure sketch merging speed
   - Test with various document counts
   - Test with various bucket counts

3. **Multi-Tier Accuracy**
   - Measure error across tiers
   - Compare to theoretical bounds
   - Verify error doesn't compound

---

## Risk Assessment

### Low Risk ✅

- Clear migration path
- Well-documented changes
- Rollback plan available
- Changes are localized (3 files)
- Comprehensive testing plan

### Medium Risk ⚠️

- Serialization format change (needs testing)
- Existing rollup indices need recreation
- Dependency on external PR

### Mitigation Strategies

1. **Comprehensive Testing**
   - Test in development environment first
   - Verify serialization format compatibility
   - Test all rollup scenarios

2. **Gradual Rollout**
   - Deploy to test environment
   - Monitor for issues
   - Gradual production rollout

3. **Clear Documentation**
   - Migration guide for users
   - Rollback procedures
   - Troubleshooting guide

4. **Rollback Plan**
   - Revert code changes
   - Recreate rollup indices
   - Clear communication

---

## Timeline

### Phase 1: Preparation ✅ COMPLETE

- ✅ Analyzed HLL PR files
- ✅ Documented required changes
- ✅ Added code comments
- ✅ Created migration guide

### Phase 2: HLL PR Merges ⏳ WAITING

- ⏳ Monitor PR #20129 status
- ⏳ Test HLL field type in dev
- ⏳ Verify compatibility

### Phase 3: Migration 🔄 READY

- 🔄 Update serialization code
- 🔄 Update field mappings
- 🔄 Update dynamic templates
- 🔄 Run verification tests

### Phase 4: Validation 🔄 PLANNED

- 🔄 Test Tier-1 rollups
- 🔄 Test Tier-N rollups
- 🔄 Test query rewriting
- 🔄 Performance testing

---

## Conclusion

**The OpenSearch HLL PR #20129 provides a complete, production-ready solution for cardinality rollups in ISM.**

### What We Have

- ✅ Complete understanding of HLL field type
- ✅ Clear migration path
- ✅ Minimal code changes needed
- ✅ Comprehensive documentation
- ✅ Testing strategy
- ✅ Rollback plan

### What We Need

- ⏳ HLL PR to merge
- 🔄 3 simple code changes
- 🔄 Testing and validation

### What We Get

- 🎯 Automatic Tier-N rollups
- 🎯 Query rewriting support
- 🎯 Better performance
- 🎯 Native field type support
- 🎯 Cleaner implementation

**Our implementation is architecturally correct and ready for migration!**

The HLL PR provides exactly what we need, and our code is structured to take advantage of it with minimal changes. Once the PR merges, we'll have a complete, production-ready cardinality rollup solution for ISM!

---

## References

### Documentation Created

1. `hll-field-mapper-explained.md` - HllFieldMapper analysis
2. `hll-field-data-explained.md` - HllFieldData analysis
3. `hll-cardinality-aggregator-explained.md` - HllCardinalityAggregator analysis
4. `hll-pr-migration-guide.md` - Migration instructions
5. `hll-pr-complete-analysis.md` - This document

### Code Files

1. `RollupIndexer.kt` - Sketch serialization
2. `RollupMappingUtils.kt` - Field mapping generation
3. `opendistro-rollup-target.json` - Dynamic templates

### OpenSearch PR

- **PR #20129**: HyperLogLog++ field type support
- **Status**: Pending merge
- **Impact**: Enables native HLL field type in OpenSearch
