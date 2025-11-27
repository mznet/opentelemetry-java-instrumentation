/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opensearch.rest;

import static io.opentelemetry.instrumentation.testing.junit.db.DbClientMetricsTestUtil.assertDurationMetric;
import static io.opentelemetry.instrumentation.testing.junit.db.SemconvStabilityUtil.maybeStable;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static io.opentelemetry.semconv.DbAttributes.DB_OPERATION_NAME;
import static io.opentelemetry.semconv.DbAttributes.DB_SYSTEM_NAME;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE;
import static io.opentelemetry.semconv.NetworkAttributes.NETWORK_PROTOCOL_VERSION;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS;
import static io.opentelemetry.semconv.ServerAttributes.SERVER_PORT;
import static io.opentelemetry.semconv.UrlAttributes.URL_FULL;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_OPERATION;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_STATEMENT;
import static io.opentelemetry.semconv.incubating.DbIncubatingAttributes.DB_SYSTEM;
import static io.opentelemetry.semconv.incubating.PeerIncubatingAttributes.PEER_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseListener;
import org.opensearch.client.RestClient;
import org.opensearch.testcontainers.OpensearchContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("deprecation") // using deprecated semconv
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractOpenSearchRestTest {

  private static final String INDEX_NAME = "test-search-index";

  protected OpensearchContainer opensearch;
  protected RestClient client;
  protected URI httpHost;

  protected abstract InstrumentationExtension getTesting();

  protected abstract RestClient buildRestClient() throws Exception;

  protected abstract int getResponseStatus(Response response);

  protected abstract String getInstrumentationName();

  @BeforeAll
  void setUp() throws Exception {
    opensearch =
        new OpensearchContainer(DockerImageName.parse("opensearchproject/opensearch:1.3.6"))
            .withSecurityEnabled();
    // limit memory usage and disable Log4j JMX to avoid cgroup detection issues in containers
    opensearch.withEnv(
        "OPENSEARCH_JAVA_OPTS",
        "-Xmx256m -Xms256m -Dlog4j2.disableJmx=true -Dlog4j2.disable.jmx=true -XX:-UseContainerSupport");
    opensearch.start();
    httpHost = URI.create(opensearch.getHttpHostAddress());

    client = buildRestClient();

    // Create index
    String createIndexBody =
        "{\"mappings\":{\"properties\":{\"message\":{\"type\":\"text\",\"fielddata\":true,\"analyzer\":\"standard\"}}}}";
    Request createIndexRequest = new Request("PUT", INDEX_NAME);
    createIndexRequest.setJsonEntity(createIndexBody);
    client.performRequest(createIndexRequest);

    // Index a document
    String documentBody = "{\"id\":\"test-doc-1\",\"message\":\"test message for search\"}";
    Request indexRequest = new Request("POST", INDEX_NAME + "/_doc");
    indexRequest.setJsonEntity(documentBody);
    client.performRequest(indexRequest);

    // Refresh index
    client.performRequest(new Request("POST", INDEX_NAME + "/_refresh"));
  }

  @AfterAll
  void tearDown() {
    opensearch.stop();
  }

  @Test
  void shouldGetStatusWithTraces() throws IOException {
    Response response = client.performRequest(new Request("GET", "_cluster/health"));
    assertThat(getResponseStatus(response)).isEqualTo(200);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        span.hasName("GET")
                            .hasKind(SpanKind.CLIENT)
                            .hasAttributesSatisfyingExactly(
                                equalTo(maybeStable(DB_SYSTEM), "opensearch"),
                                equalTo(maybeStable(DB_OPERATION), "GET"),
                                equalTo(maybeStable(DB_STATEMENT), "GET _cluster/health")),
                    span ->
                        span.hasName("GET")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(NETWORK_PROTOCOL_VERSION, "1.1"),
                                equalTo(SERVER_ADDRESS, httpHost.getHost()),
                                equalTo(SERVER_PORT, httpHost.getPort()),
                                equalTo(HTTP_REQUEST_METHOD, "GET"),
                                equalTo(PEER_SERVICE, "test-peer-service"),
                                equalTo(URL_FULL, httpHost + "/_cluster/health"),
                                equalTo(HTTP_RESPONSE_STATUS_CODE, 200L))));
  }

  @Test
  void shouldGetStatusAsyncWithTraces() throws Exception {
    AtomicReference<Response> requestResponse = new AtomicReference<>(null);
    AtomicReference<Exception> exception = new AtomicReference<>(null);
    CountDownLatch countDownLatch = new CountDownLatch(1);

    ResponseListener responseListener =
        new ResponseListener() {
          @Override
          public void onSuccess(Response response) {
            getTesting()
                .runWithSpan(
                    "callback",
                    () -> {
                      requestResponse.set(response);
                      countDownLatch.countDown();
                    });
          }

          @Override
          public void onFailure(Exception e) {
            getTesting()
                .runWithSpan(
                    "callback",
                    () -> {
                      exception.set(e);
                      countDownLatch.countDown();
                    });
          }
        };

    getTesting()
        .runWithSpan(
            "client",
            () -> {
              client.performRequestAsync(new Request("GET", "_cluster/health"), responseListener);
            });
    countDownLatch.await();

    if (exception.get() != null) {
      throw exception.get();
    }
    assertThat(getResponseStatus(requestResponse.get())).isEqualTo(200);

    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("client").hasKind(SpanKind.INTERNAL),
                    span ->
                        span.hasName("GET")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(maybeStable(DB_SYSTEM), "opensearch"),
                                equalTo(maybeStable(DB_OPERATION), "GET"),
                                equalTo(maybeStable(DB_STATEMENT), "GET _cluster/health")),
                    span ->
                        span.hasName("GET")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(NETWORK_PROTOCOL_VERSION, "1.1"),
                                equalTo(SERVER_ADDRESS, httpHost.getHost()),
                                equalTo(SERVER_PORT, httpHost.getPort()),
                                equalTo(HTTP_REQUEST_METHOD, "GET"),
                                equalTo(PEER_SERVICE, "test-peer-service"),
                                equalTo(URL_FULL, httpHost + "/_cluster/health"),
                                equalTo(HTTP_RESPONSE_STATUS_CODE, 200L)),
                    span ->
                        span.hasName("callback")
                            .hasKind(SpanKind.INTERNAL)
                            .hasParent(trace.getSpan(0))));
  }

  @Test
  void shouldRecordMetrics() throws IOException {
    Response response = client.performRequest(new Request("GET", "_cluster/health"));
    assertThat(getResponseStatus(response)).isEqualTo(200);

    getTesting().waitForTraces(1);

    assertDurationMetric(getTesting(), getInstrumentationName(), DB_OPERATION_NAME, DB_SYSTEM_NAME);
  }

  @Test
  void shouldCaptureSearchQueryBody() throws IOException {
    // Execute search query with body
    String searchBody = "{\"query\":{\"match\":{\"message\":{\"query\":\"test\"}}}}";
    Request searchRequest = new Request("POST", INDEX_NAME + "/_search");
    searchRequest.setJsonEntity(searchBody);

    Response response = client.performRequest(searchRequest);
    assertThat(getResponseStatus(response)).isEqualTo(200);

    // Verify trace includes query body in DB_STATEMENT with exact match
    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        span.hasName("POST")
                            .hasKind(SpanKind.CLIENT)
                            .hasAttributesSatisfyingExactly(
                                equalTo(maybeStable(DB_SYSTEM), "opensearch"),
                                equalTo(maybeStable(DB_OPERATION), "POST"),
                                // DB_STATEMENT should exactly match the extracted JSON query body
                                equalTo(
                                    maybeStable(DB_STATEMENT),
                                    "{\"query\":{\"match\":{\"message\":{\"query\":\"?\"}}}}")),
                    span ->
                        span.hasName("POST")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(NETWORK_PROTOCOL_VERSION, "1.1"),
                                equalTo(SERVER_ADDRESS, httpHost.getHost()),
                                equalTo(SERVER_PORT, httpHost.getPort()),
                                equalTo(HTTP_REQUEST_METHOD, "POST"),
                                satisfies(
                                    URL_FULL,
                                    url ->
                                        url.asString()
                                            .startsWith(httpHost + "/" + INDEX_NAME + "/_search")),
                                equalTo(HTTP_RESPONSE_STATUS_CODE, 200L),
                                equalTo(PEER_SERVICE, "test-peer-service"))));
  }

  @Test
  void shouldCaptureMsearchQueryBody() throws IOException {
    // Execute multi-search query with body
    // Multi-search format: header (with index) followed by body (query), separated by newlines
    String msearchBody =
        "{\"index\":\""
            + INDEX_NAME
            + "\"}\n"
            + "{\"query\":{\"term\":{\"message\":{\"value\":\"message\"}}}}\n"
            + "{\"index\":\""
            + INDEX_NAME
            + "\"}\n"
            + "{\"query\":{\"term\":{\"message\":{\"value\":100}}}}\n"
            + "{\"index\":\""
            + INDEX_NAME
            + "\"}\n"
            + "{\"query\":{\"term\":{\"message\":{\"value\":true}}}}\n";
    ;

    Request msearchRequest = new Request("POST", "_msearch");
    msearchRequest.setJsonEntity(msearchBody);

    Response response = client.performRequest(msearchRequest);
    assertThat(getResponseStatus(response)).isEqualTo(200);

    // Verify trace includes query body in DB_STATEMENT with exact match
    getTesting()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        span.hasName("POST")
                            .hasKind(SpanKind.CLIENT)
                            .hasAttributesSatisfyingExactly(
                                equalTo(maybeStable(DB_SYSTEM), "opensearch"),
                                equalTo(maybeStable(DB_OPERATION), "POST"),
                                // DB_STATEMENT should exactly match the extracted JSON query body
                                equalTo(
                                    maybeStable(DB_STATEMENT),
                                    "{\"index\":\"?\"};{\"query\":{\"term\":{\"message\":{\"value\":\"?\"}}}};{\"index\":\"?\"};{\"query\":{\"term\":{\"message\":{\"value\":\"?\"}}}};{\"index\":\"?\"};{\"query\":{\"term\":{\"message\":{\"value\":\"?\"}}}}")),
                    span ->
                        span.hasName("POST")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(NETWORK_PROTOCOL_VERSION, "1.1"),
                                equalTo(SERVER_ADDRESS, httpHost.getHost()),
                                equalTo(SERVER_PORT, httpHost.getPort()),
                                equalTo(HTTP_REQUEST_METHOD, "POST"),
                                satisfies(
                                    URL_FULL,
                                    url -> url.asString().startsWith(httpHost + "/_msearch")),
                                equalTo(HTTP_RESPONSE_STATUS_CODE, 200L),
                                equalTo(PEER_SERVICE, "test-peer-service"))));
  }
}
