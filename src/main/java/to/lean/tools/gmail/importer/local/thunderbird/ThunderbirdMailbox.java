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

package to.lean.tools.gmail.importer.local.thunderbird;

import com.google.inject.Inject;
import java.io.IOException;
import javax.mail.MessagingException;
import to.lean.tools.gmail.importer.CommandLineArguments;
import to.lean.tools.gmail.importer.MailProvider;
import to.lean.tools.gmail.importer.local.LocalStorage;

/** Reads a Thunderbird mailbox. */
class ThunderbirdMailbox implements MailProvider<LocalStorage> {

  private final CommandLineArguments commandLineArguments;

  @Inject
  ThunderbirdMailbox(CommandLineArguments commandLineArguments) {
    this.commandLineArguments = commandLineArguments;
  }

  public LocalStorage get() throws MessagingException {
    try {
      return new StreamingMboxStorage(commandLineArguments.mailboxFileName);
    } catch (IOException e) {
      throw new MessagingException(e.getMessage(), e);
    }
  }
}
