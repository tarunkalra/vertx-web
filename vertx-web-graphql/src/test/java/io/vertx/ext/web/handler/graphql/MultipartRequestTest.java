/*
 * Copyright 2023 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.ext.web.handler.graphql;

import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.WebTestBase;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.Test;

import java.util.List;

class Result {
  private final String id;

  Result(final String id) {
    this.id = id;
  }
}

public class MultipartRequestTest extends WebTestBase {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    GraphQLHandler graphQLHandler = GraphQLHandler.builder(graphQL()).with(createOptions()).build();
    router.route().handler(BodyHandler.create());
    router.route("/graphql").order(100).handler(graphQLHandler);
  }

  private GraphQLHandlerOptions createOptions() {
    return new GraphQLHandlerOptions().setRequestMultipartEnabled(true)
      .setRequestBatchingEnabled(true);
  }

  private GraphQL graphQL() {
    final String schema = vertx.fileSystem().readFileBlocking("upload.graphqls").toString();
    final String emptyQueryschema = vertx.fileSystem().readFileBlocking("emptyQuery.graphqls").toString();

    final SchemaParser schemaParser = new SchemaParser();
    final TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema)
      .merge(schemaParser.parse(emptyQueryschema));

    final RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
      .scalar(UploadScalar.build())
      .type("Mutation", builder -> {
        builder.dataFetcher("singleUpload", this::singleUpload);
        builder.dataFetcher("multipleUpload", this::multipleUpload);
        return builder;
      }).build();

    final SchemaGenerator schemaGenerator = new SchemaGenerator();
    final GraphQLSchema graphQLSchema = schemaGenerator
      .makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    return GraphQL.newGraphQL(graphQLSchema).build();
  }

  @Test
  public void testSingleUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("singleUpload.txt");

    client.request(new RequestOptions()
      .setMethod(HttpMethod.POST)
      .setURI("/graphql")
      .setIdleTimeout(10000)
      .addHeader("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryBpwmk50wSJmsTPAH")
      .addHeader("accept", "application/json"))
      .compose(req -> req.send(bodyBuffer)
        .compose(HttpClientResponse::body)
      ).onComplete(onSuccess(buffer -> {
      final JsonObject json = ((JsonObject) buffer.toJson()).getJsonObject("data").getJsonObject("singleUpload");
      assertEquals("a.txt", json.getString("id"));
      complete();
    }));

    await();
  }

  @Test
  public void testMultipleUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("multipleUpload.txt");
    client.request(new RequestOptions()
      .setMethod(HttpMethod.POST)
      .setURI("/graphql")
      .addHeader("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryhvb6BzAACEqQKt0Z")
      .addHeader("accept", "application/json")
      .setIdleTimeout(10000))
      .compose(req -> req.send(bodyBuffer).compose(HttpClientResponse::body))
      .onComplete(onSuccess(buffer -> {
        final JsonObject json = ((JsonObject) buffer.toJson()).getJsonObject("data").getJsonObject("multipleUpload");
        assertEquals("b.txt c.txt", json.getString("id"));
        complete();
      }));

    await();
  }

  @Test
  public void testBatchUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("batchUpload.txt");
    client.request(new RequestOptions()
        .setMethod(HttpMethod.POST)
        .setURI("/graphql")
        .addHeader("Content-Type", "multipart/form-data; boundary=------------------------560b6209af099a26")
        .addHeader("accept", "application/json")
        .setIdleTimeout(10000))
      .compose(req -> req.send(bodyBuffer).compose(HttpClientResponse::body))
      .onComplete(onSuccess(buffer -> {
      final JsonObject result = new JsonObject("{ \"array\":" + buffer.toString() + "}");
      assertEquals("a.txt", result.getJsonArray("array")
        .getJsonObject(0).getJsonObject("data")
        .getJsonObject("singleUpload").getString("id")
      );

      assertEquals("b.txt c.txt", result.getJsonArray("array")
        .getJsonObject(1).getJsonObject("data")
        .getJsonObject("multipleUpload").getString("id")
      );

      complete();
      }));

    await();
  }

  private Object singleUpload(DataFetchingEnvironment env) {
    final FileUpload file = env.getArgument("file");
    return new Result(file.fileName());
  }

  private Object multipleUpload(DataFetchingEnvironment env) {
    final List<FileUpload> files = env.getArgument("files");
    return new Result(files.get(0).fileName() + " " + files.get(1).fileName());
  }
}

