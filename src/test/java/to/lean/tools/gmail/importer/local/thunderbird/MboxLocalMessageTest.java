/*
 * Copyright 2026 Marc. Licensed under the Apache License, Version 2.0.
 */

package to.lean.tools.gmail.importer.local.thunderbird;

import static com.google.common.truth.Truth.assertThat;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.util.Date;
import javax.mail.internet.MailDateFormat;
import org.junit.Test;

public class MboxLocalMessageTest {

  @Test
  public void normalizesDuplicateFromHeadersForUpload() {
    byte[] raw =
        ("From: first@example.com\n"
                + "From: second@example.com\n"
                + "Message-ID: <duplicate@example.com>\n"
                + "Date: Tue, 1 Jan 2019 00:00:00 +0000\n"
                + "\n"
                + "body\n")
            .getBytes(StandardCharsets.UTF_8);

    String uploaded =
        new String(new MboxLocalMessage(raw, "Legacy marc.old").getRawContent(), StandardCharsets.UTF_8);

    assertThat(uploaded).contains("From: first@example.com");
    assertThat(uploaded).doesNotContain("From: second@example.com");
    assertThat(uploaded).contains("Message-ID: <duplicate@example.com>");
    assertThat(uploaded).contains("Date: Tue, 1 Jan 2019 00:00:00 +0000");
  }

  @Test
  public void derivesMissingDateFromEarliestReceivedHeaderWithoutReplacingMessageId() {
    byte[] raw =
        ("From: sender@example.com\n"
                + "Message-ID: <source@example.com>\n"
                + "Received: from late.example; Fri, 21 May 2010 20:51:13 +0000\n"
                + "Received: from early.example; Fri, 21 May 2010 20:50:39 +0000\n"
                + "Subject: date-less legacy message\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    String uploaded =
        new String(new MboxLocalMessage(raw, "Legacy marc.old").getRawContent(), StandardCharsets.UTF_8);

    assertThat(uploaded).contains("Message-ID: <source@example.com>");
    assertThat(uploaded).contains("Date:");
    assertThat(parseHeaderDate(uploaded).getTime()).isEqualTo(1274475039000L);
  }

  @Test
  public void canonicalizesLegacyDateFormsForGmail() {
    byte[] raw =
        ("From: sender@example.com\n"
                + "Message-ID: <legacy-date@example.com>\n"
                + "Date: Mon May 12 09:11:45 2008\n"
                + "Subject: legacy date form\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    String uploaded =
        new String(new MboxLocalMessage(raw, "Legacy marc.old").getRawContent(), StandardCharsets.UTF_8);

    assertThat(parseHeaderDate(uploaded).getTime()).isEqualTo(1210608705000L);
    assertThat(uploaded).contains("Message-ID: <legacy-date@example.com>");
  }

  @Test
  public void doesNotInventDateOrMessageIdWhenNoTrustworthySourceExists() {
    byte[] raw =
        ("From: sender@example.com\nSubject: date-less and header-less legacy message\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    String uploaded =
        new String(new MboxLocalMessage(raw, "Legacy marc.old").getRawContent(), StandardCharsets.UTF_8);

    assertThat(uploaded).doesNotContain("Date:");
    assertThat(uploaded).doesNotContain("Message-ID:");
  }

  @Test
  public void checkpointIdentityUsesOriginalBytes() {
    byte[] first =
        ("From: first@example.com\nMessage-ID: <same@example.com>\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] second =
        ("From: second@example.com\nMessage-ID: <same@example.com>\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    String firstKey = new MboxLocalMessage(first, "Legacy marc.old").getCheckpointKey();
    String secondKey = new MboxLocalMessage(second, "Legacy marc.old").getCheckpointKey();

    assertThat(firstKey).isNotEqualTo(secondKey);
  }

  @Test
  public void uploadIdentityIsStableAcrossFoldersButLabelsAreNot() {
    byte[] raw =
        ("From: first@example.com\nMessage-ID: <same@example.com>\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    MboxLocalMessage inbox = new MboxLocalMessage(raw, "Legacy marc.old/Inbox");
    MboxLocalMessage archive = new MboxLocalMessage(raw, "Legacy marc.old/Archived");

    assertThat(inbox.getUploadCheckpointKey()).isEqualTo(archive.getUploadCheckpointKey());
    assertThat(inbox.getLabelCheckpointKey()).isNotEqualTo(archive.getLabelCheckpointKey());
    assertThat(inbox.getUploadCheckpointKey()).startsWith("v4-upload\t");
    assertThat(inbox.getLabelCheckpointKey()).startsWith("v3-label\t");
  }

  @Test
  public void missingMessageIdUsesDeterministicRawHash() {
    byte[] raw =
        ("From: first@example.com\nSubject: no id\n\nbody\n")
            .getBytes(StandardCharsets.UTF_8);

    MboxLocalMessage first = new MboxLocalMessage(raw, "Legacy marc.old");
    MboxLocalMessage second = new MboxLocalMessage(raw, "Legacy marc.old");

    assertThat(first.getMessageId()).isEqualTo(second.getMessageId());
    assertThat(first.getMessageId()).startsWith("generated:");
  }

  private static Date parseHeaderDate(String raw) {
    for (String line : raw.split("\\r?\\n")) {
      if (line.startsWith("Date:")) {
        ParsePosition position = new ParsePosition(0);
        Date date = new MailDateFormat().parse(line.substring("Date:".length()).trim(), position);
        if (date != null && position.getIndex() > 0) {
          return date;
        }
      }
    }
    throw new AssertionError("No parseable Date header: " + raw);
  }
}
