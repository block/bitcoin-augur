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

/**
 * Represents a transaction in the Bitcoin mempool.
 *
 * This class contains the minimal information needed for fee estimation:
 * the transaction's weight and the fee amount.
 *
 * Example usage:
 * ```
 * val transaction = MempoolTransaction(
 *     weight = 565L,  // Transaction weight in weight units
 *     fee = 1000L     // Fee amount in satoshis
 * )
 *
 * // Get fee rate in sat/vB
 * val feeRate = transaction.getFeeRate()
 * ```
 *
 * @property weight The transaction weight in weight units (WU)
 * @property fee The transaction fee in satoshis
 */
public data class MempoolTransaction(
  public val weight: Long,
  public val fee: Long,
) {
  /**
   * Calculates the transaction's fee rate in sat/vB.
   *
   * This converts from weight units to virtual bytes and calculates
   * the fee rate as satoshis per virtual byte.
   *
   * @return The fee rate in sat/vB
   */
  public fun getFeeRate(): Double = fee * WU_PER_BYTE / weight.toDouble()

  public companion object {
    /**
     * Conversion factor from weight units to virtual bytes.
     *
     * A virtual byte (vB) is defined as weight / 4.
     * 1 vB = 4 weight units (WU)
     */
    public const val WU_PER_BYTE: Double = 4.0
  }
}
