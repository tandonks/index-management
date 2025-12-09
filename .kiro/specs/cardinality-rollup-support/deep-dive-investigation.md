# Deep Dive: AbstractHyperLogLogPlusPlus Serialization

## The Core Issue

We're calling `sketch.writeTo(0L, output)` but getting 0 cardinality when reading back.

## Key Questions

### 1. What does `writeTo(long bucketOrd, StreamOutput out)` actually write?

This method is designed for **multi-bucket aggregations** where a single `AbstractHyperLogLogPlusPlus` instance manages sketches for multiple buckets.

**Example**: Terms aggregation with cardinality sub-aggregation
```json
{
  "aggs": {
    "by_country": {
      "terms": { "field": "country" },
      "aggs": {
        "unique_users": { "cardinality": { "field": "user_id" } }
      }
    }
  }
}
```

In this case:
- Bucket 0: USA → sketch for USA users
- Bucket 1: UK → sketch for UK users  
- Bucket 2: Canada → sketch for Canada users

The `writeTo(bucketOrd, output)` writes the sketch for a **specific bucket**.

### 2. What does the HLL field type expect?

From the HLL PR analysis:
```java
// HllFieldData.getSketch()
AbstractHyperLogLogPlusPlus.readFrom(
    streamInput,
    BigArrays.NON_RECYCLING_INSTANCE
)
```

This expects the **raw sketch format** from `AbstractHyperLogLogPlusPlus.readFrom()`.

### 3. Is there a mismatch?

**YES!** Here's the problem:

1. `writeTo(bucketOrd, output)` writes sketch data for a specific bucket
2. But it might include bucket-specific metadata or format
3. `readFrom(input, bigArrays)` expects a different format

## The Real Question

**How does OpenSearch serialize a single sketch for storage?**

Let's look at how `InternalCardinality` serializes itself:

```java
// InternalCardinality.java
@Override
public void writeTo(StreamOutput out) throws IOException {
    out.writeString(name);
    counts.writeTo(out);  // ⚠️ This is the key!
}
```

So `InternalCardinality.writeTo()` calls `counts.writeTo(out)` where `counts` is the `AbstractHyperLogLogPlusPlus` instance.

**But wait!** This is `writeTo(StreamOutput)` not `writeTo(long, StreamOutput)`!

## The Solution

There might be **two different `writeTo` methods**:

1. `writeTo(StreamOutput out)` - Serializes the entire sketch collection
2. `writeTo(long bucketOrd, StreamOutput out)` - Serializes a specific bucket's sketch

We need to use the **first one** for single-sketch serialization!

## Investigation Plan

### Option 1: Use `writeTo(StreamOutput)` directly

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Try the no-arg writeTo method
    sketch.writeTo(output)  // ⚠️ Does this method exist?
    output.bytes().toBytesRef().bytes
}
```

### Option 2: Check if there's a single-sketch extraction method

The `AbstractHyperLogLogPlusPlus` class might have a method to extract a single sketch:

```kotlin
// Hypothetical methods to investigate:
sketch.getSketch(0L)  // Get sketch for bucket 0?
sketch.toBytes()      // Direct byte conversion?
sketch.serialize()    // Explicit serialization?
```

### Option 3: Use InternalCardinality's serialization

Since `InternalCardinality.writeTo()` already handles sketch serialization:

```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)  // Serialize the whole thing
    output.bytes().toBytesRef().bytes
}
```

**But**: This includes the aggregation name and other metadata. The HLL field type might not expect this.

## The Critical Test

We need to understand what format `AbstractHyperLogLogPlusPlus.readFrom()` expects.

### Test Plan

1. **Serialize a sketch** using our current method
2. **Try to deserialize it** using `AbstractHyperLogLogPlusPlus.readFrom()`
3. **Check if the cardinality matches**

```kotlin
// Test code
val output = BytesStreamOutput()
sketch.writeTo(0L, output)
val bytes = output.bytes()

// Try to read it back
val input = bytes.streamInput()
val deserializedSketch = AbstractHyperLogLogPlusPlus.readFrom(input, BigArrays.NON_RECYCLING_INSTANCE)

// Check cardinality
logger.info("Original cardinality: ${sketch.cardinality(0L)}")
logger.info("Deserialized cardinality: ${deserializedSketch.cardinality(0L)}")
```

If the deserialized cardinality is 0, we know the format is wrong!

## Hypothesis: The Bucket Ordinal Problem

Here's what I think is happening:

1. `AbstractHyperLogLogPlusPlus` manages multiple buckets internally
2. `writeTo(bucketOrd, output)` writes data for ONE bucket
3. `readFrom(input, bigArrays)` expects data for ALL buckets (or a different format)

**Evidence**: The method signature difference
- `writeTo(long bucketOrd, StreamOutput out)` - bucket-specific
- `readFrom(StreamInput in, BigArrays bigArrays)` - no bucket parameter

This suggests `readFrom` expects a different format than what `writeTo(bucketOrd, ...)` produces!

## Next Steps

1. **Check OpenSearch source** for `AbstractHyperLogLogPlusPlus`:
   - Look for all `writeTo` method signatures
   - Check if there's a `writeTo(StreamOutput)` without bucket ordinal
   - Understand the serialization format

2. **Check HllFieldMapper** implementation:
   - How does it serialize sketches during indexing?
   - What method does it use?

3. **Test serialization/deserialization**:
   - Verify our serialization can be deserialized correctly
   - Check if the cardinality is preserved

## Most Likely Fix

I believe we need to find and use a different serialization method that produces the format expected by `readFrom()`.

Possible solutions:
1. Use `writeTo(StreamOutput)` if it exists
2. Use a different method to extract raw sketch bytes
3. Manually construct the correct format based on HLL PR implementation
