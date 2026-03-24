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

import org.junit.jupiter.api.Test
import xyz.block.augur.MempoolSnapshot
import java.time.Instant
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalAugurApi::class)
class MempoolSnapshotF64ArrayTest {
  private val defaultConfig = BucketConfig.DEFAULT

  @Test
  fun `fromMempoolSnapshot drops buckets below minimum`() {
    val lowBucket = defaultConfig.bucketMin - 1
    val validBucket = defaultConfig.bucketMin
    val snapshot =
      MempoolSnapshot(
        blockHeight = 100,
        timestamp = Instant.now(),
        bucketedWeights = mapOf(
          lowBucket to 400L,
          validBucket to 600L,
        ),
      )

    val result = MempoolSnapshotF64Array.fromMempoolSnapshot(snapshot)

    assertEquals(defaultConfig.arraySize, result.buckets.length)
    val validIndex = BucketCreator.toArrayIndex(validBucket)
    assertEquals(600.0, result.buckets[validIndex])

    var totalWeight = 0.0
    for (i in 0 until result.buckets.length) {
      totalWeight += result.buckets[i]
    }
    assertEquals(600.0, totalWeight)
    assertTrue(validIndex == result.buckets.length - 1)
  }

  @Test
  fun `fromMempoolSnapshot with custom minFeeRate accepts lower buckets`() {
    val config = BucketConfig(0.1)
    val lowBucket = (ln(0.1) * 100).roundToInt() // -230, valid for this config
    val validBucket = 0 // 1 sat/vB

    val snapshot =
      MempoolSnapshot(
        blockHeight = 100,
        timestamp = Instant.now(),
        bucketedWeights = mapOf(
          lowBucket to 400L,
          validBucket to 600L,
        ),
      )

    val result = MempoolSnapshotF64Array.fromMempoolSnapshot(snapshot, config)

    assertEquals(config.arraySize, result.buckets.length)

    var totalWeight = 0.0
    for (i in 0 until result.buckets.length) {
      totalWeight += result.buckets[i]
    }
    assertEquals(1000.0, totalWeight)
  }

  @Test
  fun `fromMempoolSnapshot ignores very low fee rates below configured minimum`() {
    val config = BucketConfig(0.1)
    val veryLowFeeRate = 0.05
    val veryLowBucket = (ln(veryLowFeeRate) * 100).roundToInt() // -300

    // Verify this bucket is indeed below the config's bucketMin
    assertTrue(veryLowBucket < config.bucketMin)

    val validBucket = 0 // 1 sat/vB

    val snapshot =
      MempoolSnapshot(
        blockHeight = 100,
        timestamp = Instant.now(),
        bucketedWeights = mapOf(
          veryLowBucket to 1000L,
          validBucket to 500L,
        ),
      )

    val result = MempoolSnapshotF64Array.fromMempoolSnapshot(snapshot, config)

    // Should not throw, and should only include the valid bucket's weight
    var totalWeight = 0.0
    for (i in 0 until result.buckets.length) {
      totalWeight += result.buckets[i]
    }
    assertEquals(500.0, totalWeight)
  }

  @Test
  fun `fromMempoolSnapshot drops buckets above BUCKET_MAX`() {
    val oversizedBucket = BucketCreator.BUCKET_MAX + 1
    val validBucket = BucketCreator.BUCKET_MAX

    val snapshot =
      MempoolSnapshot(
        blockHeight = 100,
        timestamp = Instant.now(),
        bucketedWeights = mapOf(
          oversizedBucket to 1000L,
          validBucket to 500L,
        ),
      )

    val result = MempoolSnapshotF64Array.fromMempoolSnapshot(snapshot)

    // Only the valid bucket's weight should be included
    var totalWeight = 0.0
    for (i in 0 until result.buckets.length) {
      totalWeight += result.buckets[i]
    }
    assertEquals(500.0, totalWeight)
    assertEquals(500.0, result.buckets[BucketCreator.toArrayIndex(validBucket)])
  }
}
