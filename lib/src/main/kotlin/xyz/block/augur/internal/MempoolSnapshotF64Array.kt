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
import xyz.block.augur.MempoolSnapshot
import java.time.Instant

/**
 * Internal representation of mempool snapshot using F64Array for efficient calculations.
 */
@InternalAugurApi
internal data class MempoolSnapshotF64Array(
  val timestamp: Instant,
  val blockHeight: Int,
  val buckets: F64Array,
) {
  companion object {
    /**
     * Converts a [MempoolSnapshot] to [MempoolSnapshotF64Array] for efficient calculations.
     */
    fun fromMempoolSnapshot(snapshot: MempoolSnapshot): MempoolSnapshotF64Array {
      val feeRateBuckets = F64Array(BucketCreator.BUCKET_MAX + 1)
      snapshot.bucketedWeights.forEach { (bucket, weight) ->
        // Remove buckets that are less than 0 (i.e. fee rates that are less than 1 satoshi/vByte)
        if (bucket >= 0) {
          // Inserting into reverse order will allow us to mine the highest fee rate buckets first
          feeRateBuckets[BucketCreator.BUCKET_MAX - bucket] = weight.toDouble()
        }
      }

      return MempoolSnapshotF64Array(
        timestamp = snapshot.timestamp,
        blockHeight = snapshot.blockHeight,
        buckets = feeRateBuckets,
      )
    }
  }
}
