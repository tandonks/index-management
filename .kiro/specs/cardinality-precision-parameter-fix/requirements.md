# Requirements Document

## Introduction

This document specifies requirements for fixing the precision parameter handling in cardinality rollup aggregations. The current implementation creates HLL++ sketches with incorrect precision values during composite aggregation, causing a mismatch between the sketch precision and the target index mapping precision. This results in indexing failures when rolling up data with custom precision values.

## Glossary

- **HLL++ Sketch**: HyperLogLog++ data structure used for cardinality estimation
- **Precision**: The log2 of the number of registers in the HLL++ sketch (valid range: 4-18), stored in the sketch itself
- **Precision Threshold**: The number of unique values below which exact counting is used, specified by the user (precisionThreshold = 2^precision)
- **Composite Aggregation**: OpenSearch aggregation that combines multiple bucket aggregations
- **Target Index**: The rollup index where aggregated data is stored
- **Source Index**: The original index containing raw data to be rolled up
- **RollupIndexer**: Component responsible for executing rollup jobs and indexing results
- **RollupMapperService**: Component responsible for creating target index mappings
- **Cardinality Metric**: User-defined metric configuration containing the precisionThreshold parameter

## Requirements

### Requirement 1: Precision Threshold to Precision Conversion

**User Story:** As a rollup user, I want to specify a custom precisionThreshold value for cardinality metrics, so that I can control the accuracy-storage trade-off for my use case.

#### Acceptance Criteria

1. WHEN THE RollupIndexer receives a precisionThreshold value from the Cardinality metric configuration, THE RollupIndexer SHALL calculate the precision value using the existing `precisionFromThreshold()` utility function
2. WHEN THE RollupIndexer creates a CardinalityAggregationBuilder, THE RollupIndexer SHALL set the precisionThreshold parameter to the user-provided value
3. WHEN THE RollupMapperService creates target index mappings for cardinality fields, THE RollupMapperService SHALL use the calculated precision value from `precisionFromThreshold()` (not the precisionThreshold)
4. WHEN THE RollupIndexer extracts HLL++ sketches from aggregation results, THE RollupIndexer SHALL verify that the sketch precision matches the calculated precision value
5. WHEN a precision mismatch is detected between sketch and mapping, THE RollupIndexer SHALL log a detailed error message including the precisionThreshold, calculated precision, actual sketch precision, and field name

### Requirement 2: Precision Consistency Validation

**User Story:** As a rollup developer, I want the system to validate precision consistency across all components, so that precision mismatches are detected early and reported clearly.

#### Acceptance Criteria

1. WHEN THE RollupMapperService creates HLL field mappings, THE RollupMapperService SHALL calculate precision from precisionThreshold and store it in the mapping properties
2. WHEN THE RollupIndexer processes aggregation results, THE RollupIndexer SHALL extract the actual precision from the HLL++ sketch
3. IF the sketch precision does not match the mapping precision, THEN THE RollupIndexer SHALL throw an exception with a message containing the precisionThreshold, calculated precision, and actual sketch precision
4. WHEN THE RollupIndexer logs precision information, THE RollupIndexer SHALL include the field name, precisionThreshold, calculated precision, and actual sketch precision
5. WHEN precision validation fails, THE RollupIndexer SHALL prevent document indexing and mark the rollup job as failed

### Requirement 3: Backward Compatibility

**User Story:** As a rollup user with existing rollup jobs, I want the precision fix to not break my existing rollups, so that I can upgrade without disruption.

#### Acceptance Criteria

1. WHEN a rollup job does not specify a precisionThreshold value, THE System SHALL use the default precisionThreshold value of 40000 (which corresponds to precision 12)
2. WHEN THE RollupIndexer processes existing rollup indices without precision metadata, THE RollupIndexer SHALL assume precision 12
3. WHEN THE RollupMapperService creates mappings without explicit precisionThreshold, THE RollupMapperService SHALL use precision 12
4. WHEN upgrading from a version without precision support, THE System SHALL continue to process existing rollup jobs without errors
5. WHEN querying existing rollup indices, THE System SHALL handle missing precision metadata gracefully

### Requirement 4: Error Reporting and Debugging

**User Story:** As a rollup developer debugging precision issues, I want detailed logging of precision values at each step, so that I can quickly identify where mismatches occur.

#### Acceptance Criteria

1. WHEN THE RollupIndexer builds a cardinality aggregation, THE RollupIndexer SHALL log the field name, user-provided precisionThreshold, and calculated precision value
2. WHEN THE RollupIndexer extracts an HLL++ sketch, THE RollupIndexer SHALL log the sketch's actual precision value
3. WHEN THE RollupMapperService creates HLL mappings, THE RollupMapperService SHALL log the field name, precisionThreshold, and calculated precision value
4. WHEN a precision mismatch occurs, THE RollupIndexer SHALL log the precisionThreshold, expected precision (calculated), and actual precision (from sketch)
5. WHEN indexing fails due to precision mismatch, THE System SHALL include the document ID, field name, precisionThreshold, expected precision, and actual precision in the error message

### Requirement 5: Multi-Tier Rollup Precision Handling

**User Story:** As a rollup user creating multi-tier rollups, I want precision to be preserved across rollup tiers, so that cardinality estimates remain accurate.

#### Acceptance Criteria

1. WHEN THE RollupIndexer reads sketches from a source rollup index, THE RollupIndexer SHALL extract the precision from the source index mapping
2. WHEN THE RollupIndexer merges sketches from multiple documents, THE RollupIndexer SHALL verify all sketches have the same precision
3. WHEN THE RollupMapperService creates a target rollup index from a source rollup index, THE RollupMapperService SHALL use the same precision as the source
4. IF source and target precision values differ, THEN THE System SHALL reject the rollup job configuration with a clear error message
5. WHEN merging sketches with different precision values, THE RollupIndexer SHALL log a warning and skip the mismatched sketches
