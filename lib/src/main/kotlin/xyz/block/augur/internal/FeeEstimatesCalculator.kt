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
import xyz.block.augur.internal.BucketCreator.BUCKET_MAX
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Core implementation of the fee estimation algorithm.
 *
 * This class simulates the mining of blocks to predict when transactions
 * with different fee rates would be confirmed.
 */
@InternalAugurApi
internal class FeeEstimatesCalculator(
  private val probabilities: List<Double>,
  private val blockTargets: List<Double>,
) {
  private val expectedBlocksMined by lazy { getExpectedBlocksMined() }

  /**
   * Calculates fee estimates based on mempool snapshot and inflow data.
   *
   * @param mempoolSnapshot Current mempool snapshot represented as an F64Array
   * @param shortIntervalInflows Short-term inflow data (typically 30 minutes)
   * @param longIntervalInflows Long-term inflow data (typically 24 hours)
   * @return A 2D array of fee estimates where each element corresponds to a specific
   *         block target and probability level. Values exceeding the max bucket threshold are null.
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

        // Run individual simulation and store result
        result[blockTargetIndex, probIndex] = runSimulation(
          initialWeights,
          addedWeights,
          expectedBlocks,
          meanBlocks,
        )?.toDouble() ?: 0.0
      }
    }

    return result
  }

  /**
   * Simulates mining blocks and returns the weight index corresponding to the
   * lowest fee rate that would result in the transaction getting mined.
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
      val removedWeight = min(weightsRemaining[i], weightUnitsRemaining)
      weightUnitsRemaining -= removedWeight
      weightsRemaining[i] -= removedWeight
    }
    return weightsRemaining
  }

  /**
   * Find the index of the last bucket that is fully mined.
   */
  internal fun findBestIndex(weightsRemaining: F64Array): Int {
    // The last mined bucket will occur just before the first non-zero remaining weight.
    val index = weightsRemaining.toDoubleArray().indexOfFirst { it != 0.0 } - 1

    // If index = -2, then all weights are zero so we will return a trivial index.
    // If index = -1, then no weights are fully mined so can't determine a sufficiently high rate.
    // Else, createFeeRateBuckets reversed the order, so subtract to recover the original index.
    return when (index) {
      -2 -> 0 // all weights are zero so we can use the cheapest fee rate
      -1 -> BUCKET_MAX + 1 // return null
      else -> BUCKET_MAX - index
    }
  }

  /**
   * Calculates the weighted average of the short and long interval bucket estimates.
   */
  internal fun getWeightedEstimates(
    shortEstimates: F64Array,
    longEstimates: F64Array,
  ): F64Array {
    // The longer estimates are weighted more heavily for longer intervals. For example, the
    // weighted estimate for 24 hours (144 blocks) is exactly equal to the longEstimate.
    val weights = blockTargets.map { 1 - (1 - it / 144.0).pow(2) }
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
   * Converts fee estimates to the final nullable array format and filters fees above the maximum bucket's fee rate.
   * F64Array can't accommodate nulls so we convert to traditional arrays.
   */
  private fun prepareResultArray(feeRates: F64Array): Array<Array<Double?>> {
    // Maximum allowed fee rate based on the BUCKET_MAX constant
    val maxAllowedFeeRate = exp(BUCKET_MAX.toDouble() / 100)

    return Array(feeRates.shape[0]) { blockTargetIndex ->
      Array(feeRates.shape[1]) { probabilityIndex ->
        feeRates[blockTargetIndex, probabilityIndex].takeIf { it < maxAllowedFeeRate }
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
   */
  internal fun enforceMonotonicity(feeRates: F64Array): F64Array {
    val result = feeRates.copy()
    for (j in 0 until result.shape[1]) {
      var prevRate = Double.POSITIVE_INFINITY
      for (i in 0 until result.shape[0]) {
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
  }
}
