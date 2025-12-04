# Implementation Plan

This document outlines the implementation tasks for adding cardinality aggregation support to ISM rollups. Tasks are organized by implementation phase and build incrementally.

## Task List

- [x] 1. Implement Cardinality Metric Model with Precision Support
  - Update the `Cardinality` class to include an optional precision parameter with validation
  - Add serialization and deserialization support for the precision parameter
  - Ensure backward compatibility with existing cardinality metrics that don't specify precision
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 1.1 Add precision parameter to Cardinality class
  - Add `precision: Int` field with default value of 12
  - Add validation in `init` block to ensure precision is between 4 and 18
  - Update constructor to accept precision parameter
  - _Requirements: 1.1, 5.5_

- [x] 1.2 Update Cardinality serialization methods
  - Update `toXContent()` to include precision when non-default
  - Update `writeTo()` to serialize precision as VInt
  - Update `parse()` to read precision from XContent
  - Update StreamInput constructor to deserialize precision
  - _Requirements: 1.3_

- [ ]* 1.3 Add unit tests for Cardinality model
  - Test parsing with default precision
  - Test parsing with custom precision
  - Test validation of precision range
  - Test serialization round-trip
  - Test backward compatibility (missing precision field)
  - _Requirements: 1.1, 1.2, 1.3, 1.4_


- [x] 2. Update RollupMetrics for Cardinality Field Naming
  - Modify `targetFieldWithType()` to return `.hll_sketch` suffix for cardinality metrics
  - Ensure field naming is consistent with other metric types
  - _Requirements: 2.4_

- [x] 2.1 Update targetFieldWithType method
  - Add case for `Cardinality` metric type
  - Return `"$targetField.hll_sketch"` for cardinality
  - Maintain existing behavior for other metric types
  - _Requirements: 2.4_

- [ ]* 2.2 Add unit tests for RollupMetrics field naming
  - Test cardinality field name generation
  - Test field naming with multiple metrics including cardinality
  - Verify consistency with other metric field names
  - _Requirements: 2.4_

- [x] 3. Implement Rollup Metadata Service Extensions
  - Add support for creating HLL field mappings in target indices
  - Store precision values in rollup metadata
  - Implement precision validation for multi-tier rollups
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 3.1 Create HLL field mappings
  - Implement `buildMappingsForCardinality()` method
  - Generate mapping with `type: "hll"` and precision parameter
  - Integrate with existing mapping creation logic
  - _Requirements: 4.1, 4.2_

- [x] 3.2 Store precision in rollup metadata
  - Update `buildRollupMetadata()` to include precision for cardinality metrics
  - Store precision in `_meta.rollup.<job_id>.metrics[].cardinality.precision`
  - Ensure metadata format is backward compatible
  - _Requirements: 4.1, 4.2_

- [x] 3.3 Implement precision validation
  - Create `validatePrecisionCompatibility()` method
  - Read precision from source rollup index metadata
  - Compare source and target precision values
  - Return validation error if precision values don't match
  - _Requirements: 4.3, 4.4, 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 3.4 Add utility function to extract precision from metadata
  - Implement `getPrecisionFromMetadata()` helper function
  - Handle missing precision (default to 12)
  - Handle malformed metadata gracefully
  - _Requirements: 4.3, 6.5_

- [ ]* 3.5 Add unit tests for metadata service
  - Test HLL mapping creation with default precision
  - Test HLL mapping creation with custom precision
  - Test precision storage in metadata
  - Test precision validation (matching)
  - Test precision validation (mismatched - should fail)
  - Test precision extraction from metadata
  - Test handling of missing precision
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 6.1, 6.2, 6.3, 6.4, 6.5_


- [x] 4. Implement Tier-1 Rollup Support (Raw Data Processing)
  - Build cardinality aggregations for raw data sources
  - Extract HLL++ sketches from aggregation results
  - Serialize and store sketches in rollup documents
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 4.1 Implement cardinality aggregation building
  - Create `buildCardinalityAggregation()` method in RollupIndexer
  - Build `CardinalityAggregationBuilder` with correct field and precision
  - Calculate precision threshold from precision parameter
  - Integrate with composite aggregation building
  - _Requirements: 2.1_

- [x] 4.2 Implement sketch extraction from InternalCardinality
  - Create `extractHllSketch()` method
  - Access internal HLL++ sketch from `InternalCardinality` result
  - Handle cases where sketch is null or empty
  - _Requirements: 2.2_

- [x] 4.3 Implement sketch serialization
  - Serialize HLL++ sketch to BytesReference
  - Use `BytesStreamOutput` for efficient serialization
  - Handle serialization errors gracefully
  - _Requirements: 2.4_

- [x] 4.4 Update document indexing for Tier-1
  - Modify `buildRollupDocument()` to include sketch fields
  - Store serialized sketch in `<targetField>.hll_sketch` field
  - Ensure sketch is stored as binary data
  - _Requirements: 2.4_

- [x] 4.5 Fix field mapping for sketch storage
  - Create `RollupMappingUtils` to build explicit cardinality mappings
  - Update `createTargetIndex()` to include cardinality field mappings
  - Ensure sketch field has `type: "binary"` instead of `type: "keyword"`
  - _Requirements: 2.4, 4.1_

- [ ]* 4.5 Add integration tests for Tier-1 rollup
  - Create test with raw data and cardinality metric
  - Execute rollup and verify sketch field exists
  - Verify sketch field has correct type in mapping
  - Verify precision is stored in metadata
  - Compare cardinality estimate with raw data count
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_


- [x] 5. Implement Tier-N Rollup Support (Sketch Merging)
  - Read HLL++ sketches from source rollup indices
  - Deserialize sketches from binary format
  - Merge sketches across rollup buckets
  - Store merged sketches in target rollup index
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 5.1 Implement sketch reading from rollup indices
  - Create `readHllSketches()` method
  - Query source rollup index for documents in bucket
  - Extract sketch bytes from `.hll_sketch` fields
  - Handle missing or corrupted sketch data
  - _Requirements: 3.1_

- [x] 5.2 Implement sketch deserialization
  - Deserialize BytesReference to HyperLogLogPlusPlus
  - Use `AbstractHyperLogLogPlusPlus.readFrom()`
  - Handle deserialization errors with logging
  - Skip corrupted documents and continue processing
  - _Requirements: 3.2_

- [x] 5.3 Implement sketch merging logic
  - Create `mergeHllSketches()` method
  - Initialize merged sketch with correct precision
  - Merge all sketches for a bucket using `merge()`
  - Validate all sketches have same precision
  - _Requirements: 3.3, 3.4_

- [x] 5.4 Update document indexing for Tier-N
  - Modify `buildRollupDocument()` to handle rollup sources
  - Detect rollup source and use sketch merging path
  - Serialize merged sketch for storage
  - _Requirements: 3.4_

- [ ]* 5.5 Add integration tests for Tier-N rollup
  - Create Tier-1 rollup with cardinality
  - Create Tier-2 rollup from Tier-1 index
  - Verify sketches are merged correctly
  - Verify precision is preserved
  - Compare cardinality estimates across tiers
  - Test 3-tier rollup chain
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_


- [x] 6. Implement Query Rewriting for Cardinality Aggregations
  - Detect cardinality aggregations in search requests
  - Rewrite aggregations to use `.hll_sketch` fields on rollup indices
  - Handle nested and composite aggregations
  - Maintain transparent query syntax for users
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 6.1 Update RollupInterceptor to detect cardinality aggregations
  - Verify `CardinalityAggregationBuilder` is already handled in `getAggregationMetadata()`
  - Ensure cardinality field mappings are populated correctly
  - _Requirements: 5.1, 7.4_

- [x] 6.2 Implement cardinality aggregation rewriting
  - Create `rewriteCardinalityAggregation()` method
  - Map source field to `.hll_sketch` field for rollup indices
  - Preserve aggregation name and other parameters
  - Do not rewrite for raw indices
  - _Requirements: 5.2, 5.3, 5.5_

- [x] 6.3 Integrate rewriting into search request processing
  - Update `rewriteAggregations()` to handle cardinality
  - Recursively rewrite sub-aggregations
  - Handle cardinality in composite aggregations
  - _Requirements: 5.4, 8.4_

- [x] 6.4 Add field mapping validation
  - Verify `.hll_sketch` field exists before rewriting
  - Return clear error if sketch field is missing
  - Include field name in error message
  - _Requirements: 5.5, 7.2, 7.3_

- [ ]* 6.5 Add integration tests for query rewriting
  - Test cardinality query on rollup index
  - Test cardinality with composite aggregations
  - Test cardinality with filters
  - Test multiple cardinality aggregations
  - Verify results match expected estimates
  - Test error handling for missing sketch fields
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_


- [x] 7. Implement Field Mapping and Validation
  - Add cardinality field mappings to rollup field mapping utilities
  - Validate cardinality metrics in rollup job configuration
  - Ensure field mapping compatibility across rollup tiers
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 7.1 Add cardinality to field mapping utilities
  - Implement `populateCardinalityFieldMappings()` in RollupUtils
  - Create `RollupFieldMapping` entries for cardinality metrics
  - Use `FieldType.METRIC` and `Metric.Type.CARDINALITY.type`
  - _Requirements: 7.4_

- [x] 7.2 Add sketch field name utility
  - Implement `getSketchFieldName()` helper function
  - Return `"$targetField.hll_sketch"` for consistency
  - _Requirements: 7.4_

- [x] 7.3 Validate cardinality metrics in rollup jobs
  - Verify source fields exist in source index
  - Verify cardinality metrics are properly configured
  - Return clear error messages for validation failures
  - _Requirements: 7.1, 7.2, 7.3_

- [ ]* 7.4 Add unit tests for field mapping utilities
  - Test cardinality field mapping population
  - Test sketch field name generation
  - Test field mapping validation
  - Test error messages for invalid configurations
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 8. Implement Error Handling and Validation
  - Add comprehensive error handling for cardinality operations
  - Provide clear error messages for common failure scenarios
  - Handle edge cases gracefully
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [x] 8.1 Add precision validation errors
  - Validate precision range (4-18) at job creation
  - Return error with actual value if out of range
  - _Requirements: 9.1_

- [x] 8.2 Add precision mismatch errors
  - Detect precision mismatch during Tier-N validation
  - Return error showing both source and target precision
  - Include field name in error message
  - _Requirements: 9.2_

- [x] 8.3 Add sketch field validation errors
  - Detect missing sketch fields during query rewriting
  - Return error with expected field name
  - _Requirements: 9.3_

- [x] 8.4 Add sketch deserialization error handling
  - Catch deserialization exceptions
  - Log error with document ID and field name
  - Skip corrupted document and continue
  - Include warning in rollup job status
  - _Requirements: 9.4_

- [x] 8.5 Add unsupported operation errors
  - Return error for sorting on HLL fields
  - Return error for script access to HLL fields
  - Include field name in error messages
  - _Requirements: 9.6, 9.7_

- [ ]* 8.6 Add integration tests for error handling
  - Test invalid precision values
  - Test precision mismatch in multi-tier rollup
  - Test missing sketch field in query
  - Test corrupted sketch data
  - Test unsupported operations
  - Verify error messages are clear and helpful
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_


- [x] 9. Implement Composite Aggregation Support
  - Ensure cardinality works correctly within composite aggregations
  - Handle cardinality as sub-aggregation of dimension buckets
  - Extract and store sketches per composite bucket
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 9.1 Update composite aggregation building
  - Add cardinality as sub-aggregation to composite aggregation
  - Ensure cardinality is computed per dimension bucket
  - _Requirements: 8.1_

- [x] 9.2 Update bucket processing for cardinality
  - Extract cardinality aggregation from each composite bucket
  - Extract sketch from cardinality result
  - Associate sketch with bucket's dimension values
  - _Requirements: 8.2_

- [x] 9.3 Update document creation for composite buckets
  - Include dimension fields in rollup document
  - Include `.hll_sketch` field in rollup document
  - Ensure one document per composite bucket
  - _Requirements: 8.3_

- [ ]* 9.4 Add integration tests for composite aggregations
  - Test cardinality with date_histogram dimension
  - Test cardinality with terms dimension
  - Test cardinality with multiple dimensions
  - Verify sketch is stored per bucket
  - Verify query results are correct
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 10. Ensure Backward Compatibility
  - Verify existing rollup jobs continue to work
  - Ensure metadata format is backward compatible
  - Test mixed rollup indices (with and without cardinality)
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [ ] 10.1 Test existing rollup jobs without cardinality
  - Execute existing rollup job configuration
  - Verify no changes to behavior
  - Verify no new fields created
  - _Requirements: 10.1_

- [ ] 10.2 Test querying rollup indices without cardinality
  - Query rollup index without cardinality metrics
  - Verify query rewriting works as before
  - Verify results are unchanged
  - _Requirements: 10.2_

- [ ] 10.3 Test adding cardinality to existing workflow
  - Create new rollup job with cardinality on same source
  - Verify existing rollup data is unaffected
  - Verify new rollup index has cardinality fields
  - _Requirements: 10.3_

- [ ]* 10.4 Test metadata backward compatibility
  - Read rollup metadata without cardinality fields
  - Verify default precision is used
  - Verify no errors occur
  - _Requirements: 10.4_


- [ ] 11. Add End-to-End Integration Tests
  - Test complete rollup lifecycle with cardinality
  - Verify accuracy of cardinality estimates
  - Test multi-tier rollup chains
  - Test ISM policy integration
  - _Requirements: All requirements_

- [ ] 11.1 Create complete rollup lifecycle test
  - Create source index with sample data (1M docs, 10K unique values)
  - Create Tier-1 rollup job with cardinality
  - Execute rollup and verify completion
  - Query cardinality on rollup index
  - Verify estimate is within expected error bounds
  - _Requirements: 1.1, 2.1, 2.2, 2.3, 2.4, 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 11.2 Create multi-tier rollup test
  - Create Tier-1 rollup (1-minute buckets)
  - Create Tier-2 rollup (1-hour buckets)
  - Create Tier-3 rollup (1-day buckets)
  - Query cardinality at each tier
  - Verify estimates are consistent across tiers
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 6.1, 6.2, 6.3, 6.4_

- [ ]* 11.3 Create ISM policy integration test
  - Create ISM policy with rollup action including cardinality
  - Attach policy to source index
  - Trigger policy execution
  - Verify rollup index created with correct mappings
  - Verify cardinality data populated
  - Query and validate results
  - _Requirements: All requirements_

- [ ]* 12. Add Performance and Accuracy Tests
  - Measure sketch size for different precision values
  - Measure merge performance
  - Validate cardinality accuracy
  - _Requirements: 2.5, 3.5_

- [ ]* 12.1 Create sketch size tests
  - Measure sketch size for precision 12, 14, 16
  - Verify sizes match expected values (~1KB, ~4KB, ~16KB)
  - Test storage overhead compared to numeric values
  - _Requirements: 2.4_

- [ ]* 12.2 Create merge performance tests
  - Measure time to merge 100 sketches
  - Measure time to merge 1000 sketches
  - Compare with raw data aggregation time
  - Verify merge time scales linearly
  - _Requirements: 3.3_

- [ ]* 12.3 Create accuracy validation tests
  - Test cardinality accuracy for different data distributions
  - Test accuracy with different precision values
  - Verify error bounds match HLL++ theory
  - Test accuracy across multi-tier rollups
  - _Requirements: 2.5, 3.5_


- [ ]* 13. Add Documentation
  - Create user documentation for cardinality rollup configuration
  - Create developer documentation for implementation details
  - Update API documentation
  - Create migration guide
  - _Requirements: All requirements_

- [ ]* 13.1 Create user documentation
  - Document how to configure cardinality metrics in rollup jobs
  - Explain precision parameter and trade-offs
  - Provide examples of single-tier and multi-tier configurations
  - Document query syntax
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ]* 13.2 Create developer documentation
  - Document architecture and component interactions
  - Document data flow for Tier-1 and Tier-N rollups
  - Document sketch storage format
  - Document metadata schema
  - _Requirements: All requirements_

- [ ]* 13.3 Update API documentation
  - Document Cardinality metric model
  - Document RollupMetrics API changes
  - Document RollupMetadataService API changes
  - Document utility functions
  - _Requirements: All requirements_

- [ ]* 13.4 Create migration guide
  - Document how to add cardinality to existing workflows
  - Document backward compatibility
  - Document known limitations
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

## Task Execution Notes

### Dependencies
- Tasks 1-3 can be executed in parallel (no dependencies)
- Task 4 depends on tasks 1-3 (requires model, metadata, and field naming)
- Task 5 depends on task 4 (requires Tier-1 implementation)
- Task 6 can be executed in parallel with task 5 (independent)
- Tasks 7-9 can be executed in parallel with tasks 4-6 (supporting tasks)
- Task 10 should be executed after tasks 1-9 (validation)
- Tasks 11-12 should be executed after all implementation tasks (integration and performance)
- Task 13 can be executed in parallel with implementation (documentation)

### External Dependencies
- Core OpenSearch HLL PR #20129 must be merged before starting task 4
- Multi-tier rollup PR #1533 should be merged before starting task 5 (or implement in parallel)

### Testing Strategy
- Unit tests (marked with *) should be written alongside implementation tasks
- Integration tests should be executed after completing each major phase
- End-to-end tests should be executed after all implementation is complete
- Performance tests can be executed independently

### Estimated Effort
- Phase 1 (Tasks 1-3): 2-3 days
- Phase 2 (Task 4): 3-4 days
- Phase 3 (Task 5): 3-4 days (optional if PR #1533 not merged)
- Phase 4 (Task 6): 2-3 days
- Phase 5 (Tasks 7-10): 3-4 days
- Phase 6 (Tasks 11-12): 4-5 days
- Phase 7 (Task 13): 2-3 days (can be parallel)

**Total: 14-19 days** (11-15 days without Tier-N support)
