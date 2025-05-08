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

import xyz.block.augur.internal.BucketCreator
import xyz.block.augur.internal.InternalAugurApi
import java.time.Instant

/**
 * Represents a snapshot of the Bitcoin mempool at a specific point in time.
 *
 * The snapshot contains transactions grouped into buckets by fee rate,
 * along with metadata about when the snapshot was taken and at what block height.
 *
 * Example usage:
 * ```
 * // Create from raw mempool transactions
 * val snapshot = MempoolSnapshot.fromMempoolTransactions(
 *     transactions = mempoolTransactions,
 *     blockHeight = currentBlockHeight
 * )
 * ```
 *
 * @property blockHeight The Bitcoin block height when this snapshot was taken
 * @property timestamp When this snapshot was taken
 * @property bucketedWeights Map of fee rate bucket indices to total transaction weight
 */
public data class MempoolSnapshot(
  public val blockHeight: Int,
  public val timestamp: Instant,
  public val bucketedWeights: Map<Int, Long>,
) {
  public companion object {
    /**
     * Creates a mempool snapshot from a list of mempool transactions.
     *
     * This method processes the raw transactions, buckets them by fee rate,
     * and creates a snapshot that can be used for fee estimation.
     *
     * @param transactions List of mempool transactions
     * @param blockHeight Current block height
     * @param timestamp When the snapshot is taken (defaults to now)
     * @return A new [MempoolSnapshot] instance
     */
    @OptIn(InternalAugurApi::class)
    public fun fromMempoolTransactions(
      transactions: List<MempoolTransaction>,
      blockHeight: Int,
      timestamp: Instant = Instant.now(),
    ): MempoolSnapshot {
      val bucketedWeights = BucketCreator.createFeeRateBuckets(transactions)

      return MempoolSnapshot(
        blockHeight = blockHeight,
        timestamp = timestamp,
        bucketedWeights = bucketedWeights,
      )
    }

    /**
     * Creates an empty mempool snapshot.
     *
     * This can be useful for testing or when no mempool data is available.
     *
     * @param blockHeight Current block height
     * @param timestamp When the snapshot is taken (defaults to now)
     * @return An empty [MempoolSnapshot] instance
     */
    public fun empty(
      blockHeight: Int,
      timestamp: Instant = Instant.now(),
    ): MempoolSnapshot = MempoolSnapshot(
      blockHeight = blockHeight,
      timestamp = timestamp,
      bucketedWeights = emptyMap(),
    )
  }
}
