package to.lean.tools.gmail.importer;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class CheckpointStoreTest {

  @Test
  public void persistsCompletedKeysAcrossInstances() throws Exception {
    Path directory = Files.createTempDirectory("mail-importer-checkpoint");
    Path checkpoint = directory.resolve("state/checkpoint.tsv");

    CheckpointStore first = new CheckpointStore(checkpoint.toString());
    assertThat(first.isCompleted("message-1\tsha")).isFalse();
    first.markCompleted("message-1\tsha", "gmail-123");
    assertThat(first.isCompleted("message-1\tsha")).isTrue();

    CheckpointStore second = new CheckpointStore(checkpoint.toString());
    assertThat(second.isCompleted("message-1\tsha")).isTrue();
    second.markCompleted("message-1\tsha", "gmail-123");
    assertThat(Files.readAllLines(checkpoint)).hasSize(1);
  }

  @Test
  public void recordsInFlightBeforeCompletionAndReplaysIt() throws Exception {
    Path checkpoint = Files.createTempDirectory("mail-importer-checkpoint").resolve("journal.tsv");
    CheckpointStore first = new CheckpointStore(checkpoint.toString());
    first.markInFlight("message-1");

    CheckpointStore restarted = new CheckpointStore(checkpoint.toString());
    assertThat(restarted.isInFlight("message-1")).isTrue();
    restarted.markCompleted("message-1", "gmail-1");

    CheckpointStore completed = new CheckpointStore(checkpoint.toString());
    assertThat(completed.isCompleted("message-1")).isTrue();
    assertThat(completed.isInFlight("message-1")).isFalse();
  }

  @Test
  public void preservesAcceptedUploadUntilItsLabelIsConfirmed() throws Exception {
    Path checkpoint = Files.createTempDirectory("mail-importer-checkpoint").resolve("journal.tsv");
    CheckpointStore first = new CheckpointStore(checkpoint.toString());
    first.markInFlight("message-1");
    first.markUploaded("message-1", "gmail-1");

    CheckpointStore restarted = new CheckpointStore(checkpoint.toString());
    assertThat(restarted.isInFlight("message-1")).isFalse();
    assertThat(restarted.getUploadedGmailMessageId("message-1")).isEqualTo("gmail-1");
    restarted.markCompleted("message-1", "gmail-1");

    assertThat(new CheckpointStore(checkpoint.toString()).isCompleted("message-1")).isTrue();
  }

  @Test
  public void ignoresOnlyATruncatedFinalRecord() throws Exception {
    Path checkpoint = Files.createTempDirectory("mail-importer-checkpoint").resolve("journal.tsv");
    Files.write(checkpoint, java.util.Arrays.asList("completed\tbXNn\tZ21haWw", "in_flight\t%%%"));

    CheckpointStore restored = new CheckpointStore(checkpoint.toString());
    assertThat(restored.isCompleted("msg")).isTrue();
  }
}
