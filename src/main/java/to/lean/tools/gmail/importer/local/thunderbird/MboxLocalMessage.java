/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer.local.thunderbird;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MailDateFormat;
import javax.mail.internet.MimeMessage;
import to.lean.tools.gmail.importer.local.LocalMessage;

/** RFC822 message read from a Thunderbird mbox file. */
final class MboxLocalMessage implements LocalMessage {
  private static final Pattern RFC2822_DATE =
      Pattern.compile(
          "^(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\\s+)?"
              + "\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4}\\s+"
              + "\\d{2}:\\d{2}:\\d{2}\\s+(?:[+-]\\d{4}|UT|GMT)$");
  private static final Session SESSION = Session.getInstance(new Properties());
  private final byte[] raw;
  private final byte[] normalizedRaw;
  private final String folder;
  private final MimeMessage message;
  private final String originalMessageId;
  private final String originalDate;
  private String generatedMessageId;

  MboxLocalMessage(byte[] raw, String folder) {
    this.raw = raw;
    this.folder = folder;
    try {
      this.message = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
      this.originalMessageId = firstNonBlank(this.message.getHeader("Message-ID"));
      this.originalDate = firstNonBlank(this.message.getHeader("Date"));
      this.normalizedRaw = normalize(this.message);
    } catch (MessagingException e) {
      throw new RuntimeException("Malformed RFC822 message in " + folder, e);
    }
  }

  @Override
  public String getMessageId() {
    if (originalMessageId != null) {
      return originalMessageId;
    }
    if (generatedMessageId == null) {
      generatedMessageId = "generated:" + sha256(raw);
    }
    return generatedMessageId;
  }

  @Override
  public String getFromHeader() {
    String[] from = header("From");
    return from.length == 0 ? "" : from[0];
  }

  @Override
  public List<String> getFolders() {
    return Collections.singletonList(folder);
  }

  @Override
  public byte[] getRawContent() {
    return normalizedRaw;
  }

  /** Keep restart identity tied to the original mbox bytes, not the upload normalization. */
  @Override
  public String getCheckpointKey() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String folders = getFolders().stream().sorted().collect(Collectors.joining("\u001f"));
      digest.update(raw);
      digest.update((byte) 0);
      digest.update(folders.getBytes(StandardCharsets.UTF_8));
      return getMessageId()
          + "\t"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required", e);
    }
  }

  @Override
  public String getUploadCheckpointKey() {
    // Message-ID alone is not sufficient: legacy exports can reuse it for distinct bodies.
    // The raw digest also collapses exact source copies that appear in multiple folders.
    return "v4-upload\t" + sha256(raw);
  }

  @Override
  public String getLabelCheckpointKey() {
    return "v3-label\t" + canonicalIdentity() + "\t" + folder;
  }

  @Override
  public boolean isUnread() {
    return !hasStatusBit(0x0001);
  }

  @Override
  public boolean isStarred() {
    return hasStatusBit(0x0004);
  }

  private boolean hasStatusBit(int bit) {
    String[] statuses = header("X-Mozilla-Status");
    if (statuses.length == 0) {
      return false;
    }
    try {
      return (Integer.parseInt(statuses[0].trim(), 16) & bit) != 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private String[] header(String name) {
    try {
      String[] values = message.getHeader(name);
      return values == null ? new String[0] : values;
    } catch (MessagingException e) {
      throw new RuntimeException("Unable to read " + name + " header", e);
    }
  }

  /**
   * Re-serializes the parsed message, matching the old Thunderbird JavaMail path. Some legacy
   * mbox records contain duplicate From headers or MIME formatting that Gmail's import endpoint
   * rejects even though JavaMail can read them. Keep the source bytes in {@link #raw}; only the
   * upload representation is normalized.
   */
  private byte[] normalize(MimeMessage parsed) {
    try {
      String[] fromHeaders = parsed.getHeader("From");
      String selected = firstNonBlank(fromHeaders);
      if (selected == null) {
        selected = firstNonBlank(parsed.getHeader("Sender"));
      }
      if (selected == null) {
        selected = firstNonBlank(parsed.getHeader("Return-Path"));
      }
      if (selected == null) {
        selected = firstNonBlank(parsed.getHeader("Reply-To"));
      }
      if (selected == null) {
        selected = "undisclosed-recipients:;";
      }
      parsed.removeHeader("From");
      parsed.setHeader("From", selected);
      try {
        parsed.saveChanges();
      } catch (MessagingException e) {
        System.err.println("Could not fully normalize legacy MIME headers; preserving parsed headers.");
      }

      // saveChanges() can regenerate Message-ID values, and Gmail supplies a current date when
      // serialized legacy mail lacks Date. Restore the source values after normalization so
      // internalDateSource=dateHeader never treats import time as the message date.
      restoreHeader(parsed, "Message-ID", originalMessageId);
      String uploadDate = normalizedDate(originalDate);
      if (uploadDate == null) {
        uploadDate = earliestReceivedDate(parsed);
      }
      restoreHeader(parsed, "Date", uploadDate);

      ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length + 256);
      try {
        parsed.writeTo(output);
        return output.toByteArray();
      } catch (MessagingException | IOException e) {
        System.err.println("Could not serialize legacy MIME message; preserving original bytes.");
        return raw;
      }
    } catch (MessagingException e) {
      System.err.println("Could not inspect legacy MIME headers; preserving original bytes.");
      return raw;
    }
  }

  private static String firstNonBlank(String[] values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return null;
  }

  private static void restoreHeader(MimeMessage message, String name, String value)
      throws MessagingException {
    if (value == null) {
      message.removeHeader(name);
    } else {
      message.setHeader(name, value);
    }
  }

  /** Returns a Gmail-safe RFC 2822 date while retaining the source instant. */
  private static String normalizedDate(String sourceDate) {
    if (sourceDate == null || sourceDate.trim().isEmpty()) {
      return null;
    }
    String trimmed = sourceDate.trim();
    if (RFC2822_DATE.matcher(trimmed).matches()) {
      return trimmed;
    }

    ParsePosition position = new ParsePosition(0);
    Date parsed = new MailDateFormat().parse(trimmed, position);
    if (parsed == null || position.getIndex() != trimmed.length()) {
      // Thunderbird/Outlook-era exports commonly use "Mon May 12 09:11:45 2008".
      SimpleDateFormat legacy = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US);
      legacy.setLenient(false);
      try {
        parsed = legacy.parse(trimmed);
      } catch (java.text.ParseException e) {
        return null;
      }
    }
    return new MailDateFormat().format(parsed);
  }

  /**
   * Uses the earliest parseable delivery timestamp when old mail lacks an RFC822 Date header.
   * Received headers are prepended hop by hop, so the oldest parseable value is the least likely
   * to be an import or later relay time.
   */
  private static String earliestReceivedDate(MimeMessage message) throws MessagingException {
    Date earliest = null;
    String[] receivedHeaders = message.getHeader("Received");
    if (receivedHeaders == null) {
      return null;
    }
    for (String received : receivedHeaders) {
      int separator = received.lastIndexOf(';');
      if (separator < 0 || separator == received.length() - 1) {
        continue;
      }
      ParsePosition position = new ParsePosition(0);
      Date candidate = new MailDateFormat().parse(received.substring(separator + 1).trim(), position);
      if (candidate != null && position.getIndex() > 0 && (earliest == null || candidate.before(earliest))) {
        earliest = candidate;
      }
    }
    return earliest == null ? null : new MailDateFormat().format(earliest);
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(String.format("%02x", value & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required", e);
    }
  }

  private String canonicalIdentity() {
    String id = getMessageId();
    if (id != null && !id.startsWith("generated:")) {
      return "message-id:" + id;
    }
    return "generated:" + sha256(raw);
  }
}
