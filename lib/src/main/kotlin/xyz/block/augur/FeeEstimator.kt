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

import xyz.block.augur.internal.FeeEstimatesCalculator
import xyz.block.augur.internal.InflowCalculator
import xyz.block.augur.internal.InternalAugurApi
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
 */
@OptIn(InternalAugurApi::class)
public class FeeEstimator @JvmOverloads public constructor(
  private val probabilities: List<Double> = DEFAULT_PROBABILITIES,
  private val blockTargets: List<Double> = DEFAULT_BLOCK_TARGETS,
  private val shortTermWindowDuration: Duration = Duration.ofMinutes(30),
  private val longTermWindowDuration: Duration = Duration.ofHours(24),
) {
  private val feeEstimatesCalculator = FeeEstimatesCalculator(probabilities, blockTargets)

  init {
    require(probabilities.isNotEmpty()) { "At least one probability level must be provided" }
    require(blockTargets.isNotEmpty()) { "At least one block target must be provided" }
    require(probabilities.all { it in 0.0..1.0 }) { "All probabilities must be between 0.0 and 1.0" }
    require(blockTargets.all { it > 0 }) { "All block targets must be positive" }
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
    val simdSnapshots = orderedSnapshots.map { MempoolSnapshotF64Array.fromMempoolSnapshot(it) }

    // Extract latest mempool weights and calculate inflow rates
    val latestMempoolWeights = simdSnapshots.last().buckets
    val shortTermInflows = InflowCalculator.calculateInflows(simdSnapshots, shortTermWindowDuration)
    val longTermInflows = InflowCalculator.calculateInflows(simdSnapshots, longTermWindowDuration)

    val (calculator, targets) = if (numOfBlocks != null) {
      FeeEstimatesCalculator(probabilities, listOf(numOfBlocks)) to listOf(numOfBlocks)
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
   * @return A new [FeeEstimator] instance with the specified settings
   */
  public fun configure(
    probabilities: List<Double>? = null,
    blockTargets: List<Double>? = null,
    shortTermWindowDuration: Duration? = null,
    longTermWindowDuration: Duration? = null,
  ): FeeEstimator = FeeEstimator(
    probabilities = probabilities ?: this.probabilities,
    blockTargets = blockTargets ?: this.blockTargets,
    shortTermWindowDuration = shortTermWindowDuration ?: this.shortTermWindowDuration,
    longTermWindowDuration = longTermWindowDuration ?: this.longTermWindowDuration,
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
  }
}
