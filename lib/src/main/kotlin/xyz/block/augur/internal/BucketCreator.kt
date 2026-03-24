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
 * Internal simulation array layout derived from the minimum fee rate.
 *
 * The array always extends to a fixed upper bound ([SIMULATION_BUCKET_MAX] = 1000, corresponding
 * to ~22026 sat/vB). The user-facing `maxFeeRate` is applied as an output filter in
 * [FeeEstimatesCalculator.prepareResultArray], not as an array sizing parameter.
 *
 * Uses ceil for [bucketMin] so the lowest bucket never represents a fee rate below [minFeeRate].
 *
 * @property bucketMin Minimum bucket index, computed as ceil(ln(minFeeRate) * 100)
 * @property bucketMax Fixed simulation upper bound (1000)
 * @property arraySize Total number of bucket array slots (bucketMax - bucketMin + 1)
 */
@InternalAugurApi
internal class BucketLayout(
  minFeeRate: Double = DEFAULT_MIN_FEE_RATE,
) {
  val bucketMin: Int
  val bucketMax: Int = SIMULATION_BUCKET_MAX
  val arraySize: Int

  init {
    require(minFeeRate > 0.0) { "minFeeRate must be positive, was $minFeeRate" }
    bucketMin = ceil(ln(minFeeRate) * 100).toInt()
    require(bucketMin <= bucketMax) {
      "minFeeRate ($minFeeRate) is too high: bucketMin ($bucketMin) exceeds simulation ceiling ($bucketMax)"
    }
    arraySize = bucketMax - bucketMin + 1
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

    /**
     * Fixed simulation upper bound. Corresponds to floor(ln(22027) * 100) = 1000,
     * preserving the legacy bucket count.
     */
    internal const val SIMULATION_BUCKET_MAX = 1000

    val DEFAULT = BucketLayout()
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
    bucketLayout: BucketLayout = BucketLayout.DEFAULT,
  ): Map<Int, Long> =
    feeRateWeightPairs
      .groupingBy { calculateBucketIndex(it.getFeeRate(), bucketLayout) }
      .fold(0L) { acc, tx -> acc + tx.weight }
      .toSortedMap()

  /**
   * Calculates bucket index using logarithms, providing more precision in the lower fee levels.
   *
   * Above-max fee rates are clamped to [BucketLayout.bucketMax] (the fixed simulation ceiling)
   * so their block weight is preserved in the highest bucket. Below-min fee rates are intentionally
   * NOT clamped here; they produce indices below [BucketLayout.bucketMin] and are dropped by
   * [MempoolSnapshotF64Array.fromMempoolSnapshot], since sub-relay-minimum transactions
   * should not influence fee estimates.
   */
  private fun calculateBucketIndex(feeRate: Double, bucketLayout: BucketLayout): Int = min(
    // round() is correct here: each transaction maps to its nearest bucket.
    // BucketLayout uses ceil for the lower *boundary* to guarantee the range stays within the
    // user's configured min fee rate, but individual transactions should snap to the
    // closest discrete bucket rather than being biased up or down.
    (round(ln(feeRate) * 100).toInt()),
    bucketLayout.bucketMax,
  )
}
