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
import xyz.block.augur.MempoolTransaction
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalAugurApi::class)
class BucketCreatorTest {
  @Test
  fun `test createFeeRateBuckets with single transaction`() {
    val tx =
      MempoolTransaction(
        weight = 400, // 100 vBytes (weight/4)
        fee = 200, // 2 sat/vB
      )

    val buckets = BucketCreator.createFeeRateBuckets(listOf(tx))

    // Calculate expected bucket index for fee rate of 2 sat/vB
    val expectedBucketIndex = (ln(2.0) * 100).roundToInt()

    println("Buckets: $buckets")
    assertTrue(buckets.containsKey(expectedBucketIndex))
    assertEquals(400L, buckets[expectedBucketIndex])
  }

  @Test
  fun `test createFeeRateBuckets with multiple transactions in same bucket`() {
    // Both transactions have 2 sat/vB fee rate
    val tx1 = MempoolTransaction(weight = 400, fee = 200) // 100 vBytes, 200 sats
    val tx2 = MempoolTransaction(weight = 800, fee = 400) // 200 vBytes, 400 sats

    val buckets = BucketCreator.createFeeRateBuckets(listOf(tx1, tx2))

    val expectedBucketIndex = (ln(2.0) * 100).roundToInt()

    println("Buckets: $buckets")

    assertEquals(1, buckets.size)
    assertEquals(1200L, buckets[expectedBucketIndex]) // Total weight = 1200
  }

  @Test
  fun `test createFeeRateBuckets with transactions in different buckets`() {
    // 2 sat/vB and 4 sat/vB transactions
    val tx1 = MempoolTransaction(weight = 400, fee = 200) // 100 vBytes, 2 sat/vB
    val tx2 = MempoolTransaction(weight = 400, fee = 400) // 100 vBytes, 4 sat/vB

    val buckets = BucketCreator.createFeeRateBuckets(listOf(tx1, tx2))

    println("Buckets: $buckets")

    assertEquals(2, buckets.size)
    assertTrue(buckets.values.all { it == 400L })
  }

  @Test
  fun `test createFeeRateBuckets with exponential fee rates`() {
    // Create transactions with exponentially increasing fee rates
    val transactions =
      listOf(
        MempoolTransaction(weight = 400, fee = 100), // 1 sat/vB
        MempoolTransaction(weight = 400, fee = 272), // e sat/vB
        MempoolTransaction(weight = 400, fee = 739), // e^2 sat/vB
        MempoolTransaction(weight = 400, fee = 2009), // e^3 sat/vB
      )

    val buckets = BucketCreator.createFeeRateBuckets(transactions)

    // Calculate expected bucket indices
    val expectedBuckets =
      mapOf(
        0 to 400L, // ln(1) * 100 = 0
        100 to 400L, // ln(e) * 100 = 100
        200 to 400L, // ln(e^2) * 100 = 200
        300 to 400L, // ln(e^3) * 100 = 300
      )

    println("Buckets: $buckets")
    assertEquals(expectedBuckets, buckets)
  }

  @Test
  fun `test createFeeRateBuckets with duplicate fee rates`() {
    // Create multiple transactions with the same fee rates
    val transactions =
      listOf(
        MempoolTransaction(weight = 400, fee = 100), // 1 sat/vB
        MempoolTransaction(weight = 400, fee = 100), // 1 sat/vB
        MempoolTransaction(weight = 400, fee = 272), // e sat/vB
        MempoolTransaction(weight = 400, fee = 272), // e sat/vB
      )

    val buckets = BucketCreator.createFeeRateBuckets(transactions)

    // Calculate expected bucket indices
    val expectedBuckets =
      mapOf(
        0 to 800L, // Two transactions with fee rate 1
        100 to 800L, // Two transactions with fee rate e
      )

    assertEquals(expectedBuckets, buckets)
  }

  @Test
  fun `test createFeeRateBuckets with very high fee rates`() {
    val transactions =
      listOf(
        MempoolTransaction(
          weight = 400,
          fee = 1_000_000_000, // Very high fee rate
        ),
      )

    val buckets = BucketCreator.createFeeRateBuckets(transactions)

    // The bucket index should be at BUCKET_MAX
    assertTrue(buckets.containsKey(BucketCreator.BUCKET_MAX))
    assertEquals(400L, buckets[BucketCreator.BUCKET_MAX])
  }
}
