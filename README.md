# Augur

A Bitcoin fee estimation library that provides accurate fee estimates by analyzing historical mempool data. Augur uses statistical modeling to predict confirmation times with various confidence levels.

[![Maven Central](https://img.shields.io/maven-central/v/xyz.block/augur.svg)](https://search.maven.org/artifact/xyz.block/augur)
[![License](https://img.shields.io/github/license/block/bitcoin-augur)](LICENSE)

## Features

- Predictions based on historical mempool data and transaction inflows
- Confidence-based fee rate estimates
- Multiple confirmation targets (from 3 to 144 blocks)

## Installation

### Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("xyz.block:augur:0.1.0")
}
```

### Maven

```xml
<dependency>
  <groupId>xyz.block</groupId>
  <artifactId>augur</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Basic Usage

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

// Print a table of all fee estimates
println(feeEstimate.toString())
```

## Working with Fee Estimates

The `FeeEstimate` object contains fee rate predictions for various block targets and confidence levels:

```kotlin
// Print the full fee estimate table
println(feeEstimate)

// Get all estimates for confirming within 6 blocks
val sixBlockTarget = feeEstimate.getEstimatesForTarget(6)

// Access probabilities map directly (confidence level -> fee rate)
val probabilities = sixBlockTarget?.probabilities
```

## Customizing the Estimator

```kotlin
// Create an estimator with custom settings
val customFeeEstimator = FeeEstimator(
    // Custom probability levels (confidence)
    probabilities = listOf(0.1, 0.25, 0.5, 0.75, 0.9, 0.99),
    
    // Custom block targets
    blockTargets = listOf(1.0, 2.0, 3.0, 6.0, 12.0, 24.0, 48.0, 72.0)
)
```

## Collecting Mempool Data

This library focuses solely on fee estimation based on mempool data. You are responsible for:

1. Collecting mempool transaction data from your Bitcoin node
2. Creating and storing `MempoolSnapshot` objects
3. Providing historical snapshots to the fee estimator

See our [reference implementation](https://github.com/block/bitcoin-augur-reference) for a complete example of integration with Bitcoin Core and implementing a persistence layer.

## How It Works

Augur uses statistical modeling of historical mempool data to predict transaction confirmation times. The process:

1. Collects and buckets mempool transactions by fee rate
2. Calculates transaction inflow rates at different fee levels
3. Simulates mempool progression based on historical patterns
4. Produces fee rate estimates with multiple confidence levels

## API Documentation

Complete API documentation is available at [https://block.github.io/bitcoin-augur/](https://block.github.io/bitcoin-augur/).

## Contributing

Contributions are welcome! Please read our [Contributing Guidelines](CONTRIBUTING.md) for details on how to submit pull requests, report issues, and suggest improvements.

## Acknowledgements

The fee estimation algorithm implemented in Augur was inspired by Felix Weis's [WhatTheFee](https://github.com/FelixWeis/WhatTheFee--legacy) and bitbug42's [btcflow](https://github.com/joltfun/btcflow). While Augur is a complete rewrite in Kotlin with ongoing improvements, we appreciate the foundational concepts demonstrated by these projects.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
