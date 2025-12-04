/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.rollup.model.metric

import org.opensearch.core.common.io.stream.StreamInput
import org.opensearch.core.common.io.stream.StreamOutput
import org.opensearch.core.xcontent.ToXContent
import org.opensearch.core.xcontent.XContentBuilder
import org.opensearch.core.xcontent.XContentParser
import org.opensearch.core.xcontent.XContentParser.Token
import org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken

class Cardinality(
    val precision: Int = DEFAULT_PRECISION,
) : Metric(Type.CARDINALITY) {
    init {
        require(precision in MIN_PRECISION..MAX_PRECISION) {
            "Precision must be between $MIN_PRECISION and $MAX_PRECISION, got: $precision"
        }
    }

    constructor(sin: StreamInput) : this(
        precision = sin.readVInt(),
    )

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        builder.startObject().startObject(Type.CARDINALITY.type)
        if (precision != DEFAULT_PRECISION) {
            builder.field(PRECISION_FIELD, precision)
        }
        return builder.endObject().endObject()
    }

    override fun writeTo(out: StreamOutput) {
        out.writeVInt(precision)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Cardinality
        return precision == other.precision
    }

    override fun hashCode(): Int = precision.hashCode()

    override fun toString(): String = "Cardinality(precision=$precision)"

    companion object {
        // HLL++ precision constants (from OpenSearch core AbstractHyperLogLog)
        const val MIN_PRECISION = 4
        const val MAX_PRECISION = 18
        const val DEFAULT_PRECISION = 12
        const val PRECISION_FIELD = "precision"

        fun parse(xcp: XContentParser): Cardinality {
            var precision = DEFAULT_PRECISION

            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                when (fieldName) {
                    PRECISION_FIELD -> precision = xcp.intValue()
                    else -> throw IllegalArgumentException("Invalid field [$fieldName] found in cardinality metric")
                }
            }

            return Cardinality(precision)
        }
    }
}
