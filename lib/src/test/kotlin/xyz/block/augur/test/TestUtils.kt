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

package xyz.block.augur.test

import xyz.block.augur.MempoolSnapshot
import xyz.block.augur.MempoolTransaction
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

object TestUtils {
  fun createSnapshot(
    blockHeight: Int,
    timestamp: Instant = Instant.now(),
    transactions: List<MempoolTransaction> = emptyList(),
  ): MempoolSnapshot =
    MempoolSnapshot.fromMempoolTransactions(
      transactions = transactions,
      blockHeight = blockHeight,
      timestamp = timestamp,
    )

  fun createTransaction(
    feeRate: Double,
    weight: Long = 400, // Default 100 vBytes
  ): MempoolTransaction {
    val fee = (feeRate * weight / 4.0).toLong() // Convert fee rate to total fee
    return MempoolTransaction(weight = weight, fee = fee)
  }

  // Default test data generators
  fun createDefaultBaseWeights(): Map<Double, Long> =
    buildMap {
      // Low fee range (0.5 - 4.0 sat/vB)
      for (fee in 1..8) {
        val feeRate = fee * 0.5
        put(feeRate, (500_000L + (Random.nextDouble() * 1_500_000L).toLong()))
      }

      // Medium fee range (4.5 - 16.0 sat/vB)
      for (fee in 9..32) {
        val feeRate = fee * 0.5
        val baseWeight = 2_000_000L + (Random.nextDouble() * 5_000_000L).toLong()
        val weight =
          when (feeRate) {
            5.0 -> baseWeight * 3 // Spike at 5 sat/vB
            8.0 -> baseWeight * 4 // Major spike at 8 sat/vB
            10.0 -> baseWeight * 3 // Spike at 10 sat/vB
            12.0 -> baseWeight * 2 // Smaller spike at 12 sat/vB
            15.0 -> baseWeight * 3 // Spike at 15 sat/vB
            else -> baseWeight
          }
        put(feeRate, weight)
      }

      // High fee range (16.5 - 32.0 sat/vB)
      for (fee in 33..64) {
        val feeRate = fee * 0.5
        val baseWeight = 1_000_000L + (Random.nextDouble() * 3_000_000L).toLong()
        val weight =
          when (feeRate) {
            20.0 -> baseWeight * 3 // Spike at 20 sat/vB
            25.0 -> baseWeight * 4 // Major spike at 25 sat/vB
            30.0 -> baseWeight * 2 // Smaller spike at 30 sat/vB
            else -> baseWeight
          }
        put(feeRate, weight)
      }
    }

  fun createHighInflowRates(): Map<Double, Long> =
    buildMap {
      for (fee in 1..64) {
        val feeRate = fee * 0.5
        val inflowRate =
          when {
            feeRate <= 4.0 -> 20_000L // ~33 WU/s each
            feeRate <= 8.0 -> 40_000L // ~67 WU/s each
            feeRate <= 16.0 -> 80_000L // ~133 WU/s each
            else -> 2_000_000L // ~167 WU/s each
          }
        put(feeRate, inflowRate)
      }
    }

  fun createVeryLowInflowRates(): Map<Double, Long> =
    buildMap {
      for (fee in 1..64) {
        val feeRate = fee * 0.5
        val inflowRate =
          when {
            feeRate <= 4.0 -> 5_000L
            feeRate <= 8.0 -> 10_000L
            feeRate <= 16.0 -> 20_000L
            else -> 25_000L
          }
        put(feeRate, inflowRate)
      }
    }

  fun createLowInflowRates(): Map<Double, Long> =
    buildMap {
      for (fee in 1..64) {
        val feeRate = fee * 0.5
        val inflowRate =
          when {
            feeRate <= 4.0 -> 10_000L // ~17 WU/s each
            feeRate <= 8.0 -> 20_000L // ~33 WU/s each
            feeRate <= 16.0 -> 40_000L // ~67 WU/s each
            else -> 50_000L // ~83 WU/s each
          }
        put(feeRate, inflowRate)
      }
    }

  /**
   * Creates a sequence of snapshots modeling mempool behavior with inflows that change over time
   * @param baseWeights Initial weights for each fee rate bucket
   * @param shortTermInflowRates Weight increase per 10 minutes for first hour
   * @param longTermInflowRates Weight increase per 10 minutes after first hour
   * @param blockCount Number of blocks to simulate
   * @param snapshotsPerBlock Number of snapshots to take during each block
   */
  fun createSnapshotSequence(
    startTime: Instant = Instant.now(),
    blockCount: Int = 5,
    snapshotsPerBlock: Int = 3,
    baseWeights: Map<Double, Long> = createDefaultBaseWeights(),
    shortTermInflowRates: Map<Double, Long> = createHighInflowRates(),
    longTermInflowRates: Map<Double, Long> = createLowInflowRates(),
    inflowRateChangeTime: Duration = Duration.ofHours(1),
  ): List<MempoolSnapshot> {
    val snapshots = mutableListOf<MempoolSnapshot>()

    // Calculate end time first
    val endTime = startTime.plusSeconds(600L * (blockCount - 1))

    for (blockIndex in 0 until blockCount) {
      val blockHeight = 100 + blockIndex
      val blockStartTime = startTime.plusSeconds(600L * blockIndex)
      val snapshotInterval = 600 / snapshotsPerBlock

      // Create multiple snapshots for this block
      for (snapshotIndex in 0 until snapshotsPerBlock) {
        val snapshotTime =
          blockStartTime.plusSeconds(
            (snapshotInterval * snapshotIndex).toLong(),
          )

        // Calculate time from end instead of start
        val timeUntilEnd = Duration.between(snapshotTime, endTime)

        val transactions =
          baseWeights.map { (feeRate, baseWeight) ->
            val inflowRate =
              if (timeUntilEnd > inflowRateChangeTime) {
                longTermInflowRates[feeRate]
              } else {
                shortTermInflowRates[feeRate]
              } ?: 0L

            val elapsedIntervals = Duration.between(startTime, snapshotTime).seconds / 600.0
            val cumulativeWeight =
              baseWeight +
                (inflowRate * elapsedIntervals).toLong()

            createTransaction(feeRate = feeRate, weight = cumulativeWeight)
          }

        snapshots.add(
          createSnapshot(
            blockHeight = blockHeight,
            timestamp = snapshotTime,
            transactions = transactions,
          ),
        )
      }
    }

    return snapshots
  }
}
