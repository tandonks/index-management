# Requirements Document

## Introduction

This document specifies the requirements for adding cardinality aggregation support to the OpenSearch Index State Management (ISM) rollup feature. The implementation will enable users to compute and store approximate distinct counts using HyperLogLog++ (HLL++) sketches, supporting both single-tier and multi-tier rollup scenarios.

The feature builds upon the core OpenSearch HLL field mapper (PR #20129) which provides the `hll` field type for storing serialized HLL++ sketches as binary doc values.

## Glossary

- **ISM**: Index State Management - OpenSearch plugin for automating index lifecycle operations
- **Rollup**: A process that summarizes time-series data by aggregating raw documents into compact, pre-computed metrics
- **HLL++**: HyperLogLog++ - A probabilistic algorithm for estimating cardinality (distinct count) with configurable precision
- **Sketch**: The internal state of an HLL++ algorithm, stored as binary data, which can be merged with other sketches
- **Tier-1 Rollup**: The first level of rollup that processes raw source data
- **Tier-N Rollup**: Subsequent rollup levels (N>1) that process already-rolled-up data
- **RollupIndexer**: The ISM component responsible for executing rollup aggregations and indexing results
- **RollupInterceptor**: The component that intercepts search requests to rollup indices and rewrites them appropriately
- **Cardinality Aggregation**: An OpenSearch aggregation that computes approximate distinct counts
- **Precision**: An integer parameter (4-18) controlling the accuracy-memory tradeoff in HLL++ sketches

## Requirements

### Requirement 1: Enable Cardinality Metric in Rollup Job Configuration

**User Story:** As an ISM user, I want to specify cardinality as a metric in my rollup job configuration, so that I can track distinct counts of field values over time.

#### Acceptance Criteria

1. WHEN a user defines a rollup job, THE ISM System SHALL accept "cardinality" as a valid metric type in the metrics configuration
2. WHEN a rollup job includes a cardinality metric, THE ISM System SHALL validate that the source field exists in the source index
3. WHEN a rollup job with cardinality metric is created, THE ISM System SHALL store the metric configuration in the rollup job metadata
4. WHEN a user queries rollup job configuration, THE ISM System SHALL return cardinality metrics in the same format as other metric types

### Requirement 2: Compute and Store HLL++ Sketches for Tier-1 Rollups

**User Story:** As an ISM user, I want the rollup system to compute HLL++ sketches from raw data during Tier-1 rollups, so that distinct counts can be accurately re-aggregated in higher tiers.

#### Acceptance Criteria

1. WHEN the RollupIndexer processes a Tier-1 rollup with cardinality metric, THE ISM System SHALL execute a cardinality aggregation on the source field for each dimension bucket
2. WHEN the cardinality aggregation completes for a bucket, THE ISM System SHALL extract the serialized HLL++ sketch from the aggregation result
3. WHEN multiple documents fall into the same dimension bucket, THE ISM System SHALL merge their HLL++ sketches into a single sketch for that bucket
4. WHEN storing rollup results, THE ISM System SHALL write the serialized sketch to a field named `<target_field>.hll_sketch` using the `hll` field type
5. WHEN the precision parameter is not specified, THE ISM System SHALL use the default precision value of 12

### Requirement 3: Merge HLL++ Sketches for Tier-N Rollups

**User Story:** As an ISM user, I want higher-tier rollups to merge existing HLL++ sketches rather than recompute from raw data, so that multi-tier rollup chains maintain cardinality accuracy.

#### Acceptance Criteria

1. WHEN the RollupIndexer detects the source index is a rollup index, THE ISM System SHALL identify cardinality metrics that require sketch merging
2. WHEN processing a Tier-N rollup with cardinality metric, THE ISM System SHALL read serialized sketches from the `<source_field>.hll_sketch` field
3. WHEN multiple sketches belong to the same dimension bucket, THE ISM System SHALL deserialize and merge them using HLL++ union operations
4. WHEN sketch merging completes for a bucket, THE ISM System SHALL serialize the merged sketch and store it in the target index `<target_field>.hll_sketch` field
5. WHEN all sketches in a bucket have the same precision, THE ISM System SHALL produce a merged sketch with that precision

### Requirement 4: Store Rollup Metadata for Cardinality Metrics

**User Story:** As an ISM user, I want the rollup system to store metadata about cardinality metrics, so that subsequent rollups and queries can validate compatibility.

#### Acceptance Criteria

1. WHEN a rollup job with cardinality metric is executed, THE ISM System SHALL store the precision value in the target index `_meta.rollup` section
2. WHEN a rollup job with cardinality metric is executed, THE ISM System SHALL store the field mapping indicating `<target_field>.hll_sketch` is of type `hll`
3. WHEN creating a Tier-N rollup, THE ISM System SHALL read the source index `_meta.rollup` section to determine the precision of existing sketches
4. WHEN the source and target precision values differ, THE ISM System SHALL reject the rollup job with an error message indicating the precision mismatch

### Requirement 5: Rewrite Search Queries for Cardinality on Rollup Indices

**User Story:** As a user, I want to query cardinality aggregations on rollup indices using the same syntax as raw indices, so that I don't need to change my queries when switching to rollup data.

#### Acceptance Criteria

1. WHEN a search request with cardinality aggregation targets a rollup index, THE RollupInterceptor SHALL detect the cardinality aggregation
2. WHEN the source index is a raw index, THE RollupInterceptor SHALL allow the cardinality aggregation to execute normally on the source field
3. WHEN the source index is a rollup index, THE RollupInterceptor SHALL rewrite the cardinality aggregation to read from the `<field>.hll_sketch` field
4. WHEN the rewritten query executes, THE ISM System SHALL deserialize sketches, merge them across matching buckets, and return the numeric cardinality estimate
5. WHEN the user specifies a field in the cardinality aggregation, THE RollupInterceptor SHALL map it to the corresponding sketch field in the rollup index

### Requirement 6: Validate Precision Compatibility Across Rollup Tiers

**User Story:** As an ISM user, I want the system to prevent precision mismatches in multi-tier rollups, so that I don't get incorrect cardinality estimates.

#### Acceptance Criteria

1. WHEN creating a Tier-N rollup job, THE ISM System SHALL read the precision value from the source rollup index metadata
2. WHEN the source precision differs from the target precision, THE ISM System SHALL reject the rollup job creation with an error message
3. WHEN the source precision matches the target precision, THE ISM System SHALL allow the rollup job to proceed
4. WHEN precision is not specified in the target rollup job, THE ISM System SHALL inherit the precision from the source rollup index
5. WHEN the source index has no precision metadata, THE ISM System SHALL assume the default precision of 12

### Requirement 7: Support Cardinality in Rollup Field Mapping Validation

**User Story:** As an ISM user, I want the rollup system to validate that cardinality metrics are properly configured, so that I receive clear error messages when there are configuration issues.

#### Acceptance Criteria

1. WHEN validating a rollup job, THE ISM System SHALL verify that cardinality metrics reference valid source fields
2. WHEN a search query includes cardinality aggregation on a rollup index, THE ISM System SHALL verify that the field has a corresponding `.hll_sketch` field
3. WHEN field mapping validation fails, THE ISM System SHALL return an error message indicating which field is missing or misconfigured
4. WHEN a rollup index contains cardinality metrics, THE ISM System SHALL include them in the field mapping metadata
5. WHEN multiple rollup jobs target the same index, THE ISM System SHALL verify that cardinality metrics use compatible precision values

### Requirement 8: Handle Cardinality Aggregation in Composite Aggregations

**User Story:** As an ISM user, I want cardinality metrics to work correctly within composite aggregations alongside dimensions, so that I can compute distinct counts per time bucket or grouping.

#### Acceptance Criteria

1. WHEN a rollup job includes both dimensions and cardinality metrics, THE ISM System SHALL execute the composite aggregation with cardinality as a sub-aggregation
2. WHEN processing composite aggregation results, THE ISM System SHALL extract the HLL++ sketch for each bucket defined by the dimension values
3. WHEN storing composite aggregation results, THE ISM System SHALL include both dimension fields and the `.hll_sketch` field in each rollup document
4. WHEN querying a rollup index with composite aggregations, THE RollupInterceptor SHALL rewrite cardinality sub-aggregations to use sketch fields
5. WHEN merging sketches across composite buckets, THE ISM System SHALL group by dimension values before performing sketch unions

### Requirement 9: Provide Clear Error Messages for Unsupported Operations

**User Story:** As an ISM user, I want to receive clear error messages when I attempt unsupported operations with cardinality metrics, so that I understand the limitations and can adjust my configuration.

#### Acceptance Criteria

1. WHEN a user attempts to sort by a cardinality field, THE ISM System SHALL return an error message stating that sorting on HLL fields is not supported
2. WHEN a user attempts to use a cardinality field in a non-aggregation query context, THE ISM System SHALL return an error message explaining that HLL fields are only for aggregations
3. WHEN precision validation fails, THE ISM System SHALL return an error message showing both the source and target precision values
4. WHEN sketch deserialization fails, THE ISM System SHALL return an error message indicating the corrupted document and field
5. WHEN a rollup job configuration is invalid, THE ISM System SHALL return an error message identifying the specific validation failure

### Requirement 10: Maintain Backward Compatibility with Existing Rollup Jobs

**User Story:** As an ISM user, I want existing rollup jobs without cardinality metrics to continue working unchanged, so that adding this feature doesn't break my current workflows.

#### Acceptance Criteria

1. WHEN an existing rollup job without cardinality metrics is executed, THE ISM System SHALL process it using the existing logic without modification
2. WHEN a rollup index without cardinality metrics is queried, THE RollupInterceptor SHALL handle it using existing query rewrite logic
3. WHEN a rollup job is upgraded to include cardinality metrics, THE ISM System SHALL allow the addition without requiring recreation of existing rollup data
4. WHEN reading rollup metadata, THE ISM System SHALL handle the absence of cardinality-specific metadata gracefully
5. WHEN a rollup job uses only traditional metrics, THE ISM System SHALL not create or reference any `.hll_sketch` fields

## Examples

### Example 1: Rollup Job Configuration (Default Precision)

```json
{
  "rollup": {
    "rollup_id": "user_activity_rollup",
    "enabled": true,
    "source_index": "application_logs",
    "target_index": "application_logs_rollup",
    "page_size": 1000,
    "dimensions": [
      {
        "date_histogram": {
          "source_field": "timestamp",
          "fixed_interval": "1h",
          "timezone": "UTC"
        }
      },
      {
        "terms": {
          "source_field": "application_id"
        }
      }
    ],
    "metrics": [
      {
        "source_field": "user_id",
        "metrics": [
          {
            "cardinality": {}
          }
        ]
      },
      {
        "source_field": "response_time",
        "metrics": [
          {
            "avg": {}
          },
          {
            "max": {}
          }
        ]
      }
    ]
  }
}
```

Note: When precision is not specified, the default value of 12 is used.

### Example 1b: Rollup Job Configuration (Custom Precision)

```json
{
  "rollup": {
    "rollup_id": "high_precision_user_rollup",
    "enabled": true,
    "source_index": "application_logs",
    "target_index": "application_logs_rollup_hp",
    "page_size": 1000,
    "dimensions": [
      {
        "date_histogram": {
          "source_field": "timestamp",
          "fixed_interval": "1h",
          "timezone": "UTC"
        }
      }
    ],
    "metrics": [
      {
        "source_field": "user_id",
        "metrics": [
          {
            "cardinality": {
              "precision": 16
            }
          }
        ]
      },
      {
        "source_field": "session_id",
        "metrics": [
          {
            "cardinality": {
              "precision": 14
            }
          }
        ]
      }
    ]
  }
}
```

Note: 
- Precision must be between 4 and 18 (inclusive)
- Higher precision = better accuracy but larger sketch size
- Precision 16 provides ~0.4% error with ~16KB sketch size
- Precision 14 provides ~0.8% error with ~4KB sketch size
- Precision 12 (default) provides ~1.6% error with ~1KB sketch size

### Example 2: Target Rollup Index Mapping (Default Precision)

```json
{
  "mappings": {
    "properties": {
      "timestamp": {
        "type": "date"
      },
      "application_id": {
        "type": "keyword"
      },
      "user_id.hll_sketch": {
        "type": "hll"
      },
      "response_time.avg": {
        "type": "double"
      },
      "response_time.max": {
        "type": "double"
      }
    },
    "_meta": {
      "rollup": {
        "user_activity_rollup": {
          "rollup_id": "user_activity_rollup",
          "source_index": "application_logs",
          "dimensions": [
            {
              "date_histogram": {
                "source_field": "timestamp",
                "target_field": "timestamp",
                "fixed_interval": "1h"
              }
            },
            {
              "terms": {
                "source_field": "application_id",
                "target_field": "application_id"
              }
            }
          ],
          "metrics": [
            {
              "source_field": "user_id",
              "target_field": "user_id",
              "metrics": [
                {
                  "cardinality": {
                    "precision": 12
                  }
                }
              ]
            },
            {
              "source_field": "response_time",
              "target_field": "response_time",
              "metrics": [
                {
                  "avg": {}
                },
                {
                  "max": {}
                }
              ]
            }
          ]
        }
      }
    }
  }
}
```

### Example 2b: Target Rollup Index Mapping (Custom Precision)

```json
{
  "mappings": {
    "properties": {
      "timestamp": {
        "type": "date"
      },
      "user_id.hll_sketch": {
        "type": "hll",
        "precision": 16
      },
      "session_id.hll_sketch": {
        "type": "hll",
        "precision": 14
      }
    },
    "_meta": {
      "rollup": {
        "high_precision_user_rollup": {
          "rollup_id": "high_precision_user_rollup",
          "source_index": "application_logs",
          "dimensions": [
            {
              "date_histogram": {
                "source_field": "timestamp",
                "target_field": "timestamp",
                "fixed_interval": "1h"
              }
            }
          ],
          "metrics": [
            {
              "source_field": "user_id",
              "target_field": "user_id",
              "metrics": [
                {
                  "cardinality": {
                    "precision": 16
                  }
                }
              ]
            },
            {
              "source_field": "session_id",
              "target_field": "session_id",
              "metrics": [
                {
                  "cardinality": {
                    "precision": 14
                  }
                }
              ]
            }
          ]
        }
      }
    }
  }
}
```

Note: Each cardinality metric can have its own precision value, stored both in the field mapping and in the `_meta` section.

### Example 3: Rollup Document (Tier-1)

```json
{
  "timestamp": "2025-12-03T10:00:00Z",
  "application_id": "app-123",
  "user_id.hll_sketch": "<base64-encoded-binary-hll-sketch>",
  "response_time.avg": 245.7,
  "response_time.max": 1203.5
}
```

### Example 4: Search Query on Rollup Index

**User Query (same syntax for raw or rollup index):**
```json
{
  "size": 0,
  "query": {
    "range": {
      "timestamp": {
        "gte": "2025-12-03T00:00:00Z",
        "lte": "2025-12-03T23:59:59Z"
      }
    }
  },
  "aggs": {
    "by_application": {
      "terms": {
        "field": "application_id"
      },
      "aggs": {
        "unique_users": {
          "cardinality": {
            "field": "user_id"
          }
        },
        "avg_response": {
          "avg": {
            "field": "response_time"
          }
        }
      }
    }
  }
}
```

**Internally Rewritten Query (by RollupInterceptor for rollup index):**
```json
{
  "size": 0,
  "query": {
    "range": {
      "timestamp": {
        "gte": "2025-12-03T00:00:00Z",
        "lte": "2025-12-03T23:59:59Z"
      }
    }
  },
  "aggs": {
    "by_application": {
      "terms": {
        "field": "application_id"
      },
      "aggs": {
        "unique_users": {
          "cardinality": {
            "field": "user_id.hll_sketch"
          }
        },
        "avg_response": {
          "avg": {
            "field": "response_time.avg"
          }
        }
      }
    }
  }
}
```

### Example 5: Multi-Tier Rollup Configuration

**Tier-2 Rollup Job (hourly → daily):**
```json
{
  "rollup": {
    "rollup_id": "user_activity_daily_rollup",
    "enabled": true,
    "source_index": "application_logs_rollup",
    "target_index": "application_logs_daily_rollup",
    "page_size": 1000,
    "dimensions": [
      {
        "date_histogram": {
          "source_field": "timestamp",
          "fixed_interval": "1d",
          "timezone": "UTC"
        }
      },
      {
        "terms": {
          "source_field": "application_id"
        }
      }
    ],
    "metrics": [
      {
        "source_field": "user_id",
        "metrics": [
          {
            "cardinality": {}
          }
        ]
      },
      {
        "source_field": "response_time",
        "metrics": [
          {
            "avg": {}
          },
          {
            "max": {}
          }
        ]
      }
    ]
  }
}
```

**Tier-2 Rollup Document:**
```json
{
  "timestamp": "2025-12-03T00:00:00Z",
  "application_id": "app-123",
  "user_id.hll_sketch": "<base64-encoded-merged-hll-sketch>",
  "response_time.avg": 238.4,
  "response_time.max": 1203.5
}
```

Note: The `user_id.hll_sketch` in the daily rollup is the result of merging 24 hourly sketches. The cardinality estimate from this merged sketch will be accurate for the full day.
