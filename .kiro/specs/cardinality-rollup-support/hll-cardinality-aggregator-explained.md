# HllCardinalityAggregator.java - Detailed Explanation

## Overview

`HllCardinalityAggregator` is the **specialized aggregator** that automatically merges HLL++ sketches during cardinality aggregations on HLL fields. This is what makes **Tier-N rollups work automatically** without manual sketch merging!

**This is the magic that makes everything work together!**

---

## Purpose

When a cardinality aggregation runs on an HLL field (like `user_id.hll`), OpenSearch uses this specialized aggregator instead of the standard cardinality aggregator. It:

1. Reads pre-computed HLL++ sketches from documents
2. Merges them automatically using HLL++ merge algorithm
3. Returns accurate cardinality estimates
4. Handles multi-bucket aggregations (composite, terms, etc.)

---

## Class Structure

```java
public class HllCardinalityAggregator extends NumericMetricsAggregator.SingleValue {
    private final HllFieldData fieldData;
    private final int precision;
    private HyperLogLogPlusPlus counts;
    
    // Constructor, collection logic, aggregation building
}
```

**Key Components**:
- `fieldData`: Provides access to HLL sketches in the index
- `precision`: Precision of sketches (from field mapping)
- `counts`: Merged sketch accumulator (one per bucket)

---

## How It Works

### 1. Initialization

```java
HllCardinalityAggregator(
    String name,
    HllFieldData fieldData,
    int precision,
    SearchContext context,
    Aggregator parent,
    Map<String, Object> metadata
) throws IOException {
    super(name, context, parent, metadata);
    this.fieldData = fieldData;
    this.precision = precision;
    this.counts = null; // Lazy initialization
}
```

**What This Does**:
- Stores field data accessor for reading sketches
- Stores precision from field mapping
- Defers sketch creation until first document (lazy initialization)

**For ISM Rollups**:
- `fieldData` will access our `user_id.hll` field
- `precision` comes from our field mapping (e.g., 14)
- Efficient memory usage (no allocation until needed)

---

### 2. Leaf Collector - The Core Logic

**This is where the magic happens!**

```java
@Override
public LeafBucketCollector getLeafCollector(LeafReaderContext ctx, LeafBucketCollector sub) throws IOException {
    final HllFieldData.HllLeafFieldData leafData = fieldData.load(ctx);
    
    return new LeafBucketCollector() {
        @Override
        public void collect(int doc, long bucket) throws IOException {
            AbstractHyperLogLogPlusPlus sketch = null;
            try {
                // 1. Get sketch from document
                sketch = leafData.getSketch(doc);
                
                if (sketch != null) {
                    // 2. Lazy initialize counts
                    if (counts == null) {
                        counts = new HyperLogLogPlusPlus(precision, context.bigArrays(), bucket + 1);
                    }
                    
                    // 3. Grow if needed for this bucket
                    if (bucket >= counts.maxOrd()) {
                        HyperLogLogPlusPlus newCounts = new HyperLogLogPlusPlus(precision, context.bigArrays(), bucket + 1);
                        for (long i = 0; i < counts.maxOrd(); i++) {
                            if (counts.cardinality(i) > 0) {
                                newCounts.merge(i, counts, i);
                            }
                        }
                        counts.close();
                        counts = newCounts;
                    }
                    
                    // 4. Merge the sketch!
                    counts.merge(bucket, sketch, 0);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Failed to merge HLL++ sketch for field [{}] in document {}: {}",
                    fieldData.getFieldName(), doc, e.getMessage());
                throw e;
            } finally {
                if (sketch != null) {
                    sketch.close();
                }
            }
        }
    };
}
```

### Step-by-Step Breakdown

#### Step 1: Get Sketch from Document

```java
sketch = leafData.getSketch(doc);
```

**What This Does**:
- Calls `HllFieldData.getSketch(doc)` we analyzed earlier
- Reads sketch bytes from `user_id.hll` field
- Deserializes into `AbstractHyperLogLogPlusPlus` object

**For ISM Rollups**:
- Reads the sketches we stored during rollup indexing
- Works with both Tier-1 (raw data) and Tier-N (rollup data)

#### Step 2: Lazy Initialize Counts

```java
if (counts == null) {
    counts = new HyperLogLogPlusPlus(precision, context.bigArrays(), bucket + 1);
}
```

**What This Does**:
- Creates the accumulator sketch on first document
- Uses same precision as field mapping
- Allocates space for buckets (for composite/terms aggregations)

**Why Lazy Initialization**:
- Saves memory if no documents match
- Knows correct precision from first sketch
- Efficient for sparse data

#### Step 3: Grow for Multi-Bucket Aggregations

```java
if (bucket >= counts.maxOrd()) {
    HyperLogLogPlusPlus newCounts = new HyperLogLogPlusPlus(precision, context.bigArrays(), bucket + 1);
    for (long i = 0; i < counts.maxOrd(); i++) {
        if (counts.cardinality(i) > 0) {
            newCounts.merge(i, counts, i);
        }
    }
    counts.close();
    counts = newCounts;
}
```

**What This Does**:
- Handles composite/terms aggregations with multiple buckets
- Grows the accumulator if new bucket encountered
- Copies existing bucket data to new accumulator

**For ISM Rollups**:
- Supports composite aggregations (category + timestamp + cardinality)
- Each bucket gets its own merged sketch
- Scales to thousands of buckets

#### Step 4: Merge the Sketch! 🎯

```java
counts.merge(bucket, sketch, 0);
```

**This is the critical line!**

**What This Does**:
- Merges the document's sketch into the accumulator
- Uses HLL++ merge algorithm (register-wise maximum)
- Maintains accuracy within theoretical bounds

**For ISM Rollups**:
- **Tier-1**: Merges sketches from raw data documents
- **Tier-N**: Merges sketches from rollup documents
- **Automatic**: No manual merging needed!

**HLL++ Merge Algorithm**:
```
For each register i:
    merged[i] = max(sketch1[i], sketch2[i])
```

This is why HLL++ is perfect for rollups - merging is simple and accurate!

---

### 3. Building the Result

```java
@Override
public InternalAggregation buildAggregation(long owningBucketOrdinal) {
    if (counts == null || owningBucketOrdinal >= counts.maxOrd() || counts.cardinality(owningBucketOrdinal) == 0) {
        return buildEmptyAggregation();
    }
    
    // Build a copy because the returned Aggregation needs to remain usable after
    // this Aggregator (and its HLL++ counters) is released
    AbstractHyperLogLogPlusPlus copy = counts.clone(owningBucketOrdinal, BigArrays.NON_RECYCLING_INSTANCE);
    return new InternalCardinality(name, copy, metadata());
}
```

**What This Does**:
- Creates `InternalCardinality` with the merged sketch
- Clones the sketch for the specific bucket
- Returns result that can be used after aggregator closes

**For ISM Rollups**:
- This `InternalCardinality` is what our `RollupIndexer` receives!
- Contains the merged sketch from all documents
- Ready to be serialized and stored in next rollup tier

---

## How This Enables Tier-N Rollups

### Tier-1 Rollup (Raw Data → Rollup Index)

**Query**:
```json
{
  "aggs": {
    "hourly": {
      "date_histogram": {"field": "timestamp", "fixed_interval": "1h"},
      "aggs": {
        "unique_users": {
          "cardinality": {"field": "user_id"}  // Raw field
        }
      }
    }
  }
}
```

**What Happens**:
1. Standard cardinality aggregator runs on raw `user_id` field
2. Creates HLL++ sketch from raw values
3. Returns `InternalCardinality` with sketch
4. RollupIndexer serializes and stores in `user_id.hll`

**Result**: Tier-1 rollup index with HLL sketches

---

### Tier-2 Rollup (Tier-1 → Tier-2)

**Query**:
```json
{
  "aggs": {
    "daily": {
      "date_histogram": {"field": "timestamp.date_histogram", "fixed_interval": "1d"},
      "aggs": {
        "unique_users": {
          "cardinality": {"field": "user_id"}  // Rewritten to user_id.hll
        }
      }
    }
  }
}
```

**What Happens**:
1. Query rewriting: `user_id` → `user_id.hll`
2. `CardinalityAggregatorFactory` detects HLL field type
3. Creates `HllCardinalityAggregator` (this class!)
4. For each Tier-1 document:
   - `getSketch(doc)` reads stored sketch
   - `counts.merge(bucket, sketch, 0)` merges it
5. Returns `InternalCardinality` with merged sketch
6. RollupIndexer serializes and stores in Tier-2

**Result**: Tier-2 rollup index with merged sketches

---

### Tier-3 Rollup (Tier-2 → Tier-3)

**Same process repeats!**

1. Query rewriting: `user_id` → `user_id.hll`
2. `HllCardinalityAggregator` reads Tier-2 sketches
3. Merges them automatically
4. Stores in Tier-3

**This continues for any number of tiers!**

---

## Key Insights

### 1. Automatic Sketch Merging ✅

```java
counts.merge(bucket, sketch, 0);
```

**This single line makes Tier-N rollups work automatically!**

- No manual sketch deserialization needed
- No manual merging logic needed
- No custom aggregation plugin needed
- Just works!

### 2. Multi-Bucket Support ✅

```java
if (bucket >= counts.maxOrd()) {
    // Grow accumulator for new buckets
}
```

**Supports composite aggregations**:
- Multiple dimensions (category + timestamp)
- Each bucket gets its own merged sketch
- Scales to thousands of buckets

### 3. Precision Consistency ✅

```java
private final int precision;
counts = new HyperLogLogPlusPlus(precision, ...);
```

**Ensures all sketches have same precision**:
- Uses precision from field mapping
- Merge fails if precision mismatch
- Enforced at aggregation time

### 4. Error Handling ✅

```java
catch (IllegalArgumentException e) {
    logger.warn("Failed to merge HLL++ sketch for field [{}] in document {}: {}",
        fieldData.getFieldName(), doc, e.getMessage());
    throw e;
}
```

**Catches precision mismatches and corruption**:
- Clear error messages
- Identifies problematic documents
- Fails fast on errors

### 5. Resource Management ✅

```java
finally {
    if (sketch != null) {
        sketch.close();
    }
}

@Override
protected void doClose() {
    Releasables.close(counts);
}
```

**Proper cleanup**:
- Closes sketches after use
- Releases accumulator memory
- Prevents memory leaks

---

## How This Works with Our Implementation

### Current Flow (Binary Type)

1. **Tier-1 Rollup**:
   ```
   Raw Data → Standard Cardinality Aggregator → InternalCardinality
   → RollupIndexer.extractHLLSketch() → Base64 string
   → Store in user_id.hll (binary field)
   ```

2. **Tier-2 Rollup** (doesn't work yet):
   ```
   Tier-1 → Query user_id.hll → Binary field error
   → Cannot aggregate on binary field
   ```

### Future Flow (HLL Type)

1. **Tier-1 Rollup**:
   ```
   Raw Data → Standard Cardinality Aggregator → InternalCardinality
   → RollupIndexer.extractHLLSketch() → Raw sketch bytes
   → Store in user_id.hll (HLL field)
   ```

2. **Tier-2 Rollup** (works automatically!):
   ```
   Tier-1 → Query user_id.hll → HLL field detected
   → HllCardinalityAggregator created
   → For each doc: getSketch() → merge()
   → InternalCardinality with merged sketch
   → RollupIndexer.extractHLLSketch() → Raw sketch bytes
   → Store in Tier-2 user_id.hll
   ```

3. **Tier-3+ Rollups** (same process):
   ```
   Tier-N → HllCardinalityAggregator → Merged sketch → Tier-N+1
   ```

---

## Integration with CardinalityAggregatorFactory

Remember the code snippet you shared earlier:

```java
// CardinalityAggregatorFactory
if (config.fieldContext() != null) {
    MappedFieldType fieldType = config.fieldContext().fieldType();
    if (fieldType instanceof HllFieldMapper.HllFieldType hllFieldType) {
        IndexFieldData<?> indexFieldData = searchContext.getQueryShardContext().getForField(fieldType);
        if (indexFieldData instanceof HllFieldData hllFieldData) {
            return new HllCardinalityAggregator(
                name, 
                hllFieldData, 
                hllFieldType.precision(), 
                searchContext, 
                parent, 
                metadata
            );
        }
    }
}
```

**This is how it all connects**:

1. Cardinality aggregation requested on `user_id.hll`
2. Factory detects it's an HLL field type
3. Gets `HllFieldData` for field access
4. Creates `HllCardinalityAggregator` with precision
5. Aggregator reads and merges sketches automatically
6. Returns `InternalCardinality` with merged result

**For ISM Rollups**: This happens transparently during Tier-N rollup jobs!

---

## Performance Characteristics

### Memory Usage

```java
counts = new HyperLogLogPlusPlus(precision, context.bigArrays(), bucket + 1);
```

**Per-bucket memory**:
- Precision 12: ~1.5 KB per bucket
- Precision 14: ~6 KB per bucket
- Precision 16: ~24 KB per bucket

**Example**: 1000 buckets with precision 14 = ~6 MB

### Merge Performance

```java
counts.merge(bucket, sketch, 0);
```

**Merge complexity**: O(2^precision)
- Precision 12: 4,096 register comparisons
- Precision 14: 16,384 register comparisons
- Precision 16: 65,536 register comparisons

**Typical performance**:
- 100 sketches: ~10ms
- 1,000 sketches: ~100ms
- 10,000 sketches: ~1s

Scales linearly with document count!

---

## Summary

**HllCardinalityAggregator** is the key to automatic Tier-N rollups:

1. **Reads** pre-computed sketches from HLL fields
2. **Merges** them automatically using HLL++ algorithm
3. **Returns** accurate cardinality estimates
4. **Supports** multi-bucket aggregations
5. **Handles** errors gracefully
6. **Manages** resources properly

**For ISM Rollups**:
- ✅ Makes Tier-N rollups work automatically
- ✅ No manual sketch merging needed
- ✅ No custom aggregation plugin needed
- ✅ Transparent to rollup job configuration
- ✅ Scales to any number of tiers
- ✅ Maintains accuracy across tiers

**This is the final piece that makes everything work together!**

Once OpenSearch HLL PR #20129 merges, our rollup implementation will automatically leverage this aggregator for seamless multi-tier cardinality rollups!
