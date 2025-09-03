/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.clickhouse.clientv2.v0_6;

import static io.opentelemetry.instrumentation.testing.junit.db.DbClientMetricsTestUtil.assertDurationMetric;
import static io.opentelemetry.javaagent.instrumentation.clickhouse.testing.ClickHouseAttributeAssertions.attributeAssertions;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.semconv.DbAttributes.DB_NAMESPACE;
import static io.opentelemetry.semconv.DbAttributes.DB_OPERATION_NAME;
import static io.opentelemetry.semconv.DbAttributes.DB_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.DbAttributes.DB_SYSTEM_NAME;
import static io.opentelemetry.semconv.ErrorAttributes.ERROR_TYPE;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_PORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientException;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.enums.Protocol;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.sdk.testing.assertj.AttributeAssertion;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;

@TestInstance(Lifecycle.PER_CLASS)
public class ClickHouseClientV2TestV06 {

  @RegisterExtension
  protected static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  protected static final GenericContainer<?> clickhouseServer =
      new GenericContainer<>("clickhouse/clickhouse-server:24.4.2").withExposedPorts(8123);

  protected static final String dbName = "default";
  protected static final String tableName = "test_table";
  protected static int port;
  protected static String host;
  protected static Client client;
  protected static final String username = "default";
  protected static final String password = "";
  protected String clickhouseVersion = System.getProperty("clickhouse.client.version", "0.6.4");

  @SuppressWarnings("deprecation") // using useNewImplementation
  @BeforeAll
  void setup() throws Exception {
    clickhouseServer.start();
    port = clickhouseServer.getMappedPort(8123);
    host = clickhouseServer.getHost();

    client =
        new Client.Builder()
            .useNewImplementation(true)
            .addEndpoint(Protocol.HTTP, host, port, false)
            .setDefaultDatabase(dbName)
            .setUsername(username)
            .setPassword(password)
            .setOption("compress", "false")
            .build();

    QueryResponse response =
        client
            .query("create table if not exists " + tableName + "(value String) engine=Memory")
            .get();
    response.close();

    // wait for CREATE operation and clear
    testing.waitForTraces(1);
    testing.clearData();
  }

  @AfterAll
  void cleanup() {
    if (client != null) {
      client.close();
    }
    clickhouseServer.stop();
  }

  @Test
  void testConnectionStringWithoutDatabaseSpecifiedStillGeneratesSpans() throws Exception {
    Client client =
        new Client.Builder()
            .addEndpoint(Protocol.HTTP, host, port, false)
            .setOption("compress", "false")
            .setUsername(username)
            .setPassword(password)
            .build();

    QueryResponse response = client.query("select * from " + tableName).get();
    response.close();

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasNoParent()
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName, host, port, "select * from " + tableName, "SELECT"))));

    assertDurationMetric(
        testing,
        "io.opentelemetry.clickhouse-clientv2-0.6",
        DB_SYSTEM_NAME,
        DB_OPERATION_NAME,
        DB_NAMESPACE,
        SERVER_ADDRESS,
        SERVER_PORT);
  }

  @Test
  void testQueryWithStringQuery() throws Exception {
    testing.runWithSpan(
        "parent",
        () -> {
          QueryResponse response =
              client.query("insert into " + tableName + " values('1')('2')('3')").get();
          response.close();

          response = client.query("select * from " + tableName).get();
          response.close();
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("INSERT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "insert into " + tableName + " values(?)(?)(?)",
                                "INSERT")),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName, host, port, "select * from " + tableName, "SELECT"))));
  }

  @Test
  void testQueryWithStringQueryAndId() throws Exception {
    testing.runWithSpan(
        "parent",
        () -> {
          QuerySettings querySettings = new QuerySettings();
          querySettings.setQueryId("test_query_id");

          QueryResponse response = client.query("select * from " + tableName, querySettings).get();
          response.close();
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName, host, port, "select * from " + tableName, "SELECT"))));
  }

  @Test
  void testQueryThrowsServerException() {
    Throwable thrown =
        catchThrowable(
            () -> {
              QueryResponse response = client.query("select * from non_existent_table").get();
              response.close();
            });

    assertThat(thrown).isInstanceOf(ClientException.class);

    List<AttributeAssertion> assertions =
        new ArrayList<>(
            attributeAssertions(dbName, host, port, "select * from non_existent_table", "SELECT"));
    if (SemconvStability.emitStableDatabaseSemconv()) {
      assertions.add(equalTo(DB_RESPONSE_STATUS_CODE, null));
      assertions.add(equalTo(ERROR_TYPE, "com.clickhouse.client.api.ClientException"));
    }
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasStatus(StatusData.error())
                        .hasException(thrown)
                        .hasAttributesSatisfyingExactly(assertions)));
  }

  @Test
  void testSendQuery() throws Exception {
    testing.runWithSpan(
        "parent",
        () -> {
          CompletableFuture<CommandResponse> future =
              client.execute("select * from " + tableName + " limit 1");
          CommandResponse results = future.get();
          assertThat(results.getReadRows()).isEqualTo(0);
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "select * from " + tableName + " limit ?",
                                "SELECT"))));
  }

  @Test
  void testSendQueryAll() throws Exception {
    testing.runWithSpan(
        "parent",
        () -> {
          List<GenericRecord> records = client.queryAll("select * from " + tableName + " limit 1");
          assertThat(records.size()).isEqualTo(0);
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "select * from " + tableName + " limit ?",
                                "SELECT"))));
  }

  @Test
  void testSendQueryRecords() throws Exception {
    testing.runWithSpan(
        "parent",
        () -> {
          Records records =
              client.queryRecords("insert into " + tableName + " values('test_value')").get();
          records.close();

          records = client.queryRecords("select * from " + tableName + " limit 1").get();
          records.close();
          assertThat(records.getReadRows()).isEqualTo(1);
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("INSERT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "insert into " + tableName + " values(?)",
                                "INSERT")),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "select * from " + tableName + " limit ?",
                                "SELECT"))));
  }

  @Test
  void testPlaceholderQuery() throws Exception {
    Map<String, Object> queryParams = new HashMap<>();
    queryParams.put("param_s", Instant.now().getEpochSecond());

    testing.runWithSpan(
        "parent",
        () -> {
          QueryResponse response =
              client
                  .query(
                      "select * from " + tableName + " where value={param_s: String}",
                      queryParams,
                      null)
                  .get();
          response.close();
        });

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasNoParent().hasAttributes(Attributes.empty()),
                span ->
                    span.hasName("SELECT " + dbName)
                        .hasKind(SpanKind.CLIENT)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(
                            attributeAssertions(
                                dbName,
                                host,
                                port,
                                "select * from " + tableName + " where value={param_s: String}",
                                "SELECT"))));
  }
}
