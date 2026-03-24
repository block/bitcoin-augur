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
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

/**
 * Holds bucket boundaries derived from a minimum fee rate.
 *
 * Uses ceil so the lowest bucket never represents a fee rate below [minFeeRate].
 *
 * @property bucketMin Minimum bucket index, computed as ceil(ln(minFeeRate) * 100)
 * @property arraySize Total number of bucket array slots (BUCKET_MAX - bucketMin + 1)
 */
@InternalAugurApi
internal class BucketConfig(minFeeRate: Double) {
  val bucketMin: Int = ceil(ln(minFeeRate) * 100).toInt()
  val arraySize: Int = BucketCreator.BUCKET_MAX - bucketMin + 1

  companion object {
    val DEFAULT = BucketConfig(1.0)
  }
}

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
   * Converts a bucket index to the corresponding array position.
   * Buckets are stored in reverse order so that the highest fee rate (BUCKET_MAX) is at index 0.
   */
  fun toArrayIndex(bucket: Int): Int = BUCKET_MAX - bucket

  /**
   * Converts an array position back to the original bucket index.
   */
  fun toBucketIndex(arrayIndex: Int): Int = BUCKET_MAX - arrayIndex

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
