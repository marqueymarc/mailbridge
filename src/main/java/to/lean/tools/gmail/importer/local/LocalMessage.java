/*
 * Copyright 2015 The Mail Importer Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package to.lean.tools.gmail.importer.local;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides a generic interface to messages on local storage that can be used get the contents and
 * applicable labels for the message.
 */
public interface LocalMessage {
  /** Returns the RFC822 message id of the message. */
  String getMessageId();

  /** Returns the From header of the message. */
  String getFromHeader();

  /**
   * Returns a list of folders that the message is in. The names of the folders must be relative to
   * the root of the local store. For example, if the local store is at {@code
   * .../Mail/pop.host.com} and the message is in {@code .../Mail/pop.host.com/work/client/job},
   * then the folder should be named "work/client/job".
   *
   * <p>Note that if messages with the same id are in different folders and it is OK to not return
   * all of the names at once if the message in the other folder will be processed.
   */
  List<String> getFolders();

  /** Returns the raw, underlying bytes of the message. */
  byte[] getRawContent();

  boolean isUnread();

  boolean isStarred();

  /** Stable identity for restart-safe local import bookkeeping. */
  default String getCheckpointKey() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String folders = getFolders().stream().sorted().collect(Collectors.joining("\u001f"));
      digest.update(getRawContent());
      digest.update((byte) 0);
      digest.update(folders.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return getMessageId()
          + "\t"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required", e);
    }
  }

  /** Identity used to decide whether the message body has already been uploaded. */
  default String getUploadCheckpointKey() {
    return getCheckpointKey();
  }

  /** Identity used to decide whether this message has received its local-folder labels. */
  default String getLabelCheckpointKey() {
    return getCheckpointKey();
  }
}
