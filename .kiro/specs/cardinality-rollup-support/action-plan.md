# Action Plan: Fix Cardinality Returning 0

## Current Status

✅ **Investigation Complete**
- Root cause hypothesis identified
- Deserialization test added to code
- Comprehensive logging in place
- Multiple potential fixes documented

⏳ **Next: Execute Test**
- Build and deploy updated code
- Run Tier-1 rollup to trigger test
- Analyze logs to confirm hypothesis

## Step-by-Step Action Plan

### Phase 1: Confirm the Root Cause (30 minutes)

#### Step 1.1: Build the Plugin
```bash
cd /path/to/index-management
./gradlew clean build -Dopensearch.version=3.4.0-SNAPSHOT -x test -x integTest
```

**Expected**: Build succeeds with updated logging code

#### Step 1.2: Deploy the Plugin
```bash
# Stop OpenSearch
# Replace plugin
# Start OpenSearch
```

#### Step 1.3: Run a Tier-1 Rollup

Create a simple rollup job with cardinality:
```json
{
  "rollup": {
    "source_index": "test-data",
    "target_index": "test-rollup",
    "dimensions": [
      { "date_histogram": { "field": "timestamp", "fixed_interval": "1h" } }
    ],
    "metrics": [
      { "field": "user_id", "metrics": ["cardinality"] }
    ]
  }
}
```

#### Step 1.4: Check the Logs

Look for these log lines:
```
Extracting HLL sketch - cardinality.value(): X, sketch.cardinality(0L): Y
Sketch class: ...
Approach 1 (writeTo with bucket): Z bytes
Deserialization test - cardinality after deserialize: W
```

**Critical Check**: If W = 0 but X > 0, we've confirmed the root cause!

### Phase 2: Research the Fix (30-60 minutes)

#### Step 2.1: Check OpenSearch Source Code

Look for `AbstractHyperLogLogPlusPlus` class:
```bash
# Clone OpenSearch repo if needed
git clone https://github.com/opensearch-project/OpenSearch.git
cd OpenSearch

# Find the class
find . -name "*AbstractHyperLogLogPlusPlus*"

# Look for writeTo methods
grep -n "void writeTo" path/to/AbstractHyperLogLogPlusPlus.java
```

**Look for**:
- `void writeTo(StreamOutput out)` - without bucket ordinal
- Any other serialization methods
- Comments about serialization format

#### Step 2.2: Check HllFieldMapper Implementation

Look at how HLL field mapper serializes sketches:
```bash
# In OpenSearch repo
find . -name "*HllFieldMapper*"

# Look for parseCreateField method
grep -A 20 "parseCreateField" path/to/HllFieldMapper.java
```

**Look for**:
- How it gets sketch bytes
- What serialization method it uses
- Any format specifications

#### Step 2.3: Check InternalCardinality Implementation

```bash
# Look for InternalCardinality
find . -name "*InternalCardinality*"

# Check its writeTo method
grep -A 10 "void writeTo" path/to/InternalCardinality.java
```

**Look for**:
- How it serializes the sketch
- What method it calls on the sketch

### Phase 3: Implement the Fix (30-60 minutes)

Based on research, implement one of these fixes:

#### Fix Option A: Use writeTo(StreamOutput)

If we find `writeTo(StreamOutput)` method:
```kotlin
private fun extractHLLSketch(cardinality: InternalCardinality): ByteArray = try {
    val output = BytesStreamOutput()
    val sketchField = cardinality.javaClass.getDeclaredField("counts")
    sketchField.isAccessible = true
    val sketch = sketchField.get(cardinality) as AbstractHyperLogLogPlusPlus
    
    // Use the method without bucket ordinal
    sketch.writeTo(output)
    output.bytes().toBytesRef().bytes
}
```

#### Fix Option B: Match HllFieldMapper's Approach

If HllFieldMapper uses a specific method:
```kotlin
// Use whatever method HllFieldMapper uses
// Example: sketch.toBytes() or sketch.serialize()
```

#### Fix Option C: Create Single-Bucket Sketch

If we need to extract bucket 0 as a new sketch:
```kotlin
// Create new sketch with just bucket 0 data
val singleSketch = createSingleBucketSketch(sketch, 0L)
singleSketch.writeTo(output)
```

### Phase 4: Test the Fix (1 hour)

#### Step 4.1: Build and Deploy
```bash
./gradlew clean build -Dopensearch.version=3.4.0-SNAPSHOT -x test -x integTest
# Deploy plugin
```

#### Step 4.2: Test Tier-1 Rollup

1. Create rollup job with cardinality
2. Wait for rollup to complete
3. Check logs for deserialization test
4. **Verify**: Deserialized cardinality matches original

#### Step 4.3: Query Tier-1 Rollup

```json
GET test-rollup/_search
{
  "aggs": {
    "unique_users": {
      "cardinality": { "field": "user_id.hll" }
    }
  }
}
```

**Expected**: Cardinality value matches source data (within HLL++ error bounds)

#### Step 4.4: Test Tier-2 Rollup

1. Create rollup job that rolls up the Tier-1 rollup
2. Wait for rollup to complete
3. Query the Tier-2 rollup
4. **Verify**: Cardinality value is accurate

#### Step 4.5: Test Multi-Tier Rollup

1. Create Tier-3 rollup (rolls up Tier-2)
2. Query Tier-3 rollup
3. **Verify**: Cardinality value is still accurate

### Phase 5: Document and Clean Up (30 minutes)

#### Step 5.1: Update Documentation

1. Document the root cause
2. Document the fix
3. Update implementation notes
4. Add comments to code

#### Step 5.2: Remove Test Code

Remove the deserialization test from `extractHLLSketch()`:
```kotlin
// Remove this section:
try {
    val testInput = approach1Output.bytes().streamInput()
    val deserializedSketch = ...
    logger.info("Deserialization test - ...")
} catch (e: Exception) {
    logger.error("Failed to deserialize sketch for testing: ${e.message}", e)
}
```

Keep the essential logging:
```kotlin
logger.info("Extracting HLL sketch - cardinality: ${cardinality.value()}")
```

#### Step 5.3: Create Summary Document

Document:
- What the issue was
- How we found it
- What the fix was
- How to verify it works

## Contingency Plans

### If Fix Doesn't Work

1. **Try next fix option** from `potential-fixes.md`
2. **Add more logging** to understand what's happening
3. **Check OpenSearch forums** for similar issues
4. **Contact OpenSearch team** for guidance

### If Multiple Fixes Needed

Some scenarios might need different fixes:
- Tier-1 rollup: One serialization method
- Tier-N rollup: Different serialization method

Be prepared to handle both cases.

### If No Fix Works

Fallback options:
1. **Revert to binary field type** with Base64 encoding
2. **Implement manual sketch merging** for Tier-N
3. **Wait for HLL PR documentation** to clarify format
4. **Contact HLL PR authors** for guidance

## Success Metrics

### Must Have ✅
- Deserialization test shows matching cardinalities
- Tier-1 rollup cardinality queries work
- Tier-2 rollup cardinality queries work

### Should Have ✅
- Multi-tier rollups work correctly
- Performance is acceptable
- Code is clean and well-documented

### Nice to Have ✅
- Comprehensive test coverage
- Performance benchmarks
- Migration guide for users

## Timeline

### Optimistic: 2.5 hours
- Phase 1: 30 min
- Phase 2: 30 min
- Phase 3: 30 min
- Phase 4: 1 hour
- Phase 5: 30 min

### Realistic: 4.5 hours
- Phase 1: 30 min
- Phase 2: 1 hour
- Phase 3: 1 hour
- Phase 4: 1.5 hours
- Phase 5: 30 min

### Pessimistic: 8 hours
- Phase 1: 30 min
- Phase 2: 2 hours
- Phase 3: 2 hours
- Phase 4: 3 hours
- Phase 5: 30 min

## Communication Plan

### During Investigation
- Update team on progress every 2 hours
- Share findings in team channel
- Ask for help if stuck

### After Fix
- Share solution with team
- Update documentation
- Create knowledge base article

### If Blocked
- Escalate to team lead
- Contact OpenSearch community
- Consider alternative approaches

## Resources

### Documentation
- `root-cause-analysis.md` - Root cause hypothesis
- `deep-dive-investigation.md` - Detailed analysis
- `serialization-test-plan.md` - Test plan
- `potential-fixes.md` - Fix options
- `investigation-summary.md` - Complete summary

### Code Files
- `RollupIndexer.kt` - Contains extractHLLSketch()
- `RollupMappingUtils.kt` - Field mapping configuration
- `opendistro-ism-config.json` - Dynamic template

### External Resources
- OpenSearch HLL PR #20129
- OpenSearch source code
- HLL++ algorithm documentation

## Ready to Execute

Everything is in place:
- ✅ Code updated with test
- ✅ Hypothesis documented
- ✅ Fixes identified
- ✅ Test plan ready
- ✅ Timeline estimated

**Next step**: Build, deploy, and run the test!
