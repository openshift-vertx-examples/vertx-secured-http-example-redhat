/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openshift.booster;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.net.URLEncoder;

@RunWith(VertxUnitRunner.class)
public class SecuredBoosterIT {

  private Vertx vertx;
  private static String ssoEndpoint;
  private static String boosterEndpoint;

  @BeforeClass
  public static void init() throws IOException, InterruptedException {
    // Use a ProcessBuilder
    ProcessBuilder pb = new ProcessBuilder("oc", "get", "routes");

    Process p = pb.start();
    InputStream is = p.getInputStream();
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    String line;
    while ((line = br.readLine()) != null) {
      String[] tokens = line.split("\\s+");
      if ("secure-sso".equals(tokens[0])) {
        ssoEndpoint = tokens[1];
        continue;
      }
      if ("secured-vertx-http".equals(tokens[0])) {
        boosterEndpoint = tokens[1];
      }
    }

    int r = p.waitFor(); // Let the process finish.
    if (r != 0) { // error
      throw new RuntimeException("oc exit code: " + r);
    }
  }

  @Before
  public void before(TestContext context) {
    vertx = Vertx.vertx();
    vertx.exceptionHandler(context.exceptionHandler());
  }

  @After
  public void after(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }

  private void getToken(String username, String password, Handler<AsyncResult<String>> handler) {
    Buffer form = Buffer.buffer();

    try {
      form
        .appendString("client_id=demoapp&")
        .appendString("client_secret=1daa57a2-b60e-468b-a3ac-25bd2dc2eadc&")
        .appendString("grant_type=password&")
        .appendString("username=" + URLEncoder.encode(username, "UTF8") + "&")
        .appendString("password=" + URLEncoder.encode(password, "UTF8"));
    } catch (UnsupportedEncodingException e) {
      handler.handle(Future.failedFuture(e));
      return;
    }

    final HttpClient client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));

    client
      .post(443, ssoEndpoint, "/auth/realms/master/protocol/openid-connect/token", res -> {
        res.exceptionHandler(t -> {
          handler.handle(Future.failedFuture(t));
          client.close();
        });

        res.bodyHandler(body -> {
          if (res.statusCode() != 200) {
            if (body.length() > 0) {
              handler.handle(Future.failedFuture(body.toString()));
            } else {
              handler.handle(Future.failedFuture(res.statusMessage()));
            }
          } else {
            try {
              JsonObject token = new JsonObject(body);
              handler.handle(Future.succeededFuture(token.getString("access_token")));
            } catch (RuntimeException e) {
              handler.handle(Future.failedFuture(e));
            }
          }
        });
      })
      .exceptionHandler(t -> {
        handler.handle(Future.failedFuture(t));
        client.close();
      })
      .putHeader("Accept", "application/json")
      .putHeader("Content-Type", "application/x-www-form-urlencoded")
      .putHeader("Content-Length", Integer.toString(form.length()))
      .write(form)
      .end();
  }

  private void callAPI(String token, String uri, Handler<AsyncResult<JsonObject>> handler) {

    final HttpClient client = vertx.createHttpClient();

    client
      .get(80, boosterEndpoint, uri, res -> {
        res.exceptionHandler(t -> {
          handler.handle(Future.failedFuture(t));
          client.close();
        });

        res.bodyHandler(body -> {
          if (res.statusCode() != 200) {
            if (body.length() > 0) {
              handler.handle(Future.failedFuture(body.toString()));
            } else {
              handler.handle(Future.failedFuture(res.statusMessage()));
            }
          } else {
            try {
              handler.handle(Future.succeededFuture(new JsonObject(body)));
            } catch (RuntimeException e) {
              handler.handle(Future.failedFuture(e));
            }
          }
        });
      })
      .exceptionHandler(t -> {
        handler.handle(Future.failedFuture(t));
        client.close();
      })
      .putHeader("Authorization", "Bearer " + token)
      .end();
  }

  @Test
  public void defaultUser_defaultFrom(TestContext context) {
    final Async test = context.async();

    getToken("alice", "password", res -> {
      if (res.failed()) {
        context.fail(res.cause());
      } else {
        callAPI(res.result(), "/greeting", res2 -> {
          if (res2.failed()) {
            context.fail(res2.cause());
          } else {
            context.assertEquals("Hello, World!", res2.result().getString("content"));
            test.complete();
          }
        });
      }
    });
  }

  @Test
  public void defaultUser_customFrom(TestContext context) {
    final Async test = context.async();

    getToken("alice", "password", res -> {
      if (res.failed()) {
        context.fail(res.cause());
      } else {
        callAPI(res.result(), "/greeting?name=Scott", res2 -> {
          if (res2.failed()) {
            context.fail(res2.cause());
          } else {
            context.assertEquals("Hello, Scott!", res2.result().getString("content"));
            test.complete();
          }
        });
      }
    });
  }

  @Test
  public void adminUser(TestContext context) {
    final Async test = context.async();

    getToken("admin", "admin", res -> {
      if (res.failed()) {
        context.fail(res.cause());
      } else {
        callAPI(res.result(), "/greeting", res2 -> {
          if (res2.failed()) {
            // must fail as the admin user does not have the required role
            test.complete();
          } else {
            context.fail("wrong role should fail!");
          }
        });
      }
    });
  }

  @Test
  public void badPassword(TestContext context) {
    final Async test = context.async();

    getToken("alice", "bad", res -> {
      if (res.failed()) {
        // must fail as the admin user does not have the required role
        test.complete();
      } else {
        context.fail("Bad password should not pass!");
      }
    });
  }
}
