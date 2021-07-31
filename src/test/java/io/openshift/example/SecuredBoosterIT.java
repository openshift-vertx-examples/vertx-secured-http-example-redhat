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
package io.openshift.example;

import org.arquillian.cube.kubernetes.impl.utils.CommandExecutor;
import org.arquillian.cube.openshift.impl.enricher.AwaitRoute;
import org.arquillian.cube.openshift.impl.enricher.RouteURL;
import org.arquillian.spacelift.execution.ExecutionException; 
import org.jboss.arquillian.junit.Arquillian;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

@RunWith(Arquillian.class)
public class SecuredBoosterIT {

  @RouteURL("${app.name}")
  @AwaitRoute
  private URL exampleEndpoint;

  private static final CommandExecutor COMMAND_EXECUTOR = new CommandExecutor();
  private static String ssoEndpoint;

  @BeforeClass
  public static void init() {
    // You can disable the sso server initialization by setting the system property skip.sso.init to true
    if (!Boolean.getBoolean("skip.sso.init")) {
      try {
      // Remove service account
        COMMAND_EXECUTOR.execCommand("oc delete sa sso-service-account");
      } catch (ExecutionException ee) {
        if (ee.getMessage().contains("NotFound")) {
          // NotFound is an expected exception
          // Do nothing
        } else {
          throw ee; // Re-throw exception
        }
      }

      COMMAND_EXECUTOR.execCommand("oc create -f service.sso.yaml");
    }
    ssoEndpoint = COMMAND_EXECUTOR
      .execCommand("oc get route secure-sso -o jsonpath='{\"https://\"}{.spec.host}{\"/auth\"}'")
      .get(0)
      .replace("'", ""); // for some reason, the string contains a single ' before the URL

    /* Await the sso server to be ready so we can make token requests. We cannot use @AwaitRoute as we are deploying
    the sso server as part of the tests.
    When making requests too early (the sso server is not ready yet), it throws SSLHandshakeException,
    so we are taking care of that in the try-catch here. */
    await().atMost(5, TimeUnit.MINUTES).until(() -> {
      try {
        return given().relaxedHTTPSValidation().when().get(ssoEndpoint).statusCode() == 200;
      } catch (Exception ex) {
        return false;
      }
    });
  }

  @Test
  public void defaultUser_defaultFrom() {
    String token = getToken("alice", "password");

    given().header("Authorization", "Bearer " + token)
      .when().get(exampleEndpoint.toString() + "api/greeting")
      .then().body("content", equalTo("Hello, World!"));
  }

  @Test
  public void defaultUser_customFrom() {
    String token = getToken("alice", "password");

    given().header("Authorization", "Bearer " + token)
      .when().get(exampleEndpoint.toString() + "api/greeting?name=Scott")
      .then().body("content", equalTo("Hello, Scott!"));
  }

  @Test
  public void adminUser() {
    String token = getToken("admin", "admin");

    given().header("Authorization", "Bearer " + token)
      .when().get(exampleEndpoint.toString() + "api/greeting")
      .then().statusCode(403); // should be 403 Forbidden, as the admin user does not have the required role
  }

  @Test
  public void badPassword() {
    String token = getToken("alice", "bad");

    given().header("Authorization", "Bearer " + token)
      .when().get(exampleEndpoint.toString() + "api/greeting?name=Scott")
      .then().statusCode(401); // should be 401 Unauthorized, as auth fails because of providing bad password (token is null)
  }

  // SSO server cleanup
  @AfterClass
  public static void deleteSSO() {
    if (!Boolean.getBoolean("skip.sso.init")) {
      try {
        COMMAND_EXECUTOR.execCommand("oc delete serviceaccount sso-service-account");
      } catch (ExecutionException ee) {
        if (ee.getMessage().contains("NotFound")) {
          // NotFound is an expected exception
          // Do nothing
        } else {
          throw ee; // Re-throw exception
        }
      }

      COMMAND_EXECUTOR.execCommand("oc delete all --selector application=sso");
      COMMAND_EXECUTOR.execCommand("oc delete secret sso-app-secret");
      COMMAND_EXECUTOR.execCommand("oc delete secret sso-demo-secret");
    }
  }

  private String getToken(String username, String password) {
    Map<String, String> requestParams = new HashMap<>();
    try {
      requestParams.put("grant_type", "password");
      requestParams.put("username", URLEncoder.encode(username, "UTF8"));
      requestParams.put("password", URLEncoder.encode(password, "UTF8"));
      requestParams.put("client_id", "demoapp");
      requestParams.put("client_secret", "1daa57a2-b60e-468b-a3ac-25bd2dc2eadc");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }

    return given()
      .relaxedHTTPSValidation()
      .params(requestParams)
      .when()
      .post(ssoEndpoint + "/realms/demo/protocol/openid-connect/token")
      .path("access_token");
  }
}
