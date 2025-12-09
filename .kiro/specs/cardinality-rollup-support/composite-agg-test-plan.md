# Test Plan: Composite Aggregation HLL Support

## The Hypothesis

**Composite aggregations with cardinality sub-aggregations on HLL fields return 0.**

This is why Tier-2 rollups fail - they use composite aggregations to aggregate over Tier-1 rollup indices that have HLL fields.

## Critical Tests to Run

### Test 1: Regular Cardinality on HLL Field ✅ (Already confirmed working)

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "unique_users": {
      "cardinality": { "field": "user_id.hll" }
    }
  }
}
```

**Expected**: Returns correct cardinality
**Status**: ✅ You confirmed this works

### Test 2: Terms Aggregation with Cardinality Sub-Agg on HLL Field

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "by_category": {
      "terms": { 
        "field": "category.terms",
        "size": 10
      },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Expected**: Each bucket should show correct cardinality
**Question**: Does this work?

**If YES**: The issue is specific to composite aggregations
**If NO**: The issue is with any multi-bucket aggregation on HLL fields

### Test 3: Date Histogram with Cardinality Sub-Agg on HLL Field

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "by_time": {
      "date_histogram": {
        "field": "timestamp.date_histogram",
        "fixed_interval": "1h"
      },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Expected**: Each time bucket should show correct cardinality
**Question**: Does this work?

### Test 4: Composite Aggregation with Cardinality Sub-Agg on HLL Field ❌ (Already confirmed broken)

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "composite_agg": {
      "composite": {
        "size": 100,
        "sources": [
          { 
            "timestamp": { 
              "date_histogram": { 
                "field": "timestamp.date_histogram",
                "fixed_interval": "1h"
              } 
            }
          },
          { 
            "category": { 
              "terms": { "field": "category.terms" } 
            }
          }
        ]
      },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Expected**: Each bucket should show correct cardinality
**Actual**: Returns 0 for all buckets
**Status**: ❌ Confirmed broken from your logs

### Test 5: Composite Aggregation with Single Source

```json
GET tier1-rollup/_search
{
  "size": 0,
  "aggs": {
    "composite_agg": {
      "composite": {
        "size": 100,
        "sources": [
          { 
            "category": { 
              "terms": { "field": "category.terms" } 
            }
          }
        ]
      },
      "aggs": {
        "unique_users": {
          "cardinality": { "field": "user_id.hll" }
        }
      }
    }
  }
}
```

**Question**: Does this work? Or is it broken for any composite aggregation?

## What the Tests Will Tell Us

### Scenario A: Only Composite Aggregations Broken

**Test Results**:
- Test 1: ✅ Works
- Test 2: ✅ Works
- Test 3: ✅ Works
- Test 4: ❌ Returns 0
- Test 5: ❌ Returns 0

**Conclusion**: Composite aggregations specifically don't support HLL fields

**Impact**: Critical - rollups require composite aggregations

**Solution**: Need to either:
1. Fix composite aggregation support in OpenSearch
2. Use alternative aggregation approach for rollups
3. Manually merge sketches

### Scenario B: All Multi-Bucket Aggregations Broken

**Test Results**:
- Test 1: ✅ Works
- Test 2: ❌ Returns 0
- Test 3: ❌ Returns 0
- Test 4: ❌ Returns 0
- Test 5: ❌ Returns 0

**Conclusion**: HLL fields don't work with any multi-bucket aggregation

**Impact**: Critical - HLL field type is fundamentally broken for rollups

**Solution**: This would be a major bug in the HLL PR

### Scenario C: Composite with Multiple Sources Broken

**Test Results**:
- Test 1: ✅ Works
- Test 2: ✅ Works
- Test 3: ✅ Works
- Test 4: ❌ Returns 0
- Test 5: ✅ Works

**Conclusion**: Composite aggregations with multiple sources don't support HLL fields

**Impact**: High - but there might be workarounds

**Solution**: Might be able to restructure the aggregation

## Immediate Action Items

1. **Run Tests 2, 3, and 5** on your Tier-1 rollup index
2. **Document the results** for each test
3. **Based on results**, determine the scope of the issue

## If Composite Aggregations Are Broken

### Short-term Workaround Options

#### Option 1: Use Nested Terms Aggregations

Instead of:
```json
{
  "composite": {
    "sources": [
      { "timestamp": {...} },
      { "category": {...} }
    ]
  }
}
```

Use:
```json
{
  "date_histogram": {
    "field": "timestamp",
    "aggs": {
      "by_category": {
        "terms": {
          "field": "category",
          "aggs": {
            "unique_users": {
              "cardinality": { "field": "user_id.hll" }
            }
          }
        }
      }
    }
  }
}
```

**Pros**: Might work if regular bucket aggregations support HLL
**Cons**: Different structure, might not fit rollup framework, less efficient

#### Option 2: Manual Sketch Merging

1. Query the rollup index without aggregations
2. Read the HLL field values
3. Manually merge the sketches
4. Return the result

**Pros**: Guaranteed to work
**Cons**: Complex, inefficient, defeats purpose of HLL field type

#### Option 3: Revert to Binary Field + Manual Merging

Go back to storing sketches as binary fields and manually merging them.

**Pros**: We know this approach works
**Cons**: Loses benefits of HLL field type

### Long-term Solutions

#### Solution 1: Fix OpenSearch Composite Aggregation

1. Identify why composite aggregations don't support HLL fields
2. Submit a PR to OpenSearch to fix it
3. Wait for the fix to be merged and released

**Timeline**: Weeks to months

#### Solution 2: Extend HLL PR

1. Contact HLL PR authors
2. Request composite aggregation support
3. Help implement if needed

**Timeline**: Weeks to months

#### Solution 3: Change Rollup Aggregation Approach

1. Redesign how rollups aggregate data
2. Use aggregation types that support HLL fields
3. Update rollup framework

**Timeline**: Days to weeks

## Investigation Questions

### For OpenSearch Team

1. Does the HLL PR support composite aggregations?
2. Are there known limitations with HLL fields in composite aggregations?
3. Is this a bug or expected behavior?
4. What's the recommended approach for using HLL fields with composite aggregations?

### For HLL PR Authors

1. Were composite aggregations tested?
2. Are there any known issues?
3. Is support planned?
4. Can we help add support?

## Success Criteria

We'll know we've solved the issue when:
1. Composite aggregations with cardinality sub-aggs on HLL fields return correct values
2. Tier-2 rollups work correctly
3. Multi-tier rollups work correctly

## Timeline Estimates

### If Tests Show Limited Scope (e.g., only composite with multiple sources)
- Investigation: 2 hours
- Workaround implementation: 4 hours
- Testing: 2 hours
- **Total: 8 hours (1 day)**

### If Tests Show Composite Aggregations Completely Broken
- Investigation: 4 hours
- OpenSearch source code analysis: 8 hours
- Fix implementation: 16 hours
- Testing: 4 hours
- **Total: 32 hours (4 days)**

### If Tests Show All Multi-Bucket Aggregations Broken
- This would be a fundamental issue with the HLL PR
- Would need to escalate to OpenSearch team
- Might need to revert to binary field approach
- **Timeline: Unknown, depends on OpenSearch team response**

## Next Steps

**RIGHT NOW**:
1. Run Test 2 (terms + cardinality)
2. Run Test 3 (date_histogram + cardinality)
3. Run Test 5 (composite with single source)

**Based on results**:
- If only composite is broken → Investigate composite aggregation framework
- If all multi-bucket is broken → Escalate to OpenSearch team
- If only multi-source composite is broken → Look for workarounds

**Report back with test results and we'll determine the next course of action!**
