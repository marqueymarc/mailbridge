/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer;

import java.io.IOException;

/** The independent durable ledgers for upload and label completion. */
public final class CheckpointStores {
  private final CheckpointStore upload;
  private final CheckpointStore labels;

  public CheckpointStores(String uploadPath, String labelPath) throws IOException {
    upload = new CheckpointStore(uploadPath);
    labels = labelPath == null ? null : new CheckpointStore(labelPath);
  }

  public CheckpointStore upload() {
    return upload;
  }

  public CheckpointStore labels() {
    return labels;
  }
}
