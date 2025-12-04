# HllFieldData.java - Detailed Explanation

## Overview

`HllFieldData` is the **field data accessor** for HLL fields. It provides efficient access to HLL++ sketches stored in the index during aggregations and queries.

## Architecture

```
HllFieldData (Index-level)
    ↓
HllLeafFieldData (Segment-level)
    ↓
getSketch(docId) → AbstractHyperLogLogPlusPlus
```

---

## Class Structure

### 1. **HllFieldData** (Main Class)

**Purpose**: Index-level field data that provides access to HLL sketches across all segments.

```java
public class HllFieldData implements IndexFieldData<HllFieldData.HllLeafFieldData> {
    private final String fieldName;
    private final int precision;
    
    private HllFieldData(String fieldName, int precision) {
        this.fieldName = fieldName;
        this.precision = precision;
    }
}
```

**Key Properties**:
- `fieldName`: The field name (e.g., `"user_id.hll"`)
- `precision`: The precision of sketches in this field (4-18)

**Why This Matters for ISM Rollups**:
- When we create rollup indices with `user_id.hll` fields, this class provides access to those sketches
- The precision is stored at the field level, ensuring all sketches in the field have the same precision

---

### 2. **Builder** (Factory Pattern)

**Purpose**: Creates `HllFieldData` instances with proper configuration.

```java
public static class Builder implements IndexFieldData.Builder {
    private final String name;
    private final int precision;
    
    public Builder(String name, int precision) {
        this.name = name;
        this.precision = precision;
    }
    
    @Override
    public HllFieldData build(IndexFieldDataCache cache, CircuitBreakerService breakerService) {
        return new HllFieldData(name, precision);
    }
}
```

**How It's Used**:
1. HLL field mapper creates a builder with field name and precision
2. OpenSearch calls `build()` when field data is needed
3. Returns configured `HllFieldData` instance

**For ISM Rollups**:
- When we define field mapping with `type: "hll"` and `precision: 14`
- OpenSearch creates a builder with these parameters
- Field data is built on-demand when aggregations run

---

### 3. **getValuesSourceType()** (Type System Integration)

```java
@Override
public ValuesSourceType getValuesSourceType() {
    // HLL fields use BYTES values source type since they store binary data
    return BYTES;
}
```

**What This Means**:
- HLL fields are treated as **binary data** at the storage level
- Uses the `BYTES` values source type (same as binary fields)
- But with specialized handling for HLL sketches

**Why BYTES Type**:
- HLL sketches are serialized as byte arrays
- Stored in Lucene's `BinaryDocValues`
- Efficient binary storage without text processing overhead

**For ISM Rollups**:
- Our sketches are stored as binary data (Base64 or raw bytes)
- Compatible with the BYTES values source type
- No special storage format needed

---

### 4. **load()** (Segment-Level Access)

```java
@Override
public HllLeafFieldData load(LeafReaderContext context) {
    return new HllLeafFieldData(context.reader(), fieldName);
}
```

**Purpose**: Creates segment-level field data for efficient access.

**How OpenSearch Works**:
- Indices are divided into **segments** (immutable chunks of data)
- Each segment has its own Lucene reader
- Field data is loaded per-segment for efficiency

**Execution Flow**:
1. Aggregation starts on rollup index
2. OpenSearch iterates over segments
3. For each segment, calls `load(context)` to get segment-level field data
4. Uses segment-level field data to read sketches from documents

**For ISM Rollups**:
- When aggregating over rollup index with 1000 documents across 5 segments
- OpenSearch loads field data for each segment separately
- Efficient memory usage (only one segment in memory at a time)

---

### 5. **sortField()** (Sorting Not Supported)

```java
@Override
public SortField sortField(...) {
    throw new IllegalArgumentException("Sorting is not supported on [hll] fields");
}
```

**Why Sorting is Disabled**:
- HLL sketches are **binary data structures**, not comparable values
- Cannot meaningfully sort documents by sketch content
- Sorting by cardinality estimate would require deserializing all sketches (expensive)

**For ISM Rollups**:
- Customers cannot sort by `user_id.hll` field
- This is expected behavior (same as binary fields)
- Customers can sort by other fields (timestamp, category, etc.)

---

## HllLeafFieldData (Segment-Level)

### Purpose

**Provides access to HLL sketches within a single segment.**

```java
public static class HllLeafFieldData implements LeafFieldData {
    private final LeafReader reader;
    private final String fieldName;
    
    HllLeafFieldData(LeafReader reader, String fieldName) {
        this.reader = reader;
        this.fieldName = fieldName;
    }
}
```

**Key Components**:
- `reader`: Lucene segment reader (provides access to stored data)
- `fieldName`: Field to read sketches from (e.g., `"user_id.hll"`)

---

### getSketch() - The Critical Method

**This is the most important method for ISM rollups!**

```java
public AbstractHyperLogLogPlusPlus getSketch(int docId) throws IOException {
    // 1. Get binary doc values for this field
    BinaryDocValues docValues = reader.getBinaryDocValues(fieldName);
    
    // 2. Check if document has a value
    if (docValues != null && docValues.advanceExact(docId)) {
        // 3. Read the binary sketch data
        BytesRef sketchBytes = docValues.binaryValue();
        
        // 4. Deserialize into HLL++ sketch
        return AbstractHyperLogLogPlusPlus.readFrom(
            new BytesArray(sketchBytes.bytes, sketchBytes.offset, sketchBytes.length).streamInput(),
            BigArrays.NON_RECYCLING_INSTANCE
        );
    }
    
    // 5. Return null if no value
    return null;
}
```

**Step-by-Step Breakdown**:

#### Step 1: Get Binary Doc Values
```java
BinaryDocValues docValues = reader.getBinaryDocValues(fieldName);
```

**What This Does**:
- Accesses the binary doc values for the HLL field
- `BinaryDocValues` is Lucene's API for reading binary field data
- Returns null if field doesn't exist in this segment

**For ISM Rollups**:
- Reads from `user_id.hll` field in rollup index
- Accesses the stored sketch bytes

#### Step 2: Check Document Has Value
```java
if (docValues != null && docValues.advanceExact(docId))
```

**What This Does**:
- `advanceExact(docId)`: Positions reader at specific document
- Returns `true` if document has a value for this field
- Returns `false` if document doesn't have this field (sparse data)

**For ISM Rollups**:
- Handles cases where some rollup documents might not have cardinality metrics
- Gracefully skips documents without sketches

#### Step 3: Read Binary Sketch Data
```java
BytesRef sketchBytes = docValues.binaryValue();
```

**What This Does**:
- Reads the raw bytes of the sketch from Lucene
- `BytesRef` is Lucene's efficient byte array wrapper
- Contains: `bytes` (array), `offset` (start position), `length` (size)

**For ISM Rollups**:
- Reads the sketch bytes we stored during rollup indexing
- Could be Base64-decoded bytes or raw sketch bytes

#### Step 4: Deserialize into HLL++ Sketch
```java
return AbstractHyperLogLogPlusPlus.readFrom(
    new BytesArray(sketchBytes.bytes, sketchBytes.offset, sketchBytes.length).streamInput(),
    BigArrays.NON_RECYCLING_INSTANCE
);
```

**What This Does**:
- Creates a `BytesArray` wrapper around the sketch bytes
- Calls `AbstractHyperLogLogPlusPlus.readFrom()` to deserialize
- Returns a fully functional HLL++ sketch object

**Critical Detail**: Uses `AbstractHyperLogLogPlusPlus.readFrom()`
- This is OpenSearch's standard HLL++ deserialization method
- Expects sketches serialized with `AbstractHyperLogLogPlusPlus.writeTo()`
- **This tells us the serialization format we need to use!**

#### Step 5: Return Null if No Value
```java
return null;
```

**What This Does**:
- Returns null if document doesn't have a sketch
- Aggregator handles null values gracefully

---

## How This Works with ISM Rollups

### Tier-1 Rollups (Raw Data → Rollup Index)

**Our Code** (RollupIndexer.kt):
```kotlin
is InternalCardinality -> {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)  // Serializes InternalCardinality
    val bytes = output.bytes().toBytesRef().bytes
    aggResults[it.name] = Base64.getEncoder().encodeToString(bytes)
}
```

**Question**: Does this work with `HllFieldData.getSketch()`?

**Answer**: **It depends on what `InternalCardinality.writeTo()` serializes!**

#### Scenario A: InternalCardinality.writeTo() Serializes the Sketch

If `InternalCardinality.writeTo()` calls `sketch.writeTo()` internally:
```java
// InternalCardinality.writeTo()
public void writeTo(StreamOutput out) throws IOException {
    out.writeString(name);
    sketch.writeTo(out);  // Writes AbstractHyperLogLogPlusPlus
    // ... other metadata
}
```

**Then**: ✅ Our serialization works, but we need to deserialize `InternalCardinality`, not just the sketch.

#### Scenario B: We Need to Serialize Just the Sketch

If `HllFieldData.getSketch()` expects **only the sketch bytes** (not `InternalCardinality`):
```kotlin
// Updated serialization
is InternalCardinality -> {
    val sketch = cardinality.getSketch()  // Extract raw sketch
    val output = BytesStreamOutput()
    sketch.writeTo(output)  // Serialize just the sketch
    val bytes = output.bytes().toBytesRef().bytes
    aggResults[it.name] = bytes  // No Base64 encoding needed
}
```

**Then**: ✅ We need to update our serialization to match.

---

### Tier-N Rollups (Rollup → Higher-Tier Rollup)

**How It Works**:

1. **Rollup Job Aggregates Over Tier-1**:
   ```json
   {
     "aggs": {
       "unique_users": {
         "cardinality": {"field": "user_id.hll"}
       }
     }
   }
   ```

2. **HllCardinalityAggregator Uses HllFieldData**:
   ```java
   // For each document in Tier-1 rollup:
   HllLeafFieldData leafData = hllFieldData.load(context);
   AbstractHyperLogLogPlusPlus sketch = leafData.getSketch(docId);
   
   // Merge sketches
   mergedSketch.merge(sketch);
   ```

3. **getSketch() Deserializes Our Stored Sketches**:
   - Reads bytes from `user_id.hll` field
   - Calls `AbstractHyperLogLogPlusPlus.readFrom()`
   - Returns sketch object for merging

4. **Merged Sketch Stored in Tier-2**:
   - Same serialization as Tier-1
   - Process repeats for higher tiers

**Result**: ✅ **Tier-N rollups work automatically!**

---

## Key Insights

### 1. **Serialization Format is Critical**

The `getSketch()` method uses:
```java
AbstractHyperLogLogPlusPlus.readFrom(streamInput, bigArrays)
```

**This means**:
- Sketches must be serialized with `AbstractHyperLogLogPlusPlus.writeTo()`
- **Not** `InternalCardinality.writeTo()` (unless it delegates to sketch.writeTo())
- We need to verify our serialization format matches

### 2. **No Base64 Encoding Needed**

The method reads raw bytes:
```java
BytesRef sketchBytes = docValues.binaryValue();
```

**This means**:
- HLL field type stores **raw bytes**, not Base64 strings
- We should remove Base64 encoding when using HLL field type
- Binary field type needed Base64 for JSON compatibility, HLL doesn't

### 3. **Precision is Field-Level, Not Sketch-Level**

```java
private final int precision;
```

**This means**:
- Precision is stored in field mapping, not in each sketch
- All sketches in a field must have the same precision
- OpenSearch enforces precision consistency at the field level

### 4. **Efficient Segment-Level Access**

```java
public HllLeafFieldData load(LeafReaderContext context)
```

**This means**:
- Sketches are accessed per-segment for efficiency
- Only one segment's sketches in memory at a time
- Scales to large rollup indices with many documents

---

## What We Need to Change

### Update 1: Verify Serialization Format

**Test what format is expected**:
```kotlin
// Option A: Serialize InternalCardinality
val output = BytesStreamOutput()
cardinality.writeTo(output)
val bytes = output.bytes().toBytesRef().bytes

// Option B: Serialize just the sketch
val sketch = cardinality.getSketch()
val output = BytesStreamOutput()
sketch.writeTo(output)
val bytes = output.bytes().toBytesRef().bytes
```

**Determine which one works with `AbstractHyperLogLogPlusPlus.readFrom()`**

### Update 2: Remove Base64 Encoding

**Current** (for binary type):
```kotlin
aggResults[it.name] = Base64.getEncoder().encodeToString(bytes)
```

**Updated** (for HLL type):
```kotlin
aggResults[it.name] = bytes  // Raw bytes, no encoding
```

### Update 3: Change Field Type

**Current**:
```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

**Updated**:
```kotlin
val hllMapping = """"hll":{"type":"hll","precision":$precision,"doc_values":false}"""
```

---

## Summary

**HllFieldData** is the bridge between stored sketch bytes and HLL++ sketch objects:

1. **Storage**: Sketches stored as binary doc values in Lucene
2. **Access**: `getSketch(docId)` reads and deserializes sketches
3. **Format**: Uses `AbstractHyperLogLogPlusPlus.readFrom()` for deserialization
4. **Efficiency**: Segment-level access for scalability
5. **Integration**: Works seamlessly with `HllCardinalityAggregator`

**For ISM Rollups**:
- ✅ Provides efficient access to stored sketches
- ✅ Enables automatic sketch merging in Tier-N rollups
- ✅ Scales to large rollup indices
- ⚠️ We need to verify our serialization format matches
- ⚠️ We need to remove Base64 encoding for HLL field type

This file confirms that the HLL PR provides **complete infrastructure** for our cardinality rollup use case!
