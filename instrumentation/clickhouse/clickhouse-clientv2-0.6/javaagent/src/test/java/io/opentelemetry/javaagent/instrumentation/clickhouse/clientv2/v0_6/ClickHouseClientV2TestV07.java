/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.clickhouse.clientv2.v0_6;

import static io.opentelemetry.javaagent.instrumentation.clickhouse.testing.ClickHouseAttributeAssertions.attributeAssertions;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.semconv.DbAttributes.DB_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.ErrorAttributes.ERROR_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.enums.Protocol;
import com.clickhouse.client.api.query.QueryResponse;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.api.internal.SemconvStability;
import io.opentelemetry.sdk.testing.assertj.AttributeAssertion;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
class ClickHouseClientV2TestV07 extends ClickHouseClientV2TestV06 {

  @Override
  @BeforeAll
  void setup() throws Exception {
    clickhouseServer.start();
    port = clickhouseServer.getMappedPort(8123);
    host = clickhouseServer.getHost();

    client =
        new Client.Builder()
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

  @Override
  @Test
  void testQueryThrowsServerException() {
    Throwable thrown =
        catchThrowable(
            () -> {
              QueryResponse response = client.query("select * from non_existent_table").get();
              response.close();
            });

    assertThat(thrown).isInstanceOf(ServerException.class);

    List<AttributeAssertion> assertions =
        new ArrayList<>(
            attributeAssertions(dbName, host, port, "select * from non_existent_table", "SELECT"));
    if (SemconvStability.emitStableDatabaseSemconv()) {
      assertions.add(equalTo(DB_RESPONSE_STATUS_CODE, "60"));
      assertions.add(equalTo(ERROR_TYPE, "com.clickhouse.client.api.ServerException"));
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
}
