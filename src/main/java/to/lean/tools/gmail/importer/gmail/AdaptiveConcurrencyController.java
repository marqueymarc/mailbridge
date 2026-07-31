/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer.gmail;

/** AIMD-style controller for Gmail per-user concurrency. */
final class AdaptiveConcurrencyController {
  static final int MAX_ATTEMPTS = 5;
  private static final int SUCCESS_WINDOW = 8;
  private static final long INITIAL_BACKOFF_MILLIS = 1000L;
  private static final long MAX_BACKOFF_MILLIS = 30000L;

  private final int maximum;
  private int concurrency;
  private int consecutiveSuccesses;
  private long backoffMillis = INITIAL_BACKOFF_MILLIS;

  AdaptiveConcurrencyController(int initial, int maximum) {
    if (maximum < 1) {
      throw new IllegalArgumentException("maximum concurrency must be positive");
    }
    this.maximum = maximum;
    this.concurrency = Math.max(1, Math.min(initial, maximum));
  }

  int concurrency() {
    return concurrency;
  }

  void recordSuccess() {
    consecutiveSuccesses++;
    if (consecutiveSuccesses >= SUCCESS_WINDOW && concurrency < maximum) {
      concurrency++;
      consecutiveSuccesses = 0;
      backoffMillis = INITIAL_BACKOFF_MILLIS;
    }
  }

  long recordRateLimit() {
    concurrency = Math.max(1, (concurrency + 1) / 2);
    consecutiveSuccesses = 0;
    long delay = backoffMillis;
    backoffMillis = Math.min(MAX_BACKOFF_MILLIS, backoffMillis * 2);
    return delay;
  }

  void recordFailure() {
    consecutiveSuccesses = 0;
  }
}
