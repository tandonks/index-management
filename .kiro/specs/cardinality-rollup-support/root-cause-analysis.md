# Root Cause Analysis: Cardinality Aggregation Returning 0

## The Problem

When querying a Tier-2 rollup index with cardinality aggregation:
```json
{"value.hll":{"value":0}}
```

The cardinality is returning 0 instead of the expected value.

## Current Implementation

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Serialize just the sketch
    sketch.writeTo(0L, output)  // ⚠️ POTENTIAL ISSUE HERE
    output.bytes().toBytesRef().bytes
}
```

## Hypothesis 1: Bucket Ordinal Issue

The `writeTo(long bucketOrd, StreamOutput out)` method takes a bucket ordinal parameter.

**Question**: Are we using the correct bucket ordinal?

### Evidence
- We're using `0L` as the bucket ordinal
- This assumes single-bucket aggregation
- But what if the sketch is stored differently?

### Investigation Needed
1. Check if `AbstractHyperLogLogPlusPlus` stores sketches per-bucket
2. Verify if bucket ordinal 0 is correct for our use case
3. Check if we need to extract the sketch differently

## Hypothesis 2: Serialization Format Mismatch

The HLL field type expects a specific format from `AbstractHyperLogLogPlusPlus.readFrom()`.

**Question**: Does our serialization match what `readFrom()` expects?

### Evidence from HLL PR
```java
// HllFieldData.getSketch()
AbstractHyperLogLogPlusPlus.readFrom(
    new BytesArray(sketchBytes.bytes, sketchBytes.offset, sketchBytes.length).streamInput(),
    BigArrays.NON_RECYCLING_INSTANCE
)
```

### Investigation Needed
1. Check if `writeTo(bucketOrd, output)` produces the same format as `readFrom()` expects
2. Verify if we need to use a different serialization method
3. Check if there's a `writeTo(StreamOutput)` method without bucket ordinal

## Hypothesis 3: Empty Sketch

The sketch might be empty or not properly populated.

**Question**: Is the sketch actually populated with data?

### Evidence Needed
- Log the sketch's cardinality value before serialization
- Log the sketch's size/state
- Verify the sketch contains actual data

### Investigation Needed
1. Add logging to check `sketch.cardinality(0L)` before serialization
2. Verify the sketch is not empty
3. Check if the sketch was properly merged during Tier-1 rollup

## Hypothesis 4: Field Mapping Issue

The HLL field type might not be reading the data correctly.

**Question**: Is the field mapping configured correctly?

### Current Mapping
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

### Investigation Needed
1. Verify the field is actually mapped as `hll` type
2. Check if doc_values are enabled
3. Verify precision parameter is set correctly

## Hypothesis 5: AbstractHyperLogLogPlusPlus Internal State

The `AbstractHyperLogLogPlusPlus` class might have internal state that affects serialization.

**Question**: Does the sketch need to be in a specific state before serialization?

### Investigation Needed
1. Check if there's a `prepareForSerialization()` or similar method
2. Verify if the sketch needs to be "finalized" before serialization
3. Check if there are different sketch implementations (sparse vs dense)

## Next Steps

### Immediate Actions
1. **Add comprehensive logging** to understand what's happening:
   ```kotlin
   logger.info("Sketch cardinality before serialization: ${sketch.cardinality(0L)}")
   logger.info("Sketch class: ${sketch.javaClass.name}")
   logger.info("Serialized bytes length: ${bytes.size}")
   ```

2. **Verify the sketch is populated**:
   - Check if `cardinality.value()` returns the expected value
   - Verify the sketch is not empty

3. **Test serialization/deserialization**:
   - Serialize the sketch
   - Deserialize it back
   - Verify the cardinality matches

### Research Actions
1. **Check OpenSearch source code** for `AbstractHyperLogLogPlusPlus`:
   - Look for `writeTo` method signatures
   - Check if there's a method without bucket ordinal
   - Understand the bucket ordinal parameter

2. **Check HLL PR implementation**:
   - How does `HllFieldMapper` serialize sketches during indexing?
   - What format does it expect?

3. **Check InternalCardinality implementation**:
   - How does it serialize sketches?
   - Is there a method to get the raw sketch bytes?

## Most Likely Root Cause

Based on the analysis, I believe the issue is **Hypothesis 2: Serialization Format Mismatch**.

### Reasoning
1. The `writeTo(bucketOrd, output)` method is designed for multi-bucket aggregations
2. It might include additional metadata (bucket ordinal, etc.) that the HLL field type doesn't expect
3. The HLL field type expects raw sketch data from `AbstractHyperLogLogPlusPlus.readFrom()`

### Proposed Fix
Instead of using `sketch.writeTo(0L, output)`, we should look for a method that serializes just the sketch data without bucket metadata.

Possible alternatives:
1. Check if there's a `writeTo(StreamOutput)` method without bucket ordinal
2. Check if `InternalCardinality` has a method to get raw sketch bytes
3. Check how the HLL field mapper serializes sketches during indexing

## Action Plan

1. **First**: Add logging to verify the sketch is populated
2. **Second**: Research the correct serialization method
3. **Third**: Test the fix with a simple Tier-1 rollup
4. **Fourth**: Verify Tier-2 rollup works correctly
