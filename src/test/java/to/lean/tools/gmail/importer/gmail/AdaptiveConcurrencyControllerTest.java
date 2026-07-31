/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer.gmail;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class AdaptiveConcurrencyControllerTest {

  @Test
  public void growsAfterCleanWindowAndCapsAtMaximum() {
    AdaptiveConcurrencyController controller = new AdaptiveConcurrencyController(2, 4);

    for (int i = 0; i < 8; i++) {
      controller.recordSuccess();
    }
    assertThat(controller.concurrency()).isEqualTo(3);

    for (int i = 0; i < 8; i++) {
      controller.recordSuccess();
    }
    assertThat(controller.concurrency()).isEqualTo(4);

    for (int i = 0; i < 20; i++) {
      controller.recordSuccess();
    }
    assertThat(controller.concurrency()).isEqualTo(4);
  }

  @Test
  public void halvesOnRateLimitAndNeverDropsBelowOne() {
    AdaptiveConcurrencyController controller = new AdaptiveConcurrencyController(4, 8);

    assertThat(controller.recordRateLimit()).isEqualTo(1000L);
    assertThat(controller.concurrency()).isEqualTo(2);
    assertThat(controller.recordRateLimit()).isEqualTo(2000L);
    assertThat(controller.concurrency()).isEqualTo(1);
    assertThat(controller.recordRateLimit()).isEqualTo(4000L);
    assertThat(controller.concurrency()).isEqualTo(1);
  }
}
