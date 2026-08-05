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

import org.apache.commons.math3.distribution.PoissonDistribution
import org.jetbrains.bio.viktor.F64Array
import org.jetbrains.bio.viktor.F64Array.Companion.invoke
import kotlin.math.min
import kotlin.math.pow

/**
 * Core implementation of the fee estimation algorithm.
 *
 * This class simulates the mining of blocks to predict when transactions
 * with different fee rates would be confirmed.
 */
internal class FeeEstimatesCalculator(
  private val probabilities: List<Double>,
  private val blockTargets: List<Double>,
  private val bucketLayout: BucketLayout = BucketLayout.DEFAULT,
  private val maxFeeRate: Double = DEFAULT_MAX_FEE_RATE,
  private val longTermWindowBlocks: Double = DEFAULT_LONG_TERM_WINDOW_BLOCKS,
) {
  private val expectedBlocksMined by lazy { getExpectedBlocksMined() }

  /**
   * Calculates fee estimates based on mempool snapshot and inflow data.
   *
   * @param mempoolSnapshot Current mempool snapshot represented as an F64Array
   * @param shortIntervalInflows Short-term inflow data (typically 30 minutes)
   * @param longIntervalInflows Long-term inflow data (typically 24 hours)
   * @return A 2D array of fee estimates where each element corresponds to a specific
   *         block target and probability level. An element is null when it exceeds [maxFeeRate],
   *         or when no fee rate can satisfy that (block target, probability) pair at all.
   */
  fun getFeeEstimates(
    mempoolSnapshot: F64Array,
    shortIntervalInflows: F64Array,
    longIntervalInflows: F64Array,
  ): Array<Array<Double?>> {
    // Add half of short-term inflows as a buffer to current weights
    val currentWeightsWithBuffer = mempoolSnapshot + shortIntervalInflows / 2.0

    // Run simulations for short and long-term intervals
    val shortTermEstimates = runSimulations(
      currentWeightsWithBuffer,
      shortIntervalInflows,
      expectedBlocksMined
    )

    val longTermEstimates = runSimulations(
      currentWeightsWithBuffer,
      longIntervalInflows,
      expectedBlocksMined
    )

    // Combine estimates with appropriate weighting
    val weightedEstimates = getWeightedEstimates(shortTermEstimates, longTermEstimates)

    // Convert bucket indices to actual fee rates
    val feeRates = convertBucketsToFeeRates(weightedEstimates)

    // Ensure fee rates are monotonically decreasing with block targets
    val monotoneFeeRates = enforceMonotonicity(feeRates)

    // Create final result array with values filtered by maximum threshold
    return prepareResultArray(monotoneFeeRates)
  }

  /**
   * Returns a 2d array of expected fee rates.
   */
  private fun runSimulations(
    initialWeights: F64Array,
    addedWeights: F64Array,
    expectedBlocksMined: F64Array,
  ): F64Array {
    val result = F64Array(blockTargets.size, probabilities.size)

    // For each block target and probability combination
    blockTargets.forEachIndexed { blockTargetIndex, blocks ->
      val meanBlocks = blocks.toInt()

      probabilities.indices.forEach { probIndex ->
        val expectedBlocks = expectedBlocksMined[blockTargetIndex, probIndex].toInt()

        // Run individual simulation and store result. NaN marks "no fee rate satisfies this pair"
        // and prepareResultArray turns it into null. Bucket 0 must never stand in for failure: it is
        // a real fee rate (minFeeRate, 1 sat/vB by default), so a caller could not tell a genuine
        // 1 sat/vB recommendation apart from a failed simulation.
        result[blockTargetIndex, probIndex] = runSimulation(
          initialWeights,
          addedWeights,
          expectedBlocks,
          meanBlocks,
        )?.toDouble() ?: Double.NaN
      }
    }

    return result
  }

  /**
   * Simulates mining blocks and returns the weight index corresponding to the
   * lowest fee rate that would result in the transaction getting mined, or null when no
   * simulation is possible (no blocks are expected to be mined at this confidence level) or
   * when even the highest fee rate bucket would not clear.
   */
  internal fun runSimulation(
    initialWeights: F64Array,
    addedWeights: F64Array,
    expectedBlocks: Int,
    meanBlocks: Int,
    blockSize: Double = BLOCK_SIZE_WEIGHT_UNITS.toDouble(),
  ): Int? {
    if (expectedBlocks <= 0) return null

    // If we expect 6 blocks to be mined in the time it usually takes to mine 3 blocks,
    // then we expect it to take 3/6 * (10 mins) = 5 mins to mine one block. Therefore, we
    // should only take 5/10 = 1/2 of the added weights from 10 mins worth of inflow data.
    val expectedMiningTimeFactor = meanBlocks.toDouble() / expectedBlocks

    // Add only a block's worth of added weights from the inflow data
    val addedWeightsInOneBlock = addedWeights * expectedMiningTimeFactor

    // Mine the expected number of blocks and return the remaining weights
    val finalWeights =
      (1..expectedBlocks).fold(initialWeights.copy()) { currentWeights, _ ->
        val updatedWeights = currentWeights + addedWeightsInOneBlock
        mineBlock(updatedWeights, blockSize)
      }

    // Find the index of the last fully mined bucket,
    // corresponding to the lowest fee rate that would get mined.
    return findBestIndex(finalWeights)
  }

  /**
   * Mines a block by removing the lowest fee rate buckets (highest fees)
   * until the block size is reached. Returns the remaining mempool weight.
   */
  internal fun mineBlock(
    currentWeights: F64Array,
    blockSize: Double,
  ): F64Array {
    val weightsRemaining = currentWeights.copy()
    var weightUnitsRemaining = blockSize

    for (i in 0 until weightsRemaining.length) {
      // coerceAtLeast(0.0) stops a negative bucket weight from *adding* to the block's remaining
      // capacity, which would otherwise let a single block mine more than blockSize weight units
      // and make the whole mempool look clearable at the minimum fee rate.
      val removedWeight = min(weightsRemaining[i], weightUnitsRemaining).coerceAtLeast(0.0)
      weightUnitsRemaining -= removedWeight
      weightsRemaining[i] -= removedWeight
    }
    return weightsRemaining
  }

  /**
   * Find the index of the last bucket that is fully mined, or null if no bucket is.
   */
  internal fun findBestIndex(weightsRemaining: F64Array): Int? {
    // The last mined bucket will occur just before the first non-zero remaining weight.
    val index = weightsRemaining.toDoubleArray().indexOfFirst { it != 0.0 } - 1

    // If index = -2, then all weights are zero so we will return the cheapest bucket.
    // If index = -1, then no weights are fully mined so can't determine a sufficiently high rate.
    // Else, createFeeRateBuckets reversed the order, so subtract to recover the original index.
    return when (index) {
      -2 -> bucketLayout.bucketMin // all weights are zero so we can use the cheapest fee rate
      -1 -> null // not even the highest fee rate bucket cleared, so no answer exists
      else -> bucketLayout.toBucketIndex(index)
    }
  }

  /**
   * Calculates the weighted average of the short and long interval bucket estimates.
   */
  internal fun getWeightedEstimates(
    shortEstimates: F64Array,
    longEstimates: F64Array,
  ): F64Array {
    // The longer estimates are weighted more heavily for longer intervals, reaching a pure
    // long-term estimate at the long-term window (144 blocks for the default 24 hours).
    //
    // coerceAtMost(1.0) saturates the ramp past that point. Without it the parabola turns back down,
    // returning to 0 at twice the window and reaching -35 at 1008 blocks, which extrapolates the two
    // estimates apart instead of averaging them.
    val weights = blockTargets.map { 1 - (1 - (it / longTermWindowBlocks).coerceAtMost(1.0)).pow(2) }
    val weightedEstimates = F64Array(shortEstimates.shape[0], shortEstimates.shape[1])

    for (i in 0 until weightedEstimates.shape[0]) {
      weightedEstimates.V[i] = shortEstimates.view(i) * (1.0 - weights[i]) +
        longEstimates.view(i) * weights[i]
    }
    return weightedEstimates
  }

  /**
   * Converts bucket matrix to fee matrix by performing the inverse of the logarithm calculation.
   */
  internal fun convertBucketsToFeeRates(bucketEstimates: F64Array): F64Array = (bucketEstimates / 100.0).exp()

  /**
   * Converts fee estimates to the final nullable array format, dropping cells that no fee rate can
   * satisfy along with any fee above [maxFeeRate].
   * F64Array can't accommodate nulls so we convert to traditional arrays.
   */
  private fun prepareResultArray(feeRates: F64Array): Array<Array<Double?>> {
    return Array(feeRates.shape[0]) { blockTargetIndex ->
      Array(feeRates.shape[1]) { probabilityIndex ->
        // isFinite() drops the NaN markers runSimulations writes for unanswerable cells, and stays
        // independent of maxFeeRate. The old out-of-band bucket sentinel did not: it relied on the
        // gap between exp(1001/100) and DEFAULT_MAX_FEE_RATE, so raising maxFeeRate surfaced
        // 22247.84 sat/vB as a real estimate.
        feeRates[blockTargetIndex, probabilityIndex].takeIf { it.isFinite() && it <= maxFeeRate }
      }
    }
  }

  internal fun getExpectedBlocksMined(): F64Array {
    val blocks = F64Array(blockTargets.size, probabilities.size)

    blockTargets.mapIndexed { i, target ->
      // Store the probabilities of mining x or more blocks
      val poisson = PoissonDistribution(target)
      val trialProbabilities =
        (0 until (target * 4).toInt()).map { x ->
          1.0 - poisson.cumulativeProbability(x - 1)
        }

      // Store the maximum number of blocks that can be mined with probability >= confidence level
      probabilities.forEachIndexed { j, probability ->
        val numBlocks = trialProbabilities.indexOfLast { it >= probability }
        if (numBlocks != -1) {
          blocks[i, j] = numBlocks.toDouble()
        }
      }
    }

    return blocks
  }

  /**
   * Ensures that fee rates decrease (or stay the same) as block targets increase.
   * For each probability, if a fee rate is higher than the previous one,
   * it is set equal to the previous rate.
   *
   * Assumes [blockTargets] is in ascending order; [xyz.block.augur.FeeEstimator] sorts it.
   */
  internal fun enforceMonotonicity(feeRates: F64Array): F64Array {
    val result = feeRates.copy()
    for (j in 0 until result.shape[1]) {
      var prevRate = Double.POSITIVE_INFINITY
      for (i in 0 until result.shape[0]) {
        // Skip unavailable cells, and in particular don't let one become the running bound: a
        // single unanswerable short target would otherwise clamp every longer target in the
        // column down to it, discarding estimates that were computed correctly.
        if (result[i, j].isNaN()) continue
        if (result[i, j] > prevRate) {
          result[i, j] = prevRate
        }
        prevRate = result[i, j]
      }
    }
    return result
  }

  companion object {
    const val BLOCK_SIZE_WEIGHT_UNITS = 4_000_000

    /** Bitcoin's target block interval, used to convert window durations into block counts. */
    const val MINUTES_PER_BLOCK = 10.0

    /** Blocks in the default 24 hour long-term window: 24 * 60 / 10. */
    const val DEFAULT_LONG_TERM_WINDOW_BLOCKS = 144.0

    // Rounded up from exp(10) ≈ 22026.47 so estimates at the simulation ceiling pass the <= filter
    const val DEFAULT_MAX_FEE_RATE = 22027.0
  }
}
