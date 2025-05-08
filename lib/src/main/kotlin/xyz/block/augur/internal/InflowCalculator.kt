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
@InternalAugurApi
internal object InflowCalculator {
  /**
   * Calculates inflow rates based on historical snapshots.
   *
   * @param mempoolSnapshots List of mempool snapshots
   * @param timeframe Duration to consider for inflow calculation
   * @return Array of inflow rates by fee rate bucket
   */
  fun calculateInflows(
    mempoolSnapshots: List<MempoolSnapshotF64Array>,
    timeframe: Duration,
  ): F64Array {
    if (mempoolSnapshots.isEmpty()) return F64Array(BucketCreator.BUCKET_MAX + 1)

    // First sort the snapshots by timestamp
    val orderedSnapshots = mempoolSnapshots.sortedBy { it.timestamp }

    val endTime = orderedSnapshots.last().timestamp
    val startTime = endTime - timeframe

    val relevantSnapshots = orderedSnapshots.filter { it.timestamp in startTime..endTime }
    val inflows = F64Array(BucketCreator.BUCKET_MAX + 1)

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

    // Normalize inflows to 10 minutes
    val tenMinutes = Duration.ofMinutes(10)
    val normalizationFactor = tenMinutes.seconds.toDouble() / totalTimeSpan.seconds
    inflows *= normalizationFactor

    return inflows
  }
}
