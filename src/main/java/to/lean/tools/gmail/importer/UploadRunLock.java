/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** An OS-backed lock that permits one write-capable uploader for a state directory. */
final class UploadRunLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private UploadRunLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static UploadRunLock acquire(String filename) throws IOException {
    Path path = Paths.get(filename).toAbsolutePath();
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    FileChannel channel =
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        throw new IOException("Another uploader already holds " + path);
      }
      String registration =
          "pid=" + ProcessHandle.current().pid() + " started=" + Instant.now() + System.lineSeparator();
      channel.truncate(0);
      channel.position(0);
      writeFully(channel, ByteBuffer.wrap(registration.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
      return new UploadRunLock(channel, lock);
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new IOException("Another uploader already holds " + path, e);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
    while (bytes.hasRemaining()) {
      channel.write(bytes);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (lock.isValid()) {
        lock.release();
      }
    } finally {
      channel.close();
    }
  }
}
