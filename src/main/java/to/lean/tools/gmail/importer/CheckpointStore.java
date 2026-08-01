/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crash-safe append-only upload journal. Each record is forced to storage before its method
 * returns. An in-flight record is intentionally retained after a lost response: a future run must
 * explicitly choose whether to retry that ambiguous request.
 */
public final class CheckpointStore {
  private final Path path;
  private final Map<String, String> completed = new HashMap<>();
  private final Map<String, String> inFlight = new HashMap<>();
  private final Map<String, String> uploaded = new HashMap<>();

  public CheckpointStore(String filename) throws IOException {
    path = Paths.get(filename).toAbsolutePath();
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    load();
  }

  public synchronized boolean isCompleted(String key) {
    return completed.containsKey(key);
  }

  public synchronized String getGmailMessageId(String key) {
    return completed.get(key);
  }

  public synchronized boolean isInFlight(String key) {
    return inFlight.containsKey(key) && !completed.containsKey(key) && !uploaded.containsKey(key);
  }

  public synchronized String getUploadedGmailMessageId(String key) {
    return uploaded.get(key);
  }

  /** Returns a stable snapshot of completed source keys and their Gmail IDs. */
  public synchronized Map<String, String> completedEntries() {
    return new LinkedHashMap<>(completed);
  }

  /** Persists the intent before the request can reach Gmail. */
  public synchronized void markInFlight(String key) throws IOException {
    if (!completed.containsKey(key) && !inFlight.containsKey(key)) {
      append("in_flight", key, "");
      inFlight.put(key, "");
    }
  }

  /** Restores an accepted-but-unverified result to the explicit ambiguity state. */
  public synchronized void markAmbiguous(String key) throws IOException {
    if (completed.containsKey(key)) {
      return;
    }
    append("in_flight", key, "");
    inFlight.put(key, "");
    uploaded.remove(key);
  }

  /** Persists Gmail's returned id after an accepted request. */
  public synchronized void markCompleted(String key, String gmailMessageId) throws IOException {
    if (completed.containsKey(key)) {
      return;
    }
    String id = gmailMessageId == null ? "" : gmailMessageId;
    append("completed", key, id);
    completed.put(key, id);
    inFlight.remove(key);
    uploaded.remove(key);
  }

  /** Persists Gmail's response before a separate, idempotent label request is made. */
  public synchronized void markUploaded(String key, String gmailMessageId) throws IOException {
    if (completed.containsKey(key) || uploaded.containsKey(key)) {
      return;
    }
    String id = gmailMessageId == null ? "" : gmailMessageId;
    append("uploaded", key, id);
    uploaded.put(key, id);
    inFlight.remove(key);
  }

  public Path getPath() {
    return path;
  }

  private void load() throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    for (int index = 0; index < lines.size(); index++) {
      try {
        String[] fields = lines.get(index).split("\t", -1);
        if (fields.length < 2
            || (!"completed".equals(fields[0])
                && !"in_flight".equals(fields[0])
                && !"uploaded".equals(fields[0]))) {
          throw new IllegalArgumentException("unrecognized record");
        }
        String key = decode(fields[1]);
        String gmailId = fields.length >= 3 ? decode(fields[2]) : "";
        if ("completed".equals(fields[0])) {
          completed.put(key, gmailId);
          inFlight.remove(key);
          uploaded.remove(key);
        } else if ("uploaded".equals(fields[0]) && !completed.containsKey(key)) {
          uploaded.put(key, gmailId);
          inFlight.remove(key);
        } else if (!completed.containsKey(key)) {
          inFlight.put(key, gmailId);
          uploaded.remove(key);
        }
      } catch (IllegalArgumentException e) {
        if (index == lines.size() - 1) {
          System.err.println("Ignoring truncated final checkpoint record in " + path);
          return;
        }
        throw new IOException("Malformed checkpoint record " + (index + 1) + " in " + path, e);
      }
    }
  }

  private void append(String status, String key, String gmailMessageId) throws IOException {
    String line = status + "\t" + encode(key) + "\t" + encode(gmailMessageId) + System.lineSeparator();
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
      channel.force(true);
    }
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }
}
