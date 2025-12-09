# Serialization Format Test Plan

## The Problem

Tier-2 rollup cardinality aggregations return 0 instead of the expected value:
```json
{"value.hll":{"value":0}}
```

## Root Cause Hypothesis

The serialization format produced by `sketch.writeTo(0L, output)` doesn't match what `AbstractHyperLogLogPlusPlus.readFrom()` expects.

### Why This Matters

1. **During Tier-1 rollup**: We serialize the sketch and store it in the rollup index
2. **During Tier-2 rollup**: OpenSearch reads the sketch using `AbstractHyperLogLogPlusPlus.readFrom()`
3. **If formats don't match**: The deserialized sketch will be empty/invalid → cardinality = 0

## The Test

I've added code to `extractHLLSketch()` that:

1. Serializes the sketch using `writeTo(0L, output)`
2. **Immediately deserializes it** using `readFrom()`
3. Compares the cardinalities

```kotlin
// Serialize
sketch.writeTo(0L, output)
val bytes = output.bytes()

// Deserialize
val deserializedSketch = AbstractHyperLogLogPlusPlus.readFrom(
    bytes.streamInput(),
    BigArrays.NON_RECYCLING_INSTANCE
)

// Compare
logger.info("Original: ${sketch.cardinality(0L)}")
logger.info("Deserialized: ${deserializedSketch.cardinality(0L)}")
```

## Expected Outcomes

### Scenario 1: Format is Correct ✅
```
Original cardinality: 1000
Deserialized cardinality: 1000
```
→ The problem is elsewhere (field mapping, query, etc.)

### Scenario 2: Format is Wrong ❌
```
Original cardinality: 1000
Deserialized cardinality: 0
```
→ **This is the root cause!** We need a different serialization method.

## Next Steps Based on Results

### If Format is Wrong

We need to find the correct serialization method. Options:

#### Option 1: Check for `writeTo(StreamOutput)` without bucket ordinal
```kotlin
// Instead of:
sketch.writeTo(0L, output)

// Try:
sketch.writeTo(output)  // If this method exists
```

#### Option 2: Use InternalCardinality serialization
```kotlin
// Serialize the whole InternalCardinality
cardinality.writeTo(output)

// But then we need to skip the name field when deserializing
```

#### Option 3: Check HllFieldMapper implementation
Look at how the HLL PR's `HllFieldMapper.parseCreateField()` serializes sketches during indexing.

#### Option 4: Manual sketch extraction
If `AbstractHyperLogLogPlusPlus` has methods to access internal state:
```kotlin
// Hypothetical:
val registers = sketch.getRegisters(0L)
val precision = sketch.getPrecision()
// Manually serialize registers + precision
```

### If Format is Correct

The problem is in how the HLL field type reads the data. Investigate:

1. **Field mapping**: Is the field actually mapped as `hll` type?
2. **Doc values**: Are doc values enabled and working?
3. **Precision**: Does the precision match between write and read?
4. **Query rewriting**: Is the query being rewritten correctly?

## How to Run the Test

1. Build the plugin:
   ```bash
   ./gradlew clean build -Dopensearch.version=3.4.0-SNAPSHOT -x test -x integTest
   ```

2. Deploy and run a Tier-1 rollup

3. Check the logs for:
   ```
   Extracting HLL sketch - cardinality.value(): X, sketch.cardinality(0L): Y
   Deserialization test - cardinality after deserialize: Z
   ```

4. If Z = 0 but X > 0, we've found the root cause!

## Additional Logging

The test also logs:
- Sketch class name (to verify we're using the right class)
- Serialized bytes size (to verify data is being written)
- Any deserialization errors

## Critical Questions to Answer

1. **Does `writeTo(0L, output)` produce the same format as `readFrom()` expects?**
   - If NO → We need a different serialization method
   - If YES → The problem is elsewhere

2. **Is the sketch populated before serialization?**
   - Check if `sketch.cardinality(0L)` > 0
   - If 0 → The sketch is empty (problem in Tier-1 rollup)
   - If > 0 → The sketch is populated (problem in serialization/deserialization)

3. **What is the correct serialization format?**
   - Check OpenSearch source code
   - Check HLL PR implementation
   - Check if there are multiple `writeTo` methods

## Success Criteria

We'll know we've fixed the issue when:
1. The deserialization test shows matching cardinalities
2. Tier-2 rollup queries return correct cardinality values
3. The fix works for both Tier-1 and Tier-N rollups

## Timeline

1. **Immediate**: Run the test and check logs
2. **If format is wrong**: Research correct serialization method (1-2 hours)
3. **Implement fix**: Update `extractHLLSketch()` method (30 minutes)
4. **Verify**: Test Tier-1 and Tier-2 rollups (1 hour)
5. **Document**: Update implementation notes (30 minutes)

Total estimated time: 3-4 hours if format is wrong, 1-2 hours if format is correct.
