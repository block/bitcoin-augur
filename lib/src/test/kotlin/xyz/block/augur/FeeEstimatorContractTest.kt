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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [FeeEstimator]: what it rejects, and what it reports when it cannot answer.
 *
 * Each case here covers a way the estimator previously returned a plausible but wrong number
 * instead of declining to answer.
 */
class FeeEstimatorContractTest {
  private val snapshots = TestUtils.createSnapshotSequence(blockCount = 5, snapshotsPerBlock = 3)

  @Test
  fun `an unanswerable confidence level reports null rather than the minimum fee rate`() {
    // 1 - exp(-1) = 0.632 is the chance that any block at all is found in one block's time, so no
    // fee rate achieves 90% confidence at a 1 block target. This used to report the lowest bucket,
    // 1 sat/vB, which reads as a real and extremely cheap recommendation.
    val estimator = FeeEstimator(probabilities = listOf(0.9), blockTargets = listOf(1.0))

    assertNull(estimator.calculateEstimates(snapshots).getFeeRate(1, 0.9))
  }

  @Test
  fun `an unanswerable short target does not clamp the longer targets in its column`() {
    // Monotonicity walks targets in ascending order and clamps each rate to the previous one. When
    // the 1 block cell collapsed to 1 sat/vB, every longer target inherited that bound and the
    // whole column read 1 sat/vB.
    val estimator =
      FeeEstimator(probabilities = listOf(0.9), blockTargets = listOf(1.0, 6.0, 24.0, 144.0))
    val estimate = estimator.calculateEstimates(snapshots)

    assertNull(estimate.getFeeRate(1, 0.9))
    listOf(6, 24, 144).forEach { target ->
      val feeRate = assertNotNull(estimate.getFeeRate(target, 0.9), "target=$target should be answerable")
      assertTrue(feeRate > 1.0, "target=$target collapsed to the minimum fee rate: $feeRate")
    }
  }

  @Test
  fun `raising maxFeeRate does not turn an unanswerable cell into a huge fee rate`() {
    // Unanswerable cells used to be flagged with an out-of-range bucket index whose fee rate,
    // 22247.84 sat/vB, only failed the output filter because it happened to sit above the default
    // maxFeeRate. Raising the filter exposed it as a real estimate.
    val estimator =
      FeeEstimator(
        probabilities = listOf(0.9),
        blockTargets = listOf(1.0),
        maxFeeRate = 1_000_000.0,
      )

    assertNull(estimator.calculateEstimates(snapshots).getFeeRate(1, 0.9))
  }

  @Test
  fun `unsorted block targets give the same estimates as sorted ones`() {
    // Monotonicity assumes ascending targets. With an unsorted list the longest target was visited
    // first and became the bound for every shorter one.
    val ascending = FeeEstimator(blockTargets = listOf(3.0, 12.0, 144.0))
    val shuffled = FeeEstimator(blockTargets = listOf(144.0, 3.0, 12.0))

    assertEquals(
      ascending.calculateEstimates(snapshots).estimates,
      shuffled.calculateEstimates(snapshots).estimates,
    )
  }

  @Test
  fun `estimates stay monotonic past the long term window`() {
    // The short/long blend ramp used to be a parabola that turned back down after the window: the
    // long-term weight hit 0 again at 288 blocks and reached -35 at 1008, so the two estimates were
    // extrapolated apart instead of averaged and longer targets could cost more than shorter ones.
    val targets = listOf(144.0, 288.0, 576.0, 1008.0)
    val estimate =
      FeeEstimator(probabilities = listOf(0.5), blockTargets = targets)
        .calculateEstimates(TestUtils.createSnapshotSequence(blockCount = 144, snapshotsPerBlock = 3))

    var previous = Double.MAX_VALUE
    targets.forEach { target ->
      val feeRate = assertNotNull(estimate.getFeeRate(target.toInt(), 0.5), "target=$target")
      assertTrue(feeRate <= previous, "target=$target cost $feeRate, more than the shorter target's $previous")
      previous = feeRate
    }
  }

  @Test
  fun `snapshots at distinct block heights report no estimates instead of a table of nulls`() {
    // Inflow is the difference between two snapshots at the same height. One snapshot per height is
    // the normal state for a per-block collector, and it left the inflow window spanning zero time:
    // the division produced Infinity, then NaN, and every cell in the table came back null with no
    // indication of why.
    val start = Instant.now()
    val oncePerBlock =
      (0 until 6).map { i ->
        TestUtils.createSnapshot(
          blockHeight = 100 + i,
          timestamp = start.plusSeconds(600L * i),
          transactions = listOf(TestUtils.createTransaction(feeRate = 50.0, weight = 4_000_000)),
        )
      }

    val estimate = FeeEstimator().calculateEstimates(oncePerBlock)

    assertTrue(estimate.estimates.isEmpty(), "expected no estimates, got ${estimate.estimates}")
    assertEquals(oncePerBlock.last().timestamp, estimate.timestamp)
  }

  @Test
  fun `getNearestBlockTarget breaks ties toward the smaller target`() {
    // Previously the winner of an exact tie was whichever key the map yielded first.
    val estimate =
      FeeEstimate(
        estimates = mapOf(
          6 to BlockTarget(6, mapOf(0.5 to 20.0)),
          10 to BlockTarget(10, mapOf(0.5 to 10.0)),
        ),
        timestamp = Instant.now(),
      )

    assertEquals(6, estimate.getNearestBlockTarget(8))
    assertEquals(6, estimate.getNearestBlockTarget(7))
    assertEquals(10, estimate.getNearestBlockTarget(9))
    // Extreme inputs must not overflow the distance comparison.
    assertEquals(6, estimate.getNearestBlockTarget(Int.MIN_VALUE))
    assertEquals(10, estimate.getNearestBlockTarget(Int.MAX_VALUE))
  }
}
