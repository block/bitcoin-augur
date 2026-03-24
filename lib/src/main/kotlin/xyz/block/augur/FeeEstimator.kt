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

package xyz.block.augur

import xyz.block.augur.internal.BucketLayout
import xyz.block.augur.internal.FeeEstimatesCalculator
import xyz.block.augur.internal.InflowCalculator
import xyz.block.augur.internal.MempoolSnapshotF64Array
import java.time.Duration
import java.time.Instant

/**
 * The main entry point for calculating Bitcoin fee estimates.
 *
 * [FeeEstimator] analyzes historical mempool data to predict transaction confirmation
 * times with various confidence levels.
 *
 * Example usage:
 * ```
 * // Initialize with default settings
 * val estimator = FeeEstimator()
 *
 * // Calculate estimates from historical snapshots
 * val estimate = estimator.calculateEstimates(mempoolSnapshots)
 *
 * // Get fee rate for 6 blocks with 95% confidence
 * val feeRate = estimate.getFeeRate(targetBlocks = 6, probability = 0.95)
 * ```
 *
 * @property probabilities The confidence levels to calculate (default: 5%, 20%, 50%, 80%, 95%)
 * @property blockTargets The block confirmation targets to estimate for (default: 3, 6, 9, 12, 18, 24, 36, 48, 72, 96, 144)
 * @property minFeeRate The minimum fee rate in sat/vB for the simulation lower bound (default: 1.0).
 *   Set to 0.1 for Bitcoin Core 29.1/30.0+ nodes that support sub-1 sat/vB fee rates. Snapshots
 *   store all bucketed transactions regardless of this value; `minFeeRate` controls which buckets
 *   are included when the snapshot is converted to the internal simulation array. Transactions whose
 *   bucket index falls below `ceil(ln(minFeeRate) * 100)` are excluded from the simulation. Note:
 *   because per-transaction bucketing uses `round()` while the layout boundary uses `ceil()`, a
 *   transaction at exactly `minFeeRate` may round to a bucket just below the boundary for some
 *   values; this does not affect the two standard values (1.0 and 0.1) where `ceil` and `round`
 *   agree.
 * @property maxFeeRate The maximum fee rate in sat/vB for reporting (default: 22027.0).
 *   Fee estimates whose fee rate exceeds this bound are returned as null. This is an output filter
 *   only — the internal simulation always models the full fee rate space regardless of this value.
 */
public class FeeEstimator @JvmOverloads public constructor(
  private val probabilities: List<Double> = DEFAULT_PROBABILITIES,
  private val blockTargets: List<Double> = DEFAULT_BLOCK_TARGETS,
  private val shortTermWindowDuration: Duration = Duration.ofMinutes(30),
  private val longTermWindowDuration: Duration = Duration.ofHours(24),
  private val minFeeRate: Double = DEFAULT_MIN_FEE_RATE,
  private val maxFeeRate: Double = DEFAULT_MAX_FEE_RATE,
) {
  private val bucketLayout: BucketLayout
  private val feeEstimatesCalculator: FeeEstimatesCalculator

  init {
    require(probabilities.isNotEmpty()) { "At least one probability level must be provided" }
    require(blockTargets.isNotEmpty()) { "At least one block target must be provided" }
    require(probabilities.all { it in 0.0..1.0 }) { "All probabilities must be between 0.0 and 1.0" }
    require(blockTargets.all { it > 0 }) { "All block targets must be positive" }
    require(maxFeeRate > 0.0) { "maxFeeRate must be positive, was $maxFeeRate" }
    bucketLayout = BucketLayout(minFeeRate)
    feeEstimatesCalculator = FeeEstimatesCalculator(probabilities, blockTargets, bucketLayout, maxFeeRate)
  }

  /**
   * Calculates fee estimates based on historical mempool snapshots.
   *
   * This method analyzes the provided mempool snapshots to generate fee estimates
   * for each block target and confidence level.
   *
   * @param mempoolSnapshots A list of historical mempool snapshots, ideally covering
   *                        at least the past 24 hours.
   * @param numOfBlocks Optional specific block target to estimate for.
   *                       If null or not positive, uses all default block targets.
   * @return A [FeeEstimate] object containing the calculated estimates.
   */
  public fun calculateEstimates(mempoolSnapshots: List<MempoolSnapshot>, numOfBlocks: Double? = null): FeeEstimate {
    // If numOfBlocks is specified then it needs to be at least 3,
    // since we can't simulate partial blocks being mined
    require(numOfBlocks == null || numOfBlocks >= 3.0) { "numOfBlocks must be at least 3 if specified" }

    if (mempoolSnapshots.isEmpty()) {
      return FeeEstimate(emptyMap(), Instant.now())
    }

    // Sort the snapshots by timestamp to ensure chronological order
    val orderedSnapshots = mempoolSnapshots.sortedBy { it.timestamp }
    val simdSnapshots = orderedSnapshots.map { MempoolSnapshotF64Array.fromMempoolSnapshot(it, bucketLayout) }

    // Extract latest mempool weights and calculate inflow rates
    val latestMempoolWeights = simdSnapshots.last().buckets
    val shortTermInflows = InflowCalculator.calculateInflows(simdSnapshots, shortTermWindowDuration, bucketLayout)
    val longTermInflows = InflowCalculator.calculateInflows(simdSnapshots, longTermWindowDuration, bucketLayout)

    val (calculator, targets) = if (numOfBlocks != null) {
      FeeEstimatesCalculator(probabilities, listOf(numOfBlocks), bucketLayout, maxFeeRate) to listOf(numOfBlocks)
    } else {
      feeEstimatesCalculator to blockTargets
    }

    // Calculate fee estimates using the core algorithm
    val feeMatrix = calculator.getFeeEstimates(
      latestMempoolWeights,
      shortTermInflows,
      longTermInflows,
    )
    return convertToFeeEstimate(feeMatrix, orderedSnapshots.last().timestamp, targets)
  }

  /**
   * Creates a new [FeeEstimator] with modified settings.
   *
   * @param probabilities New confidence levels (null to keep current)
   * @param blockTargets New block targets (null to keep current)
   * @param shortTermWindowDuration New short-term window duration (null to keep current)
   * @param longTermWindowDuration New long-term window duration (null to keep current)
   * @param minFeeRate New minimum fee rate in sat/vB (null to keep current)
   * @param maxFeeRate New maximum fee rate in sat/vB (null to keep current)
   * @return A new [FeeEstimator] instance with the specified settings
   */
  public fun configure(
    probabilities: List<Double>? = null,
    blockTargets: List<Double>? = null,
    shortTermWindowDuration: Duration? = null,
    longTermWindowDuration: Duration? = null,
    minFeeRate: Double? = null,
    maxFeeRate: Double? = null,
  ): FeeEstimator = FeeEstimator(
    probabilities = probabilities ?: this.probabilities,
    blockTargets = blockTargets ?: this.blockTargets,
    shortTermWindowDuration = shortTermWindowDuration ?: this.shortTermWindowDuration,
    longTermWindowDuration = longTermWindowDuration ?: this.longTermWindowDuration,
    minFeeRate = minFeeRate ?: this.minFeeRate,
    maxFeeRate = maxFeeRate ?: this.maxFeeRate,
  )

  /**
   * Converts the raw fee matrix to a structured [FeeEstimate] object.
   */
  private fun convertToFeeEstimate(
    feeMatrix: Array<Array<Double?>>,
    timestamp: Instant,
    targets: List<Double> = blockTargets
  ): FeeEstimate {
    val estimates = buildMap {
      targets.forEachIndexed { blockIndex, meanBlocks ->
        val blockTarget = BlockTarget(
          blocks = meanBlocks.toInt(),
          probabilities = buildMap {
            probabilities.forEachIndexed { probIndex, prob ->
              feeMatrix[blockIndex][probIndex]?.let { feeRate ->
                put(prob, feeRate)
              }
            }
          }
        )
        put(meanBlocks.toInt(), blockTarget)
      }
    }

    return FeeEstimate(estimates, timestamp)
  }

  public companion object {
    /**
     * Default block targets for fee estimation (3, 6, 9, 12, 18, 24, 36, 48, 72, 96, 144 blocks).
     */
    public val DEFAULT_BLOCK_TARGETS: List<Double> =
      listOf(3.0, 6.0, 9.0, 12.0, 18.0, 24.0, 36.0, 48.0, 72.0, 96.0, 144.0)

    /**
     * Default confidence levels for fee estimation (5%, 20%, 50%, 80%, 95%).
     */
    public val DEFAULT_PROBABILITIES: List<Double> = listOf(0.05, 0.20, 0.50, 0.80, 0.95)

    /**
     * Default minimum fee rate in sat/vB. Set to 0.1 for Bitcoin Core 29.1/30.0+ nodes.
     */
    public val DEFAULT_MIN_FEE_RATE: Double = BucketLayout.DEFAULT_MIN_FEE_RATE

    /**
     * Default maximum fee rate in sat/vB for reporting. Estimates above this value are
     * returned as null. Rounded up from exp(10) ≈ 22026.47 so that estimates at the
     * simulation ceiling (bucket 1000) pass the filter.
     */
    public val DEFAULT_MAX_FEE_RATE: Double = FeeEstimatesCalculator.DEFAULT_MAX_FEE_RATE
  }
}
