/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer.local.thunderbird;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import to.lean.tools.gmail.importer.local.LocalMessage;
import to.lean.tools.gmail.importer.local.LocalStorage;

/** Streams Thunderbird mbox files without indexing the whole mailbox first. */
final class StreamingMboxStorage implements LocalStorage {
  private final List<Source> sources;

  StreamingMboxStorage(String requestedPath) throws IOException {
    sources = discover(Paths.get(requestedPath));
    if (sources.isEmpty()) {
      throw new IOException("No Thunderbird mbox files found at " + requestedPath);
    }
  }

  @Override
  public Iterator<LocalMessage> iterator() {
    return new MessageIterator(sources.iterator());
  }

  private static List<Source> discover(Path requested) throws IOException {
    List<Source> discovered = new ArrayList<>();
    if (Files.isRegularFile(requested)) {
      addSource(discovered, requested, requested.getFileName().toString());
      Path sidecar = requested.resolveSibling(requested.getFileName() + ".sbd");
      if (Files.isDirectory(sidecar)) {
        addDirectory(discovered, sidecar, requested.getFileName().toString());
      }
    } else if (Files.isDirectory(requested)) {
      addDirectory(discovered, requested, "");
    } else {
      throw new IOException("Mailbox path does not exist: " + requested);
    }
    discovered.sort(Comparator.comparing(source -> source.path.toString()));
    return discovered;
  }

  private static void addDirectory(List<Source> output, Path directory, String prefix)
      throws IOException {
    try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
      stream
          .filter(Files::isRegularFile)
          .filter(path -> !path.getFileName().toString().endsWith(".msf"))
          .filter(path -> !path.getFileName().toString().equals("msgFilterRules.dat"))
          .forEach(
              path -> {
                Path relative = directory.relativize(path);
                String relativeLabel = relative.toString().replace(java.io.File.separatorChar, '/');
                relativeLabel = relativeLabel.replace(".sbd/", "/");
                String label = prefix.isEmpty() ? relativeLabel : prefix + "/" + relativeLabel;
                try {
                  addSource(output, path, label);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  private static void addSource(List<Source> output, Path path, String label) throws IOException {
    if (Files.size(path) > 0) {
      output.add(new Source(path, label));
    }
  }

  private static final class Source {
    private final Path path;
    private final String label;

    private Source(Path path, String label) {
      this.path = path;
      this.label = label;
    }
  }

  private static final class MessageIterator implements Iterator<LocalMessage> {
    private final Iterator<Source> sourceIterator;
    private Source source;
    private LineReader reader;
    private LocalMessage next;

    private MessageIterator(Iterator<Source> sourceIterator) {
      this.sourceIterator = sourceIterator;
    }

    @Override
    public boolean hasNext() {
      loadNext();
      return next != null;
    }

    @Override
    public LocalMessage next() {
      loadNext();
      if (next == null) {
        throw new NoSuchElementException();
      }
      LocalMessage result = next;
      next = null;
      return result;
    }

    private void loadNext() {
      if (next != null) {
        return;
      }
      try {
        while (true) {
          if (reader == null) {
            if (!sourceIterator.hasNext()) {
              return;
            }
            source = sourceIterator.next();
            reader =
                new LineReader(
                    new BufferedInputStream(Files.newInputStream(source.path), 128 * 1024));
          }
          byte[] raw = reader.nextMessage();
          if (raw != null) {
            next = new MboxLocalMessage(raw, source.label);
            return;
          }
          reader.close();
          reader = null;
        }
      } catch (IOException e) {
        throw new RuntimeException("Unable to read Thunderbird mbox " + source.path, e);
      }
    }
  }

  /** Reads one mbox record at a time while preserving RFC822 bytes. */
  private static final class LineReader {
    private final InputStream input;
    private final byte[] buffer = new byte[128 * 1024];
    private int position;
    private int limit;
    private boolean eof;
    private final ByteArrayOutputStream message = new ByteArrayOutputStream();

    private LineReader(InputStream input) {
      this.input = input;
    }

    private byte[] nextMessage() throws IOException {
      message.reset();
      boolean sawRecord = false;
      while (true) {
        byte[] line = readLine();
        if (line == null) {
          return message.size() == 0 ? null : message.toByteArray();
        }
        boolean delimiter =
            line.length >= 5
                && line[0] == 'F'
                && line[1] == 'r'
                && line[2] == 'o'
                && line[3] == 'm'
                && line[4] == ' ';
        if (delimiter) {
          if (sawRecord && message.size() > 0) {
            return message.toByteArray();
          }
          sawRecord = true;
          continue;
        }
        if (sawRecord) {
          message.write(line);
        }
      }
    }

    private byte[] readLine() throws IOException {
      if (eof) {
        return null;
      }
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      while (true) {
        if (position >= limit) {
          limit = input.read(buffer);
          position = 0;
          if (limit < 0) {
            eof = true;
            return line.size() == 0 ? null : line.toByteArray();
          }
        }
        int start = position;
        while (position < limit && buffer[position] != '\n') {
          position++;
        }
        if (position < limit) {
          position++;
          line.write(buffer, start, position - start);
          return line.toByteArray();
        }
        line.write(buffer, start, position - start);
      }
    }

    private void close() throws IOException {
      input.close();
    }
  }
}
