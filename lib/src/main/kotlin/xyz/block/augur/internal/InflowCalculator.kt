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
import java.time.Duration

/**
 * Calculates transaction inflow rates for different fee rate buckets.
 *
 * This is used to simulate new transactions entering the mempool
 * during the time period being estimated.
 */
internal object InflowCalculator {
  private val TEN_MINUTES: Duration = Duration.ofMinutes(10)

  /**
   * Calculates inflow rates based on historical snapshots.
   *
   * @param mempoolSnapshots List of mempool snapshots
   * @param timeframe Duration to consider for inflow calculation
   * @return Array of inflow rates by fee rate bucket, normalized to ten minutes. All zero when the
   *         snapshots span no measurable time, since inflow is only observable between two
   *         snapshots taken at the same block height.
   */
  fun calculateInflows(
    mempoolSnapshots: List<MempoolSnapshotF64Array>,
    timeframe: Duration,
    bucketLayout: BucketLayout = BucketLayout.DEFAULT,
  ): F64Array {
    if (mempoolSnapshots.isEmpty()) return F64Array(bucketLayout.arraySize)

    // First sort the snapshots by timestamp
    val orderedSnapshots = mempoolSnapshots.sortedBy { it.timestamp }

    val endTime = orderedSnapshots.last().timestamp
    val startTime = endTime - timeframe

    val relevantSnapshots = orderedSnapshots.filter { it.timestamp in startTime..endTime }
    val inflows = F64Array(bucketLayout.arraySize)

    // Group snapshots by block height
    val snapshotsByBlock = relevantSnapshots.groupBy { it.blockHeight }

    // For each block, calculate inflows by comparing first and last snapshot
    var totalTimeSpan = Duration.ZERO
    snapshotsByBlock.forEach { (_, blockSnapshots) ->
      val firstSnapshot = blockSnapshots.first()
      val lastSnapshot = blockSnapshots.last()

      // Add the duration between first and last snapshot of this block
      totalTimeSpan += Duration.between(firstSnapshot.timestamp, lastSnapshot.timestamp)

      // Calculate positive differences (inflows) between buckets
      val delta = lastSnapshot.buckets - firstSnapshot.buckets
      for (i in 0 until delta.length) {
        if (delta[i] < 0) {
          delta[i] = 0.0
        }
      }

      inflows += delta
    }

    // Normalize inflows to 10 minutes, i.e. one block's worth of arrivals.
    //
    // totalTimeSpan is zero whenever no block height carries more than one snapshot -- the ordinary
    // state for a collector polling once per block. Every delta above is then zero too, so there is
    // nothing to normalize. Dividing anyway yielded Infinity, then 0.0 * Infinity = NaN in every
    // bucket, and those NaNs voided the whole fee table with no indication of why.
    if (totalTimeSpan.isZero || totalTimeSpan.isNegative) return F64Array(bucketLayout.arraySize)

    // toMillis rather than seconds, which truncated sub-second spans to zero and divided by zero.
    val normalizationFactor = TEN_MINUTES.toMillis().toDouble() / totalTimeSpan.toMillis()
    inflows *= normalizationFactor

    return inflows
  }
}
