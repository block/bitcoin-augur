/*
 * Copyright (c) 2025 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.block.augur.internal

import xyz.block.augur.MempoolTransaction
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

/**
 * Utility functions for creating buckets from fee and weight data.
 */
@InternalAugurApi
internal object BucketCreator {
  /**
   * Maximum bucket index.
   */
  const val BUCKET_MAX = 1000

  /**
   * Minimum bucket index corresponding to 0.1 sat/vByte (Bitcoin Core 29.1/30.0+).
   * Calculated as round(ln(0.1) * 100) = -230
   */
  const val BUCKET_MIN = -230

  /**
   * Total number of bucket array slots needed to store buckets from BUCKET_MIN to BUCKET_MAX.
   * Size = BUCKET_MAX - BUCKET_MIN + 1 = 1000 - (-230) + 1 = 1231
   */
  const val BUCKET_ARRAY_SIZE = BUCKET_MAX - BUCKET_MIN + 1

  /**
   * Creates a bucket map from fee and weight pairs where the key is the bucket index
   * and the value is the sum of the weights at that fee rate, normalized to a one block duration.
   */
  fun createFeeRateBuckets(feeRateWeightPairs: List<MempoolTransaction>): Map<Int, Long> =
    feeRateWeightPairs
      .groupingBy { calculateBucketIndex(it.getFeeRate()) }
      .fold(0L) { acc, tx -> acc + tx.weight }
      .toSortedMap()

  /**
   * Calculates bucket index using logarithms, providing more precision in the lower fee levels.
   */
  private fun calculateBucketIndex(feeRate: Double): Int = min(
    (round(ln(feeRate) * 100).toInt()),
    BUCKET_MAX,
  )
}
