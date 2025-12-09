# Potential Fixes for Cardinality Serialization Issue

## The Core Problem

`AbstractHyperLogLogPlusPlus.writeTo(long bucketOrd, StreamOutput out)` is designed for **multi-bucket aggregations**, but we're trying to use it for **single-sketch storage**.

## Understanding the Bucket Ordinal

### What is a Bucket Ordinal?

In OpenSearch aggregations, a single `AbstractHyperLogLogPlusPlus` instance can manage sketches for **multiple buckets**.

**Example**: Terms aggregation with cardinality sub-aggregation
```json
{
  "aggs": {
    "by_status": {
      "terms": { "field": "status" },
      "aggs": {
        "unique_users": { "cardinality": { "field": "user_id" } }
      }
    }
  }
}
```

Results:
- Bucket 0 (status="active"): 1000 unique users
- Bucket 1 (status="inactive"): 500 unique users
- Bucket 2 (status="pending"): 200 unique users

The `AbstractHyperLogLogPlusPlus` instance stores **all three sketches** internally, indexed by bucket ordinal.

### The Method Signatures

```java
// Write a specific bucket's sketch
void writeTo(long bucketOrd, StreamOutput out)

// Read a sketch (but from what format?)
static AbstractHyperLogLogPlusPlus readFrom(StreamInput in, BigArrays bigArrays)
```

**Notice**: `readFrom()` doesn't take a bucket ordinal! This suggests it expects a **different format** than what `writeTo(bucketOrd, ...)` produces.

## Potential Fix #1: Find the Correct Serialization Method

### Investigation

Check if `AbstractHyperLogLogPlusPlus` has other serialization methods:

```java
// Possible methods to look for:
void writeTo(StreamOutput out)  // Serialize entire sketch collection?
byte[] toBytes()                 // Direct byte conversion?
void serialize(StreamOutput out) // Explicit serialization?
```

### Implementation

If we find `writeTo(StreamOutput)`:
```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Use the no-bucket-ordinal version
    sketch.writeTo(output)
    output.bytes().toBytesRef().bytes
}
```

## Potential Fix #2: Extract Single-Bucket Sketch

### Theory

Maybe we need to extract the sketch for bucket 0 as a **new, single-bucket sketch** instance.

### Investigation

Check if `AbstractHyperLogLogPlusPlus` has methods like:
```java
AbstractHyperLogLogPlusPlus extractBucket(long bucketOrd)
AbstractHyperLogLogPlusPlus clone(long bucketOrd)
```

### Implementation

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Extract bucket 0 as a new sketch instance
    val singleBucketSketch = sketch.extractBucket(0L)
    
    // Serialize the single-bucket sketch
    singleBucketSketch.writeTo(output)
    output.bytes().toBytesRef().bytes
}
```

## Potential Fix #3: Use HLL Field Mapper's Approach

### Theory

The HLL PR's `HllFieldMapper` must have code to serialize sketches during indexing. We should use the same approach.

### Investigation

Look at `HllFieldMapper.parseCreateField()` to see how it serializes sketches:

```java
// From HLL PR (hypothetical)
public IndexableField parseCreateField(ParseContext context) {
    byte[] sketchBytes = ...; // How does it get the bytes?
    return new BinaryDocValuesField(name(), new BytesRef(sketchBytes));
}
```

### Implementation

Match whatever the HLL field mapper does.

## Potential Fix #4: Create a New Sketch Instance

### Theory

Maybe we need to create a **new** `AbstractHyperLogLogPlusPlus` instance with just the data from bucket 0.

### Investigation

Check the `AbstractHyperLogLogPlusPlus` constructor:
```java
// Possible constructor
AbstractHyperLogLogPlusPlus(int precision, BigArrays bigArrays)
```

### Implementation

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val multiSketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Create a new single-bucket sketch
    val precision = multiSketch.precision()
    val singleSketch = AbstractHyperLogLogPlusPlus(precision, BigArrays.NON_RECYCLING_INSTANCE)
    
    // Copy data from bucket 0 to the new sketch
    // (Need to find the right method for this)
    singleSketch.merge(0L, multiSketch, 0L)
    
    // Serialize the new sketch
    singleSketch.writeTo(output)
    output.bytes().toBytesRef().bytes
}
```

## Potential Fix #5: Manual Register Extraction

### Theory

HLL++ sketches are essentially arrays of registers. Maybe we need to manually extract and serialize the registers.

### Investigation

Check if `AbstractHyperLogLogPlusPlus` exposes register access:
```java
// Possible methods
int[] getRegisters(long bucketOrd)
byte[] getRegisterBytes(long bucketOrd)
```

### Implementation

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Manually extract registers for bucket 0
    val registers = sketch.getRegisters(0L)
    val precision = sketch.precision()
    
    // Serialize in the format expected by readFrom()
    output.writeInt(precision)
    output.writeByteArray(registers)
    
    output.bytes().toBytesRef().bytes
}
```

## Potential Fix #6: Use InternalCardinality Serialization

### Theory

Maybe we should serialize the entire `InternalCardinality` object, not just the sketch.

### Pros
- `InternalCardinality.writeTo()` is a well-defined serialization method
- It includes all necessary metadata

### Cons
- Includes the aggregation name (extra data)
- HLL field type might not expect this format

### Implementation

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)
    output.bytes().toBytesRef().bytes
}
```

### Deserialization

The HLL field type would need to:
```java
// Read the name first
String name = in.readString();
// Then read the sketch
AbstractHyperLogLogPlusPlus sketch = AbstractHyperLogLogPlusPlus.readFrom(in, bigArrays);
```

## Most Likely Fix

Based on my analysis, I believe **Fix #1** is most likely correct:

### Reasoning

1. `readFrom()` doesn't take a bucket ordinal → expects a different format
2. There's probably a `writeTo(StreamOutput)` method for single-sketch serialization
3. This is the simplest and most direct approach

### Next Steps

1. **Check OpenSearch source** for `AbstractHyperLogLogPlusPlus` class
2. **Look for all `writeTo` methods**
3. **Find the one that matches `readFrom()`'s expected format**

## Testing Strategy

For each potential fix:

1. **Implement the fix**
2. **Run the deserialization test** (already added to code)
3. **Check if cardinalities match**
4. **If yes**: Test with Tier-1 and Tier-2 rollups
5. **If no**: Try the next fix

## Expected Timeline

- **Fix #1**: 30 minutes to research + 15 minutes to implement
- **Fix #2-6**: 1-2 hours each if Fix #1 doesn't work

## Success Criteria

The fix is successful when:
1. Deserialization test shows matching cardinalities
2. Tier-1 rollup stores sketches correctly
3. Tier-2 rollup reads sketches correctly
4. Cardinality aggregations return accurate values
5. Multi-tier rollups work correctly

## Rollback Plan

If none of the fixes work:
1. Revert to Base64 encoding with binary field type
2. Implement manual sketch merging for Tier-N rollups
3. Wait for HLL PR to provide more documentation
