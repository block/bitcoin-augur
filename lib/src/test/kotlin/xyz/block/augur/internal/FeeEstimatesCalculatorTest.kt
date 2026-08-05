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

import org.jetbrains.bio.viktor.F64Array
import org.junit.jupiter.api.Test
import xyz.block.augur.MempoolSnapshot
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeeEstimatesCalculatorTest {
  private val blockTargets = listOf(3.0, 12.0, 144.0)
  private val probabilities = listOf(0.5, 0.95)
  private val defaultLayout = BucketLayout.DEFAULT

  private val calculator =
    FeeEstimatesCalculator(
      probabilities,
      blockTargets,
    )

  @Test
  fun `test mineBlock removes weights from highest fee buckets first`() {
    val weights = F64Array(5) { 1000.0 }
    val blockSize = 2500.0

    val remainingWeights = calculator.mineBlock(weights, blockSize)

    // First two and half buckets should be mined
    assertEquals(0.0, remainingWeights[0])
    assertEquals(0.0, remainingWeights[1])
    assertEquals(500.0, remainingWeights[2])
    assertEquals(1000.0, remainingWeights[3])
    assertEquals(1000.0, remainingWeights[4])
  }

  @Test
  fun `test findBestIndex when all weights are mined`() {
    val weights = F64Array(5) { 0.0 }
    assertEquals(defaultLayout.bucketMin, calculator.findBestIndex(weights))
  }

  @Test
  fun `test findBestIndex returns null when no weights are fully mined`() {
    val weights = F64Array(5) { 1000.0 }
    // Null rather than bucketMax + 1: an out-of-range bucket index is only distinguishable from a
    // real one by remembering to range-check it, and one caller did not.
    assertNull(calculator.findBestIndex(weights))
  }

  @Test
  fun `test findBestIndex with partially mined weights`() {
    val weights = F64Array(5)
    weights[0] = 0.0 // fully mined
    weights[1] = 0.0 // fully mined
    weights[2] = 500.0 // partially mined
    weights[3] = 1000.0 // unmined
    weights[4] = 1000.0 // unmined

    // Should return defaultLayout.bucketMax - 1 since index 1 is the last fully mined bucket
    assertEquals(defaultLayout.bucketMax - 1, calculator.findBestIndex(weights))
  }

  @Test
  fun `test runSimulation with simple case`() {
    val initialWeights = F64Array(5) { 1000.0 }
    val addedWeights = F64Array(5) { 100.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 2,
        meanBlocks = 2,
        blockSize = 2500.0,
      )

    // With these parameters, we expect some buckets to be fully mined
    assertNotNull(result)
    assertTrue(result < defaultLayout.bucketMax)
  }

  @Test
  fun `test runSimulation with zero expected blocks`() {
    val initialWeights = F64Array(5) { 1000.0 }
    val addedWeights = F64Array(5) { 100.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 0,
        meanBlocks = 2,
        blockSize = 2500.0,
      )

    assertNull(result)
  }

  @Test
  fun `test mineBlock handles block size larger than total weights`() {
    val weights = F64Array(5) { 1000.0 }
    val blockSize = 6000.0

    val remainingWeights = calculator.mineBlock(weights, blockSize)

    // All buckets should be fully mined
    assertEquals(0.0, remainingWeights[0])
    assertEquals(0.0, remainingWeights[1])
    assertEquals(0.0, remainingWeights[2])
    assertEquals(0.0, remainingWeights[3])
    assertEquals(0.0, remainingWeights[4])
  }

  @Test
  fun `test mineBlock handles block size smaller than any weight`() {
    val weights = F64Array(5) { 1000.0 }
    val blockSize = 500.0

    val remainingWeights = calculator.mineBlock(weights, blockSize)

    // Only first bucket should be partially mined
    assertEquals(500.0, remainingWeights[0])
    assertEquals(1000.0, remainingWeights[1])
    assertEquals(1000.0, remainingWeights[2])
    assertEquals(1000.0, remainingWeights[3])
    assertEquals(1000.0, remainingWeights[4])
  }

  @Test
  fun `test findBestIndex when last bucket is mined`() {
    val weights = F64Array(5)
    weights[0] = 0.0
    weights[1] = 1000.0
    weights[2] = 1000.0
    weights[3] = 1000.0
    weights[4] = 1000.0

    assertEquals(defaultLayout.bucketMax, calculator.findBestIndex(weights))
  }

  @Test
  fun `test runSimulation with large block size`() {
    val initialWeights = F64Array(5) { 1000.0 }
    val addedWeights = F64Array(5) { 100.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 2,
        meanBlocks = 2,
        blockSize = 6000.0,
      )

    // With such a large block size, all buckets should be mined
    assertEquals(defaultLayout.bucketMin, result)
  }

  @Test
  fun `test runSimulation with intermediate mining case`() {
    val initialWeights = F64Array(5) { 4.0 }
    val addedWeights = F64Array(5) { 4.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 2,
        meanBlocks = 2,
        blockSize = 12.0,
      )

    // After first block: [0, 4, 8, 8, 8]
    // Add weights: [4, 8, 12, 12, 12]
    // After second block: [0, 0, 12, 12, 12]
    // Last fully mined bucket is index 1
    assertEquals(defaultLayout.bucketMax - 1, result)
  }

  @Test
  fun `test runSimulation returns minimum fee bucket when all buckets are mined`() {
    val initialWeights = F64Array(5) { 4.0 }
    val addedWeights = F64Array(5) { 4.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 3,
        meanBlocks = 3,
        blockSize = 100.0,
      )

    assertEquals(defaultLayout.bucketMin, result)
  }

  @Test
  fun `test near-minimum fee bucket never emits sub 0_1 sat per vB`() {
    val lowFeeLayout = BucketLayout(0.1)
    val lowFeeCalculator = FeeEstimatesCalculator(probabilities, blockTargets, lowFeeLayout)

    val nearMinimumFeeRate = 0.0998
    val bucketIndex = (ln(nearMinimumFeeRate) * 100).roundToInt()
    assertEquals(lowFeeLayout.bucketMin, bucketIndex)

    val snapshot =
      MempoolSnapshot(
        blockHeight = 800_000,
        timestamp = Instant.EPOCH,
        bucketedWeights = mapOf(bucketIndex to 4_000_000L),
      )

    val mempoolBuckets = MempoolSnapshotF64Array.fromMempoolSnapshot(snapshot, lowFeeLayout).buckets
    val zeroInflows = F64Array(lowFeeLayout.arraySize) { 0.0 }

    val estimates =
      lowFeeCalculator.getFeeEstimates(
        mempoolBuckets,
        zeroInflows,
        zeroInflows.copy(),
      )

    val expectedFeeRate = exp(bucketIndex.toDouble() / 100.0)
    estimates.forEachIndexed { blockIdx, row ->
      row.forEachIndexed { probIdx, fee ->
        val actual = requireNotNull(fee)
        assertTrue(actual >= 0.1, "estimate[$blockIdx][$probIdx] should be >= 0.1 sat/vB")
        assertEquals(
          expectedFeeRate,
          actual,
          1e-12,
          "estimate[$blockIdx][$probIdx] should match the rounded bucket fee rate",
        )
      }
    }
  }

  @Test
  fun `test runSimulation returns null when no buckets fully mined`() {
    val initialWeights = F64Array(5) { 4.0 }
    val addedWeights = F64Array(5) { 4.0 }

    val result =
      calculator.runSimulation(
        initialWeights = initialWeights,
        addedWeights = addedWeights,
        expectedBlocks = 3,
        meanBlocks = 3,
        blockSize = 1.0,
      )

    assertNull(result, "no bucket was fully mined, so there is no estimate to report")
  }

  @Test
  fun `test getExpectedBlocksMined returns valid blocks`() {
    // With probabilities [0.5, 0.8, 0.9] and meanBlocks [1.0, 2.0, 3.0]
    // we expect the following blocks to be mined:
    // 50% confidence: at least 1 block in period 1, 2 blocks in period 2, 3 blocks in period 3
    // 80% confidence: slightly fewer blocks
    // 90% confidence: even fewer blocks

    val expectedBlocks = F64Array(blockTargets.size, probabilities.size)
    expectedBlocks.V[0] = F64Array.of(3.0, 1.0)
    expectedBlocks.V[1] = F64Array.of(12.0, 7.0)
    expectedBlocks.V[2] = F64Array.of(144.0, 125.0)
    val result = calculator.getExpectedBlocksMined()

    assertEquals(expectedBlocks, result)
  }

  @Test
  fun `test getWeightedEstimates returns estimates where the 144 block estimate equals longEstimate`() {
    val shortEstimates = F64Array.full(3, 2, init = 1.0)
    val longEstimates = F64Array.full(3, 2, init = 100.0)
    val expectedWeightedEstimates = F64Array(3, 2)
    expectedWeightedEstimates.V[0] = F64Array.of(5.082031250000005, 5.082031250000005)
    expectedWeightedEstimates.V[1] = F64Array.of(16.81250000000001, 16.81250000000001)
    expectedWeightedEstimates.V[2] = F64Array.of(100.0, 100.0) // same as longEstimate

    val result = calculator.getWeightedEstimates(shortEstimates, longEstimates)

    assertEquals(expectedWeightedEstimates, result)
  }

  @Test
  fun `test getWeightedEstimates returns same as the short and long if all estimates are the same`() {
    val shortEstimates = F64Array.full(3, 2, init = 100.0)
    val longEstimates = F64Array.full(3, 2, init = 100.0)
    val expectedWeightedEstimates = F64Array.full(3, 2, init = 100.0)

    val result = calculator.getWeightedEstimates(shortEstimates, longEstimates)

    assertEquals(expectedWeightedEstimates, result)
  }

  @Test
  fun `test getFeeEstimates filters estimates above maxFeeRate`() {
    // maxFeeRate = 1.5 means only estimates at exp(0/100) = 1.0 sat/vB (bucket 0) pass the filter.
    // Estimates at bucket 1 (exp(0.01) ≈ 1.01) and above would be filtered IF they appear.
    // We compare results with and without the filter to verify the output filtering.
    val calcFiltered = FeeEstimatesCalculator(probabilities, blockTargets, BucketLayout.DEFAULT, maxFeeRate = 1.5)
    val calcUnfiltered = FeeEstimatesCalculator(probabilities, blockTargets, BucketLayout.DEFAULT, maxFeeRate = 100000.0)

    // Spread heavy weight across all buckets so the simulation can't mine through them,
    // producing estimates at high fee rates that exceed the filter
    val weights = F64Array(defaultLayout.arraySize) { 4_000_000.0 }
    val zeroInflows = F64Array(defaultLayout.arraySize) { 0.0 }

    val filtered = calcFiltered.getFeeEstimates(weights, zeroInflows, zeroInflows.copy())
    val unfiltered = calcUnfiltered.getFeeEstimates(weights, zeroInflows, zeroInflows.copy())

    // Verify that the filtered calculator nulls out estimates that exceed maxFeeRate
    var foundFiltered = false
    for (i in filtered.indices) {
      for (j in filtered[i].indices) {
        val f = filtered[i][j]
        val u = unfiltered[i][j]
        if (f == null && u != null) {
          // This estimate was filtered because it exceeds maxFeeRate=1.5
          assertTrue(u > 1.5, "Filtered estimate should have been above maxFeeRate")
          foundFiltered = true
        }
        if (f != null) {
          assertTrue(f <= 1.5, "Non-null filtered estimate $f should be <= maxFeeRate 1.5")
        }
      }
    }
    assertTrue(foundFiltered, "At least one estimate should have been filtered by maxFeeRate")
  }

  @Test
  fun `test getFeeEstimates preserves estimates at or below maxFeeRate`() {
    // Use a maxFeeRate above the simulation ceiling's fee rate
    val calc = FeeEstimatesCalculator(probabilities, blockTargets, BucketLayout.DEFAULT, maxFeeRate = 25000.0)

    // Put all weight in bucket 1000 (≈ 22026 sat/vB), which is below 25000
    val weights = F64Array(defaultLayout.arraySize) { 0.0 }
    weights[0] = 4_000_000.0

    val zeroInflows = F64Array(defaultLayout.arraySize) { 0.0 }
    val estimates = calc.getFeeEstimates(weights, zeroInflows, zeroInflows.copy())

    // exp(1000/100) ≈ 22026 < 25000, so estimates should be non-null
    estimates.forEach { row ->
      row.forEach { fee ->
        assertNotNull(fee, "Estimate at or below maxFeeRate should not be null")
      }
    }
  }

  @Test
  fun `test getFeeEstimates includes estimate exactly equal to maxFeeRate`() {
    // Put all weight in a single bucket so the simulation produces a known fee rate.
    // Bucket 0 corresponds to exp(0/100) = 1.0 sat/vB exactly.
    val bucketIndex = 0
    val exactFeeRate = kotlin.math.exp(bucketIndex.toDouble() / 100) // 1.0

    val calc = FeeEstimatesCalculator(probabilities, blockTargets, BucketLayout.DEFAULT, maxFeeRate = exactFeeRate)

    val weights = F64Array(defaultLayout.arraySize) { 0.0 }
    weights[defaultLayout.toArrayIndex(bucketIndex)] = 4_000_000.0

    val zeroInflows = F64Array(defaultLayout.arraySize) { 0.0 }
    val estimates = calc.getFeeEstimates(weights, zeroInflows, zeroInflows.copy())

    // The estimate equals maxFeeRate exactly — the <= filter should preserve it, not null it out
    estimates.forEach { row ->
      row.forEach { fee ->
        assertNotNull(fee, "Estimate exactly equal to maxFeeRate ($exactFeeRate) should not be null")
        assertEquals(exactFeeRate, fee, "Estimate should be exactly $exactFeeRate")
      }
    }
  }

  @Test
  fun `test mineBlock does not gain capacity from a negative bucket weight`() {
    val weights = F64Array(4) { 1000.0 }
    weights[1] = -10_000.0

    val remaining = calculator.mineBlock(weights, blockSize = 1500.0)

    // Capacity is 1500: bucket 0 takes 1000, bucket 1 has nothing minable, bucket 2 takes the
    // last 500 and bucket 3 is untouched. Treating -10000 as 10000 units of freed capacity used to
    // let one block clear the rest of the ladder, so the whole mempool looked clearable at the
    // minimum fee rate.
    assertEquals(0.0, remaining[0])
    assertEquals(-10_000.0, remaining[1])
    assertEquals(500.0, remaining[2])
    assertEquals(1000.0, remaining[3])
  }

  @Test
  fun `test enforceMonotonicity leaves unavailable cells out of the running bound`() {
    // NaN marks a cell no fee rate can satisfy. It must not become the bound for the rest of the
    // column: comparisons against NaN are always false, so the bound silently stuck at NaN and
    // every longer target was overwritten with it.
    val feeRates = F64Array(3, 1)
    feeRates[0, 0] = Double.NaN
    feeRates[1, 0] = 50.0
    feeRates[2, 0] = 40.0

    val result = calculator.enforceMonotonicity(feeRates)

    assertTrue(result[0, 0].isNaN(), "the unanswerable cell should stay unanswerable")
    assertEquals(50.0, result[1, 0])
    assertEquals(40.0, result[2, 0])
  }

  @Test
  fun `test getWeightedEstimates saturates the long term weight past the window`() {
    // The ramp 1 - (1 - t/window)^2 peaks at the window and then falls away: without saturation the
    // long-term weight returns to 0 at twice the window and goes negative beyond, so the two
    // estimates were extrapolated apart rather than blended.
    val targets = listOf(144.0, 288.0, 1008.0)
    val calc = FeeEstimatesCalculator(listOf(0.5), targets, BucketLayout.DEFAULT)

    val short = F64Array(targets.size, 1)
    val long = F64Array(targets.size, 1)
    for (i in targets.indices) {
      short[i, 0] = 100.0
      long[i, 0] = 900.0
    }

    val weighted = calc.getWeightedEstimates(short, long)

    // At and past the window every target is the pure long-term estimate.
    targets.indices.forEach { i ->
      assertEquals(900.0, weighted[i, 0], 1e-9, "target=${targets[i]} should be the long-term estimate")
    }
  }

  @Test
  fun `test getWeightedEstimates ramp reaches the long term estimate at the configured window`() {
    // The window used to be hardcoded to 144 blocks no matter how FeeEstimator was configured, so a
    // caller with a 12 hour long-term window still had the ramp stretched over 24 hours' worth.
    val targets = listOf(72.0, 144.0)
    val short = F64Array(targets.size, 1)
    val long = F64Array(targets.size, 1)
    for (i in targets.indices) {
      short[i, 0] = 100.0
      long[i, 0] = 900.0
    }

    // 12 hour window: the ramp is complete at 72 blocks.
    val twelveHour =
      FeeEstimatesCalculator(listOf(0.5), targets, BucketLayout.DEFAULT, longTermWindowBlocks = 72.0)
        .getWeightedEstimates(short, long)
    assertEquals(900.0, twelveHour[0, 0], 1e-9)

    // 24 hour window: 72 blocks is only halfway, so the blend is 3/4 long-term.
    val twentyFourHour =
      FeeEstimatesCalculator(listOf(0.5), targets, BucketLayout.DEFAULT, longTermWindowBlocks = 144.0)
        .getWeightedEstimates(short, long)
    assertEquals(700.0, twentyFourHour[0, 0], 1e-9)
    assertEquals(900.0, twentyFourHour[1, 0], 1e-9)
  }
}
