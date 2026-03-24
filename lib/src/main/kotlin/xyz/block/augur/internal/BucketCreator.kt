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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

/**
 * Holds bucket boundaries derived from minimum and maximum fee rates.
 *
 * Uses ceil for [bucketMin] so the lowest bucket never represents a fee rate below [minFeeRate].
 * Uses floor for [bucketMax] so the highest bucket never represents a fee rate above [maxFeeRate].
 *
 * @property bucketMin Minimum bucket index, computed as ceil(ln(minFeeRate) * 100)
 * @property bucketMax Maximum bucket index, computed as floor(ln(maxFeeRate) * 100)
 * @property arraySize Total number of bucket array slots (bucketMax - bucketMin + 1)
 */
@InternalAugurApi
internal class BucketConfig(
  minFeeRate: Double = DEFAULT_MIN_FEE_RATE,
  maxFeeRate: Double = DEFAULT_MAX_FEE_RATE,
) {
  val bucketMin: Int
  val bucketMax: Int
  val arraySize: Int

  init {
    require(minFeeRate > 0.0) { "minFeeRate must be positive, was $minFeeRate" }
    require(maxFeeRate > 0.0) { "maxFeeRate must be positive, was $maxFeeRate" }
    require(minFeeRate < maxFeeRate) { "minFeeRate ($minFeeRate) must be less than maxFeeRate ($maxFeeRate)" }
    bucketMin = ceil(ln(minFeeRate) * 100).toInt()
    bucketMax = floor(ln(maxFeeRate) * 100).toInt()
    arraySize = bucketMax - bucketMin + 1
    require(arraySize >= 1) {
      "minFeeRate ($minFeeRate) and maxFeeRate ($maxFeeRate) are too close together: " +
        "discretized bucket range is empty (bucketMin=$bucketMin, bucketMax=$bucketMax). " +
        "Widen the gap between minFeeRate and maxFeeRate."
    }
  }

  /**
   * Converts a bucket index (bucketMin..bucketMax) to the corresponding array position.
   * Buckets are stored in reverse order so that the highest fee rate (bucketMax) is at index 0.
   */
  fun toArrayIndex(bucket: Int): Int = bucketMax - bucket

  /**
   * Converts an array position back to the original bucket index.
   */
  fun toBucketIndex(arrayIndex: Int): Int = bucketMax - arrayIndex

  companion object {
    internal const val DEFAULT_MIN_FEE_RATE = 1.0
    internal const val DEFAULT_MAX_FEE_RATE = 22027.0 // > exp(10) ≈ 22026.47, so floor gives bucket 1000

    val DEFAULT = BucketConfig()
  }
}

/**
 * Utility functions for creating buckets from fee and weight data.
 */
@InternalAugurApi
internal object BucketCreator {
  /**
   * Creates a bucket map from fee and weight pairs where the key is the bucket index
   * and the value is the sum of the weights at that fee rate, normalized to a one block duration.
   */
  fun createFeeRateBuckets(
    feeRateWeightPairs: List<MempoolTransaction>,
    bucketConfig: BucketConfig = BucketConfig.DEFAULT,
  ): Map<Int, Long> =
    feeRateWeightPairs
      .groupingBy { calculateBucketIndex(it.getFeeRate(), bucketConfig) }
      .fold(0L) { acc, tx -> acc + tx.weight }
      .toSortedMap()

  /**
   * Calculates bucket index using logarithms, providing more precision in the lower fee levels.
   *
   * Above-max fee rates are clamped to [BucketConfig.bucketMax] so their block weight is
   * preserved in the highest bucket. Below-min fee rates are intentionally NOT clamped here;
   * they produce indices below [BucketConfig.bucketMin] and are dropped by
   * [MempoolSnapshotF64Array.fromMempoolSnapshot], since sub-relay-minimum transactions
   * should not influence fee estimates.
   */
  private fun calculateBucketIndex(feeRate: Double, bucketConfig: BucketConfig): Int = min(
    // round() is correct here: each transaction maps to its nearest bucket.
    // BucketConfig uses ceil/floor for *boundaries* to guarantee the range stays within the
    // user's configured min/max fee rates, but individual transactions should snap to the
    // closest discrete bucket rather than being biased up or down.
    (round(ln(feeRate) * 100).toInt()),
    bucketConfig.bucketMax,
  )
}
