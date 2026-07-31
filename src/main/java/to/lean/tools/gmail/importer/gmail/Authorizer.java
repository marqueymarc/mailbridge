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

package to.lean.tools.gmail.importer.gmail;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Profile;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Provider;
import to.lean.tools.gmail.importer.CommandLineArguments;

/** Encapsulates the authorization and authentication flow. */
class Authorizer implements Provider<Credential> {
  private static final String CALLBACK_PATH = "/oauth2callback";
  private static final String REDIRECT_URI = "http://localhost:8080" + CALLBACK_PATH;
  private HttpTransport httpTransport;
  private JsonFactory jsonFactory;
  private User user;
  private CommandLineArguments commandLineArguments;

  @Inject
  public Authorizer(
      User user,
      HttpTransport httpTransport,
      JsonFactory jsonFactory,
      CommandLineArguments commandLineArguments) {
    this.httpTransport = httpTransport;
    this.jsonFactory = jsonFactory;
    this.user = user;
    this.commandLineArguments = commandLineArguments;
  }

  public Credential get() {
    try {
      GoogleClientSecrets clientSecrets = loadGoogleClientSecrets(jsonFactory);

      DataStore<StoredCredential> dataStore = getStoredCredentialDataStore();

      // Allow user to authorize via url.
      GoogleAuthorizationCodeFlow flow =
          new GoogleAuthorizationCodeFlow.Builder(
                  httpTransport,
                  jsonFactory,
                  clientSecrets,
                  ImmutableList.of(GmailScopes.GMAIL_MODIFY, GmailScopes.GMAIL_READONLY))
              .setCredentialDataStore(dataStore)
              .setAccessType("offline")
              .setApprovalPrompt("auto")
              .build();

      // First, see if we have a stored credential for the user.
      Credential credential = flow.loadCredential(user.getEmailAddress());

      // If we don't, prompt them to get one.
      if (credential == null) {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8080), 0);
        CountDownLatch callbackReceived = new CountDownLatch(1);
        AtomicReference<String> authorizationCode = new AtomicReference<>();
        AtomicReference<String> authorizationError = new AtomicReference<>();
        server.createContext(
            CALLBACK_PATH,
            exchange ->
                handleOAuthCallback(
                    exchange, callbackReceived, authorizationCode, authorizationError));
        server.start();
        try {
          String url = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
          System.out.println(
              "Please open the following URL in your browser to authorize "
                  + user.getEmailAddress()
                  + ":\n"
                  + url);
          System.out.println("Waiting for the localhost OAuth callback on " + REDIRECT_URI + "...");
          try {
            if (!callbackReceived.await(5, TimeUnit.MINUTES)) {
              throw new IOException("Timed out waiting for the localhost OAuth callback");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the localhost OAuth callback", exception);
          }
          if (authorizationError.get() != null) {
            throw new IOException("OAuth authorization failed: " + authorizationError.get());
          }
          String code = authorizationCode.get();
          if (code == null || code.isEmpty()) {
            throw new IOException("OAuth callback did not contain an authorization code");
          }
          GoogleTokenResponse response =
              flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute();
          credential = flow.createAndStoreCredential(response, user.getEmailAddress());
        } finally {
          server.stop(0);
        }
      }

      Gmail gmail =
          new Gmail.Builder(httpTransport, jsonFactory, credential)
              .setApplicationName(GmailServiceModule.APP_NAME)
              .build();

      Profile profile;
      try {
        profile = gmail.users().getProfile(user.getEmailAddress()).execute();
      } catch (GoogleJsonResponseException exception) {
        if (exception.getStatusCode() != 401 || credential.getRefreshToken() == null) {
          throw exception;
        }
        // A long-running import can leave a cached access token invalid without
        // marking it expired locally. Refresh once so a checkpointed restart can
        // resume without requiring another interactive authorization flow.
        if (!credential.refreshToken()) {
          throw exception;
        }
        profile = gmail.users().getProfile(user.getEmailAddress()).execute();
      }

      System.out.println(profile.toPrettyString());
      return credential;
    } catch (IOException exception) {
      throw new RuntimeException(exception);
    }
  }

  private GoogleClientSecrets loadGoogleClientSecrets(JsonFactory jsonFactory) throws IOException {
    Path configured = Paths.get(commandLineArguments.clientSecretPath).toAbsolutePath();
    if (Files.isRegularFile(configured)) {
      try (java.io.Reader reader = Files.newBufferedReader(configured, StandardCharsets.UTF_8)) {
        return GoogleClientSecrets.load(jsonFactory, reader);
      }
    }
    URL url = Resources.getResource("client_secret.json");
    CharSource inputSupplier = Resources.asCharSource(url, Charsets.UTF_8);
    return GoogleClientSecrets.load(jsonFactory, inputSupplier.openStream());
  }

  private static void handleOAuthCallback(
      HttpExchange exchange,
      CountDownLatch callbackReceived,
      AtomicReference<String> authorizationCode,
      AtomicReference<String> authorizationError)
      throws IOException {
    Map<String, String> parameters = parseQuery(exchange.getRequestURI().getRawQuery());
    authorizationCode.set(parameters.get("code"));
    authorizationError.set(parameters.get("error"));
    byte[] response =
        "<html><body>Authorization received. You may close this window.</body></html>"
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
    exchange.sendResponseHeaders(200, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(response);
    } finally {
      callbackReceived.countDown();
    }
  }

  private static Map<String, String> parseQuery(String query) throws IOException {
    Map<String, String> parameters = new HashMap<>();
    if (query == null || query.isEmpty()) {
      return parameters;
    }
    for (String pair : query.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], "UTF-8");
      String value = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
      parameters.put(key, value);
    }
    return parameters;
  }

  private DataStore<StoredCredential> getStoredCredentialDataStore() throws IOException {
    File mailimporter = new File(commandLineArguments.credentialStorePath);
    if (!mailimporter.isAbsolute()) {
      mailimporter = new File(System.getProperty("user.dir"), commandLineArguments.credentialStorePath);
    }
    if (!mailimporter.exists() && !mailimporter.mkdirs()) {
      throw new IOException("Could not create credential store: " + mailimporter);
    }
    FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(mailimporter);
    return dataStoreFactory.getDataStore("credentials");
  }
}
