# Sketch Serialization - Important Note

## Current Implementation Status

### What We're Doing Now

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)  // Serializing full InternalCardinality
    return output.bytes().toBytesRef().bytes
}
```

**This serializes the entire `InternalCardinality` object, not just the sketch.**

### Why This Might Be Wrong

Based on HLL PR analysis, `HllFieldMapper.validateSketchData()` expects:
```java
AbstractHyperLogLogPlusPlus sketch = AbstractHyperLogLogPlusPlus.readFrom(in, bigArrays);
```

This suggests it expects **just the sketch bytes**, not `InternalCardinality` bytes.

### The Problem

We tried to serialize just the sketch:
```kotlin
cardinality.counts.writeTo(output)  // Compilation error!
```

But `counts.writeTo()` has a different signature that requires additional parameters (likely a bucket ordinal).

## Possible Solutions

### Option 1: Find the Correct API

The `AbstractHyperLogLogPlusPlus` or `HyperLogLogPlusPlus` class likely has a method to serialize a single sketch. We need to find it.

**Possible methods to try**:
- `cardinality.counts.writeTo(output, 0)` - with bucket ordinal 0
- `cardinality.counts.clone(0, bigArrays)` - clone bucket 0, then serialize
- Some other method we haven't discovered yet

### Option 2: Test Current Implementation

The current implementation (serializing `InternalCardinality`) might actually work if:
- `InternalCardinality.writeTo()` writes the sketch in a compatible format
- `HllFieldMapper` can handle `InternalCardinality` format

**We should test this first!**

### Option 3: Wait for HLL PR Documentation

Once the HLL PR is fully merged and documented, there might be:
- Utility methods for sketch serialization
- Clear examples of how to store sketches
- Better API documentation

## Recommended Testing Approach

### Test 1: Try Current Implementation

1. Run rollup job with current code
2. Check if sketches are stored
3. Try to query the rollup index
4. See what error (if any) we get

**If it works**: Great! The `InternalCardinality` format is compatible.

**If it fails**: We'll see an error from `HllFieldMapper.validateSketchData()` that will tell us what's wrong.

### Test 2: Inspect InternalCardinality Serialization

```kotlin
// Debug code to see what InternalCardinality.writeTo() produces
val output = BytesStreamOutput()
cardinality.writeTo(output)
val bytes = output.bytes().toBytesRef().bytes

// Try to deserialize as AbstractHyperLogLogPlusPlus
val input = BytesArray(bytes).streamInput()
try {
    val sketch = AbstractHyperLogLogPlusPlus.readFrom(input, BigArrays.NON_RECYCLING_INSTANCE)
    println("Success! Sketch precision: ${sketch.precision()}")
} catch (e: Exception) {
    println("Failed: ${e.message}")
    // This tells us the format is incompatible
}
```

### Test 3: Try Alternative Serialization

If Test 1 fails, try these alternatives:

**Alternative A**: Serialize with bucket ordinal
```kotlin
val output = BytesStreamOutput()
cardinality.counts.writeTo(output, 0)  // Bucket 0
return output.bytes().toBytesRef().bytes
```

**Alternative B**: Clone and serialize
```kotlin
val sketch = cardinality.counts.clone(0, BigArrays.NON_RECYCLING_INSTANCE)
val output = BytesStreamOutput()
sketch.writeTo(output)
sketch.close()
return output.bytes().toBytesRef().bytes
```

**Alternative C**: Use reflection or Java interop
```kotlin
// Access the sketch directly using Java reflection if needed
val countsField = cardinality.javaClass.getDeclaredField("counts")
countsField.isAccessible = true
val sketch = countsField.get(cardinality) as AbstractHyperLogLogPlusPlus
// Then serialize...
```

## What to Look For in Testing

### Success Indicators

1. **Rollup job completes** without errors
2. **Field mapping shows** `type: "hll"`
3. **Documents contain** `user_id.hll` field
4. **Query rewriting works** - can query with `cardinality(field="user_id")`
5. **Tier-2 rollups work** - automatic sketch merging

### Failure Indicators

1. **Error during indexing**: "Invalid HLL++ sketch data"
2. **Error during validation**: "HLL++ sketch precision mismatch"
3. **Error during query**: "Cannot aggregate on field"
4. **Deserialization error**: Format incompatibility

## Next Steps

1. **Test current implementation** with OpenSearch snapshot
2. **Check error messages** if it fails
3. **Inspect HLL PR code** for correct serialization method
4. **Update implementation** based on findings
5. **Document the correct approach**

## Important Note

**The current implementation compiles and might work!**

We won't know for sure until we test with the actual OpenSearch snapshot that includes the HLL PR. The error messages from testing will guide us to the correct solution.

**Don't spend too much time guessing - test first, then fix based on actual errors!**
