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

import org.junit.jupiter.api.Test
import xyz.block.augur.test.TestUtils
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeeEstimatorTest {
  private val feeEstimator = FeeEstimator()

  @Test
  fun `test empty snapshot list returns null estimates`() {
    val estimate = feeEstimator.calculateEstimates(emptyList())

    FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
      FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
        assertNull(estimate.getFeeRate(target.toInt(), probability))
      }
    }
  }

  @Test
  fun `test single snapshot returns null estimates`() {
    val snapshot = TestUtils.createSnapshotSequence(blockCount = 1)[0]
    val estimate = feeEstimator.calculateEstimates(listOf(snapshot))

    FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
      FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
        assertNull(estimate.getFeeRate(target.toInt(), probability))
      }
    }
  }

  @Test
  fun `test estimates with consistent fee rate increase`() {
    val startTime = Instant.now()
    val snapshots =
      TestUtils.createSnapshotSequence(
        startTime = startTime,
        blockCount = 144, // 1 day hours worth of blocks
        snapshotsPerBlock = 3,
        inflowRateChangeTime = Duration.ofHours(1),
      )

    val estimate = feeEstimator.calculateEstimates(snapshots)

    println(estimate)

    // Check that estimates exist and are reasonable
    FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
      FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        assert(feeRate != null && feeRate > 0.0) {
          "Fee rate should be positive for target=$target, probability=$probability"
        }
      }
    }
  }

  @Test
  fun `test estimates are ordered correctly by probability`() {
    val snapshots =
      TestUtils.createSnapshotSequence(
        blockCount = 5,
        snapshotsPerBlock = 3,
      )

    val estimate = feeEstimator.calculateEstimates(snapshots)

    // For each target, check that fee rates increase with probability
    FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
      var lastFeeRate = 0.0
      FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        if (feeRate != null) {
          assert(feeRate >= lastFeeRate) {
            "Fee rates should increase with probability for target=$target"
          }
          lastFeeRate = feeRate
        }
      }
    }
  }

  @Test
  fun `test estimates are ordered correctly by target blocks`() {
    val snapshots =
      TestUtils.createSnapshotSequence(
        blockCount = 5,
        snapshotsPerBlock = 3,
      )

    val estimate = feeEstimator.calculateEstimates(snapshots)

    // For each probability, check that fee rates decrease with target blocks
    FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
      var lastFeeRate = Double.MAX_VALUE
      FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        if (feeRate != null) {
          assert(feeRate <= lastFeeRate) {
            "Fee rates should decrease with target blocks for probability=$probability"
          }
          lastFeeRate = feeRate
        }
      }
    }
  }

  @Test
  fun `test estimates are ordered correctly by target blocks with higher long term inflows`() {
    // Ensure even though average long-term inflow rates are higher than the short term, our
    // estimates should still decrease with target blocks.
    // This is an improvement from WhatTheFee, where it's possible for a longer target to have
    // a higher fee rate than a shorter target due to the way inflow rates are calculated.

    val snapshots =
      TestUtils.createSnapshotSequence(
        blockCount = 144,
        snapshotsPerBlock = 3,
        shortTermInflowRates = TestUtils.createVeryLowInflowRates(),
        longTermInflowRates = TestUtils.createHighInflowRates(),
      )

    val estimate = feeEstimator.calculateEstimates(snapshots)

    // For each probability, check that fee rates decrease with target blocks
    FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
      var lastFeeRate = Double.MAX_VALUE
      FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        if (feeRate != null) {
          assert(feeRate <= lastFeeRate) {
            "Fee rates should decrease with target blocks for probability=$probability"
          }
          lastFeeRate = feeRate
        }
      }
    }
  }

  @Test
  fun `test estimates with custom probabilities and targets`() {
    val customProbabilities = listOf(0.1, 0.5, 0.9)
    val customTargets = listOf(1.0, 3.0, 6.0)
    val customEstimator =
      FeeEstimator(
        probabilities = customProbabilities,
        blockTargets = customTargets,
      )

    val snapshots =
      TestUtils.createSnapshotSequence(
        blockCount = 5,
        snapshotsPerBlock = 3,
      )

    val estimate = customEstimator.calculateEstimates(snapshots)

    // Verify that estimates exist only for custom probabilities and targets
    customTargets.forEach { target ->
      customProbabilities.forEach { probability ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        assert(feeRate != null && feeRate > 0.0) {
          "Fee rate should exist for custom target=$target, probability=$probability"
        }
      }
    }
  }

  @Test
  fun `test estimates with unordered snapshots`() {
    val startTime = Instant.now()
    val snapshots =
      TestUtils
        .createSnapshotSequence(
          startTime = startTime,
          blockCount = 5,
          snapshotsPerBlock = 3,
        ).shuffled() // Randomize order

    val estimate = feeEstimator.calculateEstimates(snapshots)

    // Verify that estimates still exist and are reasonable
    FeeEstimator.DEFAULT_BLOCK_TARGETS.forEach { target ->
      FeeEstimator.DEFAULT_PROBABILITIES.forEach { probability ->
        val feeRate = estimate.getFeeRate(target.toInt(), probability)
        assert(feeRate != null && feeRate > 0.0) {
          "Fee rate should be positive for target=$target, probability=$probability"
        }
      }
    }
  }

  @Test
  fun `test getNearestBlockTarget`() {
    // Create an estimate with specific targets
    val customTargets = listOf(3.0, 6.0, 24.0, 144.0)
    val customProbabilities = listOf(0.5, 0.9)
    val customEstimator = FeeEstimator(
      probabilities = customProbabilities,
      blockTargets = customTargets,
    )

    val snapshots = TestUtils.createSnapshotSequence(
      blockCount = 5,
      snapshotsPerBlock = 3,
    )
    val estimate = customEstimator.calculateEstimates(snapshots)

    // Test exact matches
    customTargets.forEach { target ->
      val nearestTarget = estimate.getNearestBlockTarget(target.toInt())
      assertEquals(target.toInt(), nearestTarget)
    }

    // Test finding nearest targets
    val testCases = mapOf(
      1 to 3, // Closest to 3
      2 to 3, // Closest to 3
      4 to 3, // Closer to 3 than 6
      5 to 6, // Closer to 6 than 3
      10 to 6, // Closer to 6 than 24
      20 to 24, // Closer to 24 than 6
      50 to 24, // Closer to 24 than 144
      100 to 144, // Closer to 144 than 24
      200 to 144 // Closest to 144
    )

    testCases.forEach { (input, expected) ->
      val nearest = estimate.getNearestBlockTarget(input)
      assertEquals(expected, nearest, "For input $input, expected nearest target to be $expected, but got $nearest")
    }

    // Test with empty estimates
    val emptyEstimate = FeeEstimate(emptyMap(), Instant.now())
    assertNull(emptyEstimate.getNearestBlockTarget(6))
  }

  @Test
  fun `test BlockTarget getFeeRate`() {
    // Create a BlockTarget with specific confidence levels
    val probabilities = mapOf(
      0.2 to 10.0, // 20% confidence = 10 sat/vB
      0.5 to 15.0, // 50% confidence = 15 sat/vB
      0.8 to 20.0 // 80% confidence = 20 sat/vB
    )
    val blockTarget = BlockTarget(blocks = 6, probabilities = probabilities)

    // Test exact matches
    assertEquals(10.0, blockTarget.getFeeRate(0.2))
    assertEquals(15.0, blockTarget.getFeeRate(0.5))
    assertEquals(20.0, blockTarget.getFeeRate(0.8))

    // Test non-exact values should return null
    assertNull(blockTarget.getFeeRate(0.1))
    assertNull(blockTarget.getFeeRate(0.3))
    assertNull(blockTarget.getFeeRate(0.9))

    // Test with empty probabilities
    val emptyBlockTarget = BlockTarget(blocks = 6, probabilities = emptyMap())
    assertNull(emptyBlockTarget.getFeeRate(0.5))
  }

  @Test
  fun `test getAvailableBlockTargets and getAvailableConfidenceLevels`() {
    // Create a custom estimator with specific targets and probabilities
    val targets = listOf(6.0, 3.0, 24.0, 144.0) // Deliberately unordered
    val probabilities = listOf(0.8, 0.2, 0.5) // Deliberately unordered
    val customEstimator = FeeEstimator(
      probabilities = probabilities,
      blockTargets = targets,
    )

    val snapshots = TestUtils.createSnapshotSequence(
      blockCount = 5,
      snapshotsPerBlock = 3,
    )
    val estimate = customEstimator.calculateEstimates(snapshots)

    // Test that available block targets are returned in ascending order
    val availableTargets = estimate.getAvailableBlockTargets()
    assertEquals(listOf(3, 6, 24, 144), availableTargets)

    // Test that available confidence levels are returned in ascending order
    val availableConfidenceLevels = estimate.getAvailableConfidenceLevels()
    assertEquals(listOf(0.2, 0.5, 0.8), availableConfidenceLevels)
  }
}
