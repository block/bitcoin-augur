# Module augur

Augur is a Bitcoin fee estimation library that provides accurate fee estimates by analyzing historical mempool data.

## Overview

The Augur library allows you to predict Bitcoin transaction confirmation times with various confidence levels.
It analyzes mempool snapshots to generate fee rate estimates for different confirmation targets.

## Key Components

- **FeeEstimator**: The main entry point for calculating fee estimates
- **MempoolSnapshot**: Represents a snapshot of the Bitcoin mempool at a specific point in time
- **MempoolTransaction**: Represents a transaction in the Bitcoin mempool
- **FeeEstimate**: Contains fee rate estimates for various block targets and confidence levels

## Usage Example

```kotlin
// Initialize the estimator with default settings
val feeEstimator = FeeEstimator()

// Create a mempool snapshot from current transactions
val mempoolSnapshot = MempoolSnapshot.fromMempoolTransactions(
    transactions = currentMempoolTransactions.map { 
        MempoolTransaction(
            weight = it.weight.toLong(),
            fee = it.baseFee // in satoshis
        )
    },
    blockHeight = currentBlockHeight
)

// Calculate fee estimates using historical snapshots
val feeEstimate = feeEstimator.calculateEstimates(historicalSnapshots)

// Get fee rate for specific target and confidence
val feeRate = feeEstimate.getFeeRate(
    targetBlocks = 6,     // Confirm within 6 blocks
    probability = 0.95    // 95% confidence
)
```

# Features

- Predictions based on historical mempool data and transaction inflows
- Confidence-based fee rate estimates
- Multiple confirmation targets (from 3 to 144 blocks)
- Simple, clean API that integrates with any Bitcoin implementation
- Lightweight with minimal dependencies

# Package xyz.block.augur
Main public API

# Package xyz.block.augur.internal
Internal implementation details (not intended for direct use)
