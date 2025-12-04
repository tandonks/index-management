# HllFieldMapper.java - Detailed Explanation

## Overview

`HllFieldMapper` is the **field mapper** that registers and handles the `hll` field type in OpenSearch. It's responsible for:
1. Registering `type: "hll"` as a valid field type
2. Parsing and validating HLL sketches during indexing
3. Storing sketches as binary doc values
4. Providing field data access for aggregations

**This is the foundation that makes everything else work!**

---

## Class Structure

```
HllFieldMapper (Main Mapper)
    ├── Builder (Field Configuration)
    ├── HllFieldType (Type Definition)
    └── parseCreateField() (Indexing Logic)
```

---

## 1. Field Type Registration

### CONTENT_TYPE Constant

```java
public static final String CONTENT_TYPE = "hll";
```

**What This Does**:
- Registers `"hll"` as a valid field type in OpenSearch
- Allows field mappings like: `{"type": "hll", "precision": 14}`

**For ISM Rollups**:
- This is what makes our field mapping work:
  ```json
  {
    "user_id": {
      "properties": {
        "hll": {"type": "hll", "precision": 14}
      }
    }
  }
  ```

### TypeParser

```java
public static final TypeParser PARSER = new TypeParser((n, c) -> {
    // HLL fields are intended for internal use by OpenSearch and plugins only.
    return new Builder(n);
});
```

**What This Does**:
- Parses field mapping definitions
- Creates a `Builder` to configure the field
- Comment indicates it's designed for **internal use by plugins** (like ISM!)

**For ISM Rollups**:
- When we create rollup index with HLL fields, this parser processes the mapping
- Validates configuration and creates the field mapper

---

## 2. Builder (Field Configuration)

### Precision Parameter

```java
private final Parameter<Integer> precision = Parameter.intParam(
    "precision",
    false,  // Not required (has default)
    m -> toType(m).precision,
    HyperLogLogPlusPlus.DEFAULT_PRECISION  // Default value
).setValidator(Builder::validatePrecision);
```

**What This Does**:
- Defines `precision` as a field mapping parameter
- Default: `HyperLogLogPlusPlus.DEFAULT_PRECISION` (likely 12)
- Validates precision is in valid range (4-18)

**Field Mapping Example**:
```json
{
  "user_id": {
    "properties": {
      "hll": {
        "type": "hll",
        "precision": 14  // This parameter
      }
    }
  }
}
```

**For ISM Rollups**:
- ✅ We can specify precision in field mapping
- ✅ Precision is validated automatically
- ✅ Default precision if not specified

### Precision Validation

```java
private static void validatePrecision(int precision) {
    if (precision < AbstractHyperLogLog.MIN_PRECISION || 
        precision > AbstractHyperLogLog.MAX_PRECISION) {
        throw new IllegalArgumentException(
            "precision must be between " + 
            AbstractHyperLogLog.MIN_PRECISION + 
            " and " + 
            AbstractHyperLogLog.MAX_PRECISION + 
            ", got: " + precision
        );
    }
}
```

**What This Does**:
- Validates precision is between MIN_PRECISION (4) and MAX_PRECISION (18)
- Throws clear error if invalid

**For ISM Rollups**:
- ✅ Our precision validation in `CardinalityValidation.kt` aligns with this
- ✅ OpenSearch enforces the same range we validate
- ✅ Consistent error messages

---

## 3. HllFieldType (Type Definition)

### Field Type Properties

```java
public static final class HllFieldType extends MappedFieldType {
    private final int precision;
    
    public HllFieldType(String name, int precision, Map<String, String> meta) {
        super(
            name,
            false,  // indexed = false (not searchable)
            false,  // stored = false (not in _source)
            true,   // hasDocValues = true (for aggregations)
            TextSearchInfo.NONE,
            meta
        );
        this.precision = precision;
    }
}
```

**Field Properties Explained**:

#### indexed = false
- HLL fields are **not searchable** with term queries
- Cannot use in `match`, `term`, or other search queries
- Only usable in aggregations

#### stored = false
- Sketch data is **not stored in _source**
- Only stored in doc values
- Reduces storage overhead

#### hasDocValues = true
- Enables **doc values** for aggregations
- Required for cardinality aggregations to work
- Allows efficient sketch access

**For ISM Rollups**:
- ✅ Sketches are stored efficiently (doc values only)
- ✅ Aggregations work (doc values enabled)
- ✅ Cannot accidentally query sketch fields (indexed = false)

### fielddataBuilder()

```java
@Override
public IndexFieldData.Builder fielddataBuilder(
    String fullyQualifiedIndexName, 
    Supplier<SearchLookup> searchLookup
) {
    failIfNoDocValues();
    return new HllFieldData.Builder(name(), precision);
}
```

**What This Does**:
- Returns the `HllFieldData.Builder` we saw in the previous file
- Passes field name and precision to the builder
- Validates doc values are enabled

**For ISM Rollups**:
- This connects the field mapper to the field data accessor
- When aggregations run, this creates the `HllFieldData` instance
- Precision flows from field mapping → field data → aggregator

### termQuery() - Not Supported

```java
@Override
public Query termQuery(Object value, QueryShardContext context) {
    throw new IllegalArgumentException("Term queries are not supported on [hll] fields");
}
```

**Why Term Queries are Disabled**:
- HLL fields store binary sketch data, not searchable values
- Cannot meaningfully search for specific sketch values
- Only aggregations are supported

**For ISM Rollups**:
- ✅ Prevents customers from accidentally querying sketch fields
- ✅ Clear error message if they try

---

## 4. parseCreateField() - The Indexing Logic

**This is the most critical method for ISM rollups!**

```java
@Override
protected void parseCreateField(ParseContext context) throws IOException {
    // 1. Parse binary HLL++ sketch data
    byte[] value = context.parseExternalValue(byte[].class);
    if (value == null) {
        if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
            return;
        } else {
            value = context.parser().binaryValue();
        }
    }
    
    if (value == null) {
        return;
    }
    
    // 2. Validate the sketch data
    BytesRef sketchBytes = new BytesRef(value);
    validateSketchData(sketchBytes);
    
    // 3. Store as binary doc value
    context.doc().add(new BinaryDocValuesField(fieldType().name(), sketchBytes));
}
```

### Step-by-Step Breakdown

#### Step 1: Parse Binary Sketch Data

```java
byte[] value = context.parseExternalValue(byte[].class);
if (value == null) {
    value = context.parser().binaryValue();
}
```

**What This Does**:
- Reads binary data from the document being indexed
- First tries `parseExternalValue()` (for programmatic indexing)
- Falls back to `parser().binaryValue()` (for JSON/REST API)

**For ISM Rollups**:
- When we index rollup documents with sketches, this reads our sketch bytes
- Supports both programmatic indexing (our use case) and REST API

**Critical Question**: What format does `binaryValue()` expect?

**Answer**: It expects **raw bytes** or **Base64-encoded strings** in JSON.

**JSON Example**:
```json
{
  "user_id": {
    "hll": "EHVzZXJfaWQu..."  // Base64-encoded sketch bytes
  }
}
```

**Programmatic Example** (our use case):
```kotlin
val doc = mapOf(
    "user_id" to mapOf(
        "hll" to sketchBytes  // Raw byte array
    )
)
```

#### Step 2: Validate Sketch Data

```java
BytesRef sketchBytes = new BytesRef(value);
validateSketchData(sketchBytes);
```

**What This Does**:
- Wraps bytes in `BytesRef` (Lucene's byte array wrapper)
- Calls `validateSketchData()` to ensure it's a valid HLL++ sketch

**For ISM Rollups**:
- ✅ Validates our serialized sketches during indexing
- ✅ Catches serialization errors early
- ✅ Ensures data integrity

#### Step 3: Store as Binary Doc Value

```java
context.doc().add(new BinaryDocValuesField(fieldType().name(), sketchBytes));
```

**What This Does**:
- Creates a `BinaryDocValuesField` with the sketch bytes
- Adds it to the Lucene document
- Stores in doc values (not _source)

**For ISM Rollups**:
- This is how our sketches are physically stored in the index
- Stored as binary doc values (efficient, aggregation-friendly)
- Same storage mechanism as binary fields, but with HLL-specific handling

---

## 5. validateSketchData() - Critical Validation

**This method reveals the exact serialization format expected!**

```java
private void validateSketchData(BytesRef sketchBytes) throws MapperParsingException {
    try (StreamInput in = new BytesArray(
            sketchBytes.bytes, 
            sketchBytes.offset, 
            sketchBytes.length
        ).streamInput()) {
        
        // 1. Deserialize the sketch
        AbstractHyperLogLogPlusPlus sketch = AbstractHyperLogLogPlusPlus.readFrom(
            in, 
            BigArrays.NON_RECYCLING_INSTANCE
        );
        
        // 2. Verify precision matches
        if (sketch.precision() != precision) {
            throw new MapperParsingException(
                "HLL++ sketch precision mismatch for field [" + fieldType().name() + "]: " +
                "expected " + precision + ", got " + sketch.precision()
            );
        }
        
        // 3. Close the sketch
        sketch.close();
        
    } catch (MapperParsingException e) {
        throw e;
    } catch (Exception e) {
        throw new MapperParsingException(
            "Invalid HLL++ sketch data for field [" + fieldType().name() + "]", 
            e
        );
    }
}
```

### What This Reveals

#### 1. **Serialization Format**

```java
AbstractHyperLogLogPlusPlus sketch = AbstractHyperLogLogPlusPlus.readFrom(in, bigArrays);
```

**Critical Discovery**: The field mapper expects sketches serialized with:
```java
AbstractHyperLogLogPlusPlus.writeTo(StreamOutput)
```

**NOT**:
```java
InternalCardinality.writeTo(StreamOutput)
```

**This means we need to serialize just the sketch, not the entire `InternalCardinality` object!**

#### 2. **Precision Validation**

```java
if (sketch.precision() != precision) {
    throw new MapperParsingException(...);
}
```

**What This Does**:
- Validates sketch precision matches field mapping precision
- Prevents storing sketches with wrong precision
- Ensures all sketches in a field have consistent precision

**For ISM Rollups**:
- ✅ Enforces precision consistency at index time
- ✅ Catches precision mismatches early
- ✅ Complements our metadata validation

#### 3. **Error Handling**

```java
catch (Exception e) {
    throw new MapperParsingException(
        "Invalid HLL++ sketch data for field [" + fieldType().name() + "]", 
        e
    );
}
```

**What This Does**:
- Catches any deserialization errors
- Provides clear error message with field name
- Prevents corrupted data from being indexed

**For ISM Rollups**:
- ✅ Helps debug serialization issues
- ✅ Prevents bad data in rollup indices
- ✅ Clear error messages for troubleshooting

---

## How This Works with ISM Rollups

### Current Implementation (Binary Type)

**Our Code** (RollupIndexer.kt):
```kotlin
is InternalCardinality -> {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)  // Serializes InternalCardinality
    val bytes = output.bytes().toBytesRef().bytes
    aggResults[it.name] = Base64.getEncoder().encodeToString(bytes)
}
```

**Problem**: This serializes `InternalCardinality`, but `HllFieldMapper` expects `AbstractHyperLogLogPlusPlus`!

### Required Implementation (HLL Type)

**Updated Code**:
```kotlin
is InternalCardinality -> {
    // Extract the raw HLL++ sketch
    val sketch = cardinality.getSketch()  // Returns AbstractHyperLogLogPlusPlus
    
    // Serialize just the sketch
    val output = BytesStreamOutput()
    sketch.writeTo(output)
    val bytes = output.bytes().toBytesRef().bytes
    
    // Store raw bytes (no Base64 encoding)
    aggResults[it.name] = bytes
}
```

**Why This Works**:
1. ✅ Serializes `AbstractHyperLogLogPlusPlus` (what field mapper expects)
2. ✅ Uses `sketch.writeTo()` (compatible with `readFrom()`)
3. ✅ No Base64 encoding (field mapper handles binary data)
4. ✅ Passes validation (correct format and precision)

### Indexing Flow

**Step-by-Step**:

1. **RollupIndexer creates document**:
   ```kotlin
   val doc = mapOf(
       "user_id" to mapOf(
           "hll" to sketchBytes  // Raw AbstractHyperLogLogPlusPlus bytes
       )
   )
   ```

2. **HllFieldMapper.parseCreateField() called**:
   ```java
   byte[] value = context.parseExternalValue(byte[].class);
   // value = sketchBytes
   ```

3. **Validation**:
   ```java
   validateSketchData(sketchBytes);
   // Deserializes with AbstractHyperLogLogPlusPlus.readFrom()
   // Validates precision matches field mapping
   ```

4. **Storage**:
   ```java
   context.doc().add(new BinaryDocValuesField(fieldType().name(), sketchBytes));
   // Stores in Lucene doc values
   ```

5. **Result**: Sketch stored in `user_id.hll` field, ready for aggregation!

---

## Key Insights

### 1. **Serialization Format is Definitive**

The validation code proves:
```java
AbstractHyperLogLogPlusPlus.readFrom(in, bigArrays)
```

**We MUST serialize with**:
```kotlin
sketch.writeTo(output)  // AbstractHyperLogLogPlusPlus.writeTo()
```

**NOT**:
```kotlin
cardinality.writeTo(output)  // InternalCardinality.writeTo()
```

### 2. **No Base64 Encoding**

The field mapper handles raw binary data:
```java
byte[] value = context.parser().binaryValue();
```

**For JSON/REST API**: Base64 encoding is handled by the parser
**For programmatic indexing**: Raw bytes work directly

**We should remove Base64 encoding in our code!**

### 3. **Precision Validation at Index Time**

```java
if (sketch.precision() != precision)
```

**This means**:
- Precision mismatches are caught during indexing
- Cannot store sketches with wrong precision
- Complements our metadata validation

### 4. **Doc Values Storage**

```java
new BinaryDocValuesField(fieldType().name(), sketchBytes)
```

**This means**:
- Sketches stored in doc values (not _source)
- Efficient for aggregations
- No need to parse _source during queries

---

## What We Need to Change

### Change 1: Update Serialization

**From** (current):
```kotlin
is InternalCardinality -> {
    val output = BytesStreamOutput()
    cardinality.writeTo(output)
    val bytes = output.bytes().toBytesRef().bytes
    aggResults[it.name] = Base64.getEncoder().encodeToString(bytes)
}
```

**To** (required):
```kotlin
is InternalCardinality -> {
    // Extract raw sketch
    val sketch = cardinality.getSketch()
    
    // Serialize sketch
    val output = BytesStreamOutput()
    sketch.writeTo(output)
    val bytes = output.bytes().toBytesRef().bytes
    
    // Store raw bytes
    aggResults[it.name] = bytes
}
```

### Change 2: Update Field Mapping

**From** (current):
```kotlin
val hllMapping = """"hll":{"type":"binary","doc_values":false}"""
```

**To** (required):
```kotlin
val hllMapping = """"hll":{"type":"hll","precision":$precision,"doc_values":true}"""
```

**Note**: Changed `doc_values` to `true` because HLL field type requires it for aggregations!

### Change 3: Add Precision to Mapping

**Update RollupMappingUtils.kt**:
```kotlin
fun buildFieldMapping(targetField: String, precision: Int): String {
    val fieldParts = targetField.split(".")
    val opening = fieldParts.joinToString("") { """"$it":{"properties":{""" }
    val closing = "}}".repeat(fieldParts.size)
    
    // Include precision in mapping
    val hllMapping = """"hll":{"type":"hll","precision":$precision,"doc_values":true}"""
    
    return "$opening$hllMapping$closing"
}
```

**Update call site**:
```kotlin
rollupMetrics.metrics.forEach { metric ->
    if (metric is Cardinality) {
        val targetField = rollupMetrics.targetField
        mappings.add(buildFieldMapping(targetField, metric.precision))
    }
}
```

---

## Summary

**HllFieldMapper** is the foundation of HLL field support:

1. **Registers** `type: "hll"` as a valid field type
2. **Validates** precision parameter (4-18 range)
3. **Parses** binary sketch data during indexing
4. **Validates** sketch format and precision
5. **Stores** sketches as binary doc values
6. **Provides** field data builder for aggregations

**Critical Discoveries**:
- ✅ Must serialize `AbstractHyperLogLogPlusPlus`, not `InternalCardinality`
- ✅ No Base64 encoding needed (field mapper handles binary data)
- ✅ Precision must be in field mapping
- ✅ Doc values must be enabled (`doc_values: true`)
- ✅ Precision validation happens at index time

**For ISM Rollups**:
- ⚠️ We need to update our serialization code
- ⚠️ We need to remove Base64 encoding
- ⚠️ We need to add precision to field mapping
- ⚠️ We need to enable doc values
- ✅ Everything else is ready!

This file confirms exactly what changes we need to make to work with the HLL field type!
