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

import java.time.Instant

/**
 * Represents a complete fee estimate with predictions for various block targets
 * and confidence levels.
 *
 * This class contains fee rate estimates organized by confirmation target (in blocks)
 * and confidence level (as a probability between 0.0 and 1.0).
 *
 * Example usage:
 * ```
 * // Get fee rate for confirming within 6 blocks with 95% confidence
 * val feeRate = feeEstimate.getFeeRate(targetBlocks = 6, probability = 0.95)
 *
 * // Get all estimates for confirming within 6 blocks
 * val sixBlockTarget = feeEstimate.getEstimatesForTarget(6)
 *
 * // Print a formatted table of all estimates
 * println(feeEstimate)
 * ```
 *
 * @property estimates Map of block targets to their respective [BlockTarget] estimates
 * @property timestamp When this estimate was calculated
 */
public data class FeeEstimate(
  public val estimates: Map<Int, BlockTarget>,
  public val timestamp: Instant,
) {
  /**
   * Gets the recommended fee rate for a specific target block count and confidence level.
   *
   * @param targetBlocks The desired confirmation target in blocks
   * @param probability The desired confidence level (between 0.0 and 1.0)
   * @return The fee rate in sat/vB, or null if the estimate is not available
   */
  public fun getFeeRate(targetBlocks: Int, probability: Double): Double? =
    estimates[targetBlocks]?.getFeeRate(probability)

  /**
   * Gets all fee rate estimates for a specific target block count.
   *
   * @param targetBlocks The desired confirmation target in blocks
   * @return A [BlockTarget] containing fee rates for various confidence levels,
   *         or null if no estimates are available for this target
   */
  public fun getEstimatesForTarget(targetBlocks: Int): BlockTarget? =
    estimates[targetBlocks]

  /**
   * Gets the nearest available block target to the requested target.
   *
   * This is useful when the exact requested block target is not available.
   *
   * @param targetBlocks The desired confirmation target in blocks
   * @return The nearest available block target, or null if no estimates are available
   */
  public fun getNearestBlockTarget(targetBlocks: Int): Int? {
    if (estimates.isEmpty()) return null
    if (estimates.containsKey(targetBlocks)) return targetBlocks

    return estimates.keys
      .minByOrNull { kotlin.math.abs(it - targetBlocks) }
  }

  /**
   * Returns all available block targets in ascending order.
   *
   * @return A sorted list of all block targets
   */
  public fun getAvailableBlockTargets(): List<Int> = estimates.keys.sorted()

  /**
   * Returns all available confidence levels in ascending order.
   *
   * @return A sorted list of all confidence levels (probabilities)
   */
  public fun getAvailableConfidenceLevels(): List<Double> =
    estimates.values
      .flatMap { it.probabilities.keys }
      .distinct()
      .sorted()

  /**
   * Returns a formatted string representation of the fee estimates as a table.
   *
   * The table shows all block targets as rows and all confidence levels as columns.
   *
   * @return A formatted table string, or empty string if no estimates are available
   */
  public override fun toString(): String {
    if (estimates.isEmpty()) return ""

    val format = "%-10s\t"
    val builder = StringBuilder()

    // Get all unique probabilities
    val probabilities = getAvailableConfidenceLevels()

    // Header row
    builder.append(String.format(format, "Blocks"))
    probabilities.forEach { prob ->
      builder.append(String.format(format, String.format("%.1f%%", prob * 100)))
    }
    builder.append("\n")

    // Data rows
    estimates.entries.sortedBy { it.key }.forEach { (blocks, target) ->
      builder.append(String.format(format, blocks))
      probabilities.forEach { prob ->
        val feeRate = target.getFeeRate(prob)
        builder.append(String.format(format, feeRate?.let { "%.4f".format(it) } ?: "-"))
      }
      builder.append("\n")
    }

    return builder.toString()
  }
}

/**
 * Represents fee estimates for a specific block target with multiple confidence levels.
 *
 * @property blocks The confirmation target in blocks
 * @property probabilities Map of confidence levels to their respective fee rates
 */
public data class BlockTarget(
  public val blocks: Int,
  public val probabilities: Map<Double, Double>,
) {
  /**
   * Gets the fee rate for a specific confidence level.
   *
   * @param probability The desired confidence level (between 0.0 and 1.0)
   * @return The fee rate in sat/vB, or null if not available for this confidence level
   */
  public fun getFeeRate(probability: Double): Double? =
    probabilities[probability]
}
