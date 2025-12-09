# Investigation Summary: Cardinality Returning 0

## Problem Statement

When querying a Tier-2 rollup index with cardinality aggregation, the result is 0 instead of the expected value:
```json
{"value.hll":{"value":0}}
```

## What We Know

### Working
✅ Tier-1 rollups create successfully
✅ Sketches are being stored in the rollup index
✅ Field mapping is correct (`type: "hll"`)
✅ Cardinality aggregation on source data works

### Not Working
❌ Cardinality aggregation on Tier-2 rollup returns 0
❌ Sketches are not being read correctly during aggregation

## Root Cause Hypothesis

**The serialization format produced by `sketch.writeTo(0L, output)` doesn't match what `AbstractHyperLogLogPlusPlus.readFrom()` expects.**

### Evidence

1. **Method Signature Mismatch**:
   ```java
   // Write method takes bucket ordinal
   void writeTo(long bucketOrd, StreamOutput out)
   
   // Read method doesn't take bucket ordinal
   static AbstractHyperLogLogPlusPlus readFrom(StreamInput in, BigArrays bigArrays)
   ```

2. **Multi-Bucket Design**:
   - `writeTo(bucketOrd, ...)` is designed for multi-bucket aggregations
   - It writes data for a **specific bucket**
   - `readFrom()` expects a **different format** (probably for a complete sketch)

3. **Deserialization Test**:
   - We added code to serialize and immediately deserialize
   - If deserialized cardinality = 0 but original > 0, format is wrong

## The Investigation

### What We've Done

1. ✅ Analyzed HLL PR implementation
2. ✅ Understood how HLL field type works
3. ✅ Identified the serialization method being used
4. ✅ Added comprehensive logging
5. ✅ Added deserialization test to verify format
6. ✅ Documented potential fixes

### What We Need to Do

1. ⏳ Run the deserialization test
2. ⏳ Check the logs to confirm format mismatch
3. ⏳ Research the correct serialization method
4. ⏳ Implement the fix
5. ⏳ Verify with Tier-1 and Tier-2 rollups

## The Test

We've added code to `extractHLLSketch()` that will tell us definitively if the format is wrong:

```kotlin
// Serialize
sketch.writeTo(0L, output)
val bytes = output.bytes()

// Deserialize immediately
val deserializedSketch = AbstractHyperLogLogPlusPlus.readFrom(
    bytes.streamInput(),
    BigArrays.NON_RECYCLING_INSTANCE
)

// Compare
logger.info("Original: ${sketch.cardinality(0L)}")
logger.info("Deserialized: ${deserializedSketch.cardinality(0L)}")
```

### Expected Results

**If format is wrong** (most likely):
```
Original cardinality: 1000
Deserialized cardinality: 0
SERIALIZATION FORMAT MISMATCH
```

**If format is correct** (less likely):
```
Original cardinality: 1000
Deserialized cardinality: 1000
```

## Potential Fixes

### Most Likely: Use Different Serialization Method

Find and use `writeTo(StreamOutput)` without bucket ordinal:
```kotlin
sketch.writeTo(output)  // Instead of sketch.writeTo(0L, output)
```

### Alternative Fixes

1. Extract single-bucket sketch as new instance
2. Use HLL field mapper's serialization approach
3. Create new sketch instance with bucket 0 data
4. Manually extract and serialize registers
5. Serialize entire InternalCardinality object

See `potential-fixes.md` for detailed analysis of each approach.

## Next Steps

### Immediate (Now)

1. **Build and deploy** the updated code with logging
2. **Run a Tier-1 rollup** to trigger the test
3. **Check the logs** for deserialization test results

### If Format is Wrong (Expected)

1. **Research** OpenSearch source for correct serialization method
2. **Implement** the fix (estimated 30-60 minutes)
3. **Test** with Tier-1 rollup
4. **Verify** with Tier-2 rollup
5. **Document** the solution

### If Format is Correct (Unexpected)

1. **Investigate** field mapping and doc values
2. **Check** query rewriting
3. **Verify** precision parameter
4. **Debug** HLL field type reading logic

## Timeline

### Optimistic (Format is wrong, easy fix)
- Test: 30 minutes
- Research: 30 minutes
- Fix: 30 minutes
- Verify: 1 hour
- **Total: 2.5 hours**

### Realistic (Format is wrong, need to try multiple fixes)
- Test: 30 minutes
- Research: 1 hour
- Fix attempts: 2 hours
- Verify: 1 hour
- **Total: 4.5 hours**

### Pessimistic (Format is correct, deeper issue)
- Test: 30 minutes
- Investigation: 3 hours
- Fix: 2 hours
- Verify: 1 hour
- **Total: 6.5 hours**

## Success Criteria

We'll know we've solved the problem when:

1. ✅ Deserialization test shows matching cardinalities
2. ✅ Tier-1 rollup stores sketches correctly
3. ✅ Tier-2 rollup reads sketches correctly
4. ✅ Cardinality aggregations return accurate values
5. ✅ Multi-tier rollups work correctly

## Key Insights

### Why This is Likely the Issue

1. **Symptom matches**: Cardinality = 0 suggests empty/invalid sketch
2. **Method mismatch**: `writeTo(bucketOrd, ...)` vs `readFrom(...)` signatures don't align
3. **Multi-bucket design**: We're using a multi-bucket method for single-sketch storage
4. **HLL PR doesn't document this**: The PR assumes you know the correct serialization method

### Why We Didn't Catch This Earlier

1. **Tier-1 rollups work**: Serialization happens, no errors
2. **No immediate feedback**: The format mismatch only shows up during Tier-2 aggregation
3. **Complex abstraction**: The HLL field type hides the deserialization logic
4. **Lack of documentation**: The HLL PR doesn't explicitly document serialization format

### What We've Learned

1. **Always test round-trip**: Serialize → Deserialize → Verify
2. **Check method signatures**: Mismatched signatures are a red flag
3. **Understand the abstraction**: Know what format each layer expects
4. **Add comprehensive logging**: Helps identify issues quickly

## Documentation Created

1. `root-cause-analysis.md` - Initial hypothesis and investigation plan
2. `deep-dive-investigation.md` - Detailed analysis of serialization methods
3. `serialization-test-plan.md` - Test plan and expected outcomes
4. `potential-fixes.md` - Detailed analysis of possible solutions
5. `investigation-summary.md` - This document

## Code Changes

1. Added comprehensive logging to `extractHLLSketch()`
2. Added deserialization test to verify format
3. Logs original vs deserialized cardinality
4. Logs sketch class and byte size

## References

- HLL PR #20129 analysis documents
- OpenSearch AbstractHyperLogLogPlusPlus class
- HllFieldMapper implementation
- HllFieldData implementation
- HllCardinalityAggregator implementation

## Conclusion

We have a **clear hypothesis**, a **definitive test**, and **multiple potential fixes** ready to implement.

The next step is to **run the test** and let the logs tell us exactly what's wrong. Based on the results, we can quickly implement the appropriate fix.

**Estimated time to resolution: 2.5 - 6.5 hours**

The investigation is complete. Now we need to execute the test and implement the fix.
