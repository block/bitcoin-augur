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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

@OptIn(InternalAugurApi::class)
class InflowCalculatorTest {
  @Test
  fun `test calculateInflows with empty snapshot list`() {
    val inflows =
      InflowCalculator.calculateInflows(
        mempoolSnapshots = emptyList(),
        timeframe = Duration.ofMinutes(10),
      )

    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(0.0, inflows.sum())
  }

  @Test
  fun `test calculateInflows with single block snapshots`() {
    val now = Instant.now()
    val buckets1 = F64Array(BucketCreator.BUCKET_MAX + 1) { 1000.0 }
    val buckets2 = F64Array(BucketCreator.BUCKET_MAX + 1) { 2000.0 }

    val snapshots =
      listOf(
        MempoolSnapshotF64Array(now, 100, buckets1),
        MempoolSnapshotF64Array(now.plusSeconds(300), 100, buckets2),
      )

    val inflows =
      InflowCalculator.calculateInflows(
        mempoolSnapshots = snapshots,
        timeframe = Duration.ofMinutes(10),
      )

    // Each bucket increased by 1000, over 5 minutes
    // Normalized to 10 minutes, should be 2000 per bucket
    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(2000.0, inflows[0])
  }

  @Test
  fun `test calculateInflows with consistent inflow rate`() {
    val now = Instant.now()
    // Create snapshots with consistent inflow rate across all buckets
    val buckets1 = F64Array(BucketCreator.BUCKET_MAX + 1) { 1_000_000.0 }
    val buckets2 = F64Array(BucketCreator.BUCKET_MAX + 1) { 2_000_000.0 }
    val buckets3 = F64Array(BucketCreator.BUCKET_MAX + 1) { 3_000_000.0 }

    val snapshots =
      listOf(
        MempoolSnapshotF64Array(now, 100, buckets1),
        MempoolSnapshotF64Array(now.plusSeconds(300), 100, buckets2),
        MempoolSnapshotF64Array(now.plusSeconds(600), 100, buckets3),
      )

    val inflows =
      InflowCalculator.calculateInflows(
        mempoolSnapshots = snapshots,
        timeframe = Duration.ofMinutes(10),
      )

    // Each bucket increased by 1M every 5 minutes
    // Normalized to 10 minutes, should be 2M per bucket
    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(2_000_000.0, inflows[0])
    assertEquals(2_000_000.0, inflows[BucketCreator.BUCKET_MAX])
  }

  @Test
  fun `test calculateInflows with different rates per bucket`() {
    val now = Instant.now()

    // Create snapshots with different inflow rates for different buckets
    val buckets1 =
      F64Array(BucketCreator.BUCKET_MAX + 1) { idx ->
        when (idx) {
          0 -> 1_000_000.0
          1 -> 2_000_000.0
          2 -> 3_000_000.0
          else -> 0.0
        }
      }

    val buckets2 =
      F64Array(BucketCreator.BUCKET_MAX + 1) { idx ->
        when (idx) {
          0 -> 2_000_000.0
          1 -> 4_000_000.0
          2 -> 6_000_000.0
          else -> 0.0
        }
      }

    val snapshots =
      listOf(
        MempoolSnapshotF64Array(now, 100, buckets1),
        MempoolSnapshotF64Array(now.plusSeconds(300), 100, buckets2),
      )

    val inflows =
      InflowCalculator.calculateInflows(
        mempoolSnapshots = snapshots,
        timeframe = Duration.ofMinutes(10),
      )

    // Over 5 minutes:
    // Bucket 0 increased by 1M -> 2M per 10 minutes
    // Bucket 1 increased by 2M -> 4M per 10 minutes
    // Bucket 2 increased by 3M -> 6M per 10 minutes
    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(2_000_000.0, inflows[0])
    assertEquals(4_000_000.0, inflows[1])
    assertEquals(6_000_000.0, inflows[2])
    assertEquals(0.0, inflows[3])
  }

  @Test
  fun `test calculateInflows considers only first and last snapshot per block height`() {
    val now = Instant.now()

    // Create snapshots for the same block height with fluctuating values
    val snapshots = listOf(
      MempoolSnapshotF64Array(now, 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 1000.0 }),
      MempoolSnapshotF64Array(now.plusSeconds(100), 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 500.0 }), // Dip should be ignored
      MempoolSnapshotF64Array(now.plusSeconds(300), 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 2000.0 })
    )

    val inflows = InflowCalculator.calculateInflows(
      mempoolSnapshots = snapshots,
      timeframe = Duration.ofMinutes(10),
    )

    // Should only consider the difference between first (1000) and last (2000) snapshots
    // Over 5 minutes: increased by 1000 -> normalized to 2000 per 10 minutes
    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(2000.0, inflows[0])
  }

  @Test
  fun `test calculateInflows handles multiple block heights`() {
    val now = Instant.now()

    val snapshots = listOf(
      // Block 100
      MempoolSnapshotF64Array(now, 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 1000.0 }),
      MempoolSnapshotF64Array(now.plusSeconds(100), 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 500.0 }), // Should be ignored
      MempoolSnapshotF64Array(now.plusSeconds(200), 100, F64Array(BucketCreator.BUCKET_MAX + 1) { 2000.0 }),

      // Block 101
      MempoolSnapshotF64Array(now.plusSeconds(300), 101, F64Array(BucketCreator.BUCKET_MAX + 1) { 2000.0 }),
      MempoolSnapshotF64Array(now.plusSeconds(400), 101, F64Array(BucketCreator.BUCKET_MAX + 1) { 1500.0 }), // Should be ignored
      MempoolSnapshotF64Array(now.plusSeconds(500), 101, F64Array(BucketCreator.BUCKET_MAX + 1) { 3000.0 })
    )

    val inflows = InflowCalculator.calculateInflows(
      mempoolSnapshots = snapshots,
      timeframe = Duration.ofMinutes(10),
    )

    // Block 100: +1000 over 200s
    // Block 101: +1000 over 200s
    // Total: +2000 over 400s = +3000 per 600s (10 minutes)
    assertEquals(BucketCreator.BUCKET_MAX + 1, inflows.length)
    assertEquals(3000.0, inflows[0])
  }
}
