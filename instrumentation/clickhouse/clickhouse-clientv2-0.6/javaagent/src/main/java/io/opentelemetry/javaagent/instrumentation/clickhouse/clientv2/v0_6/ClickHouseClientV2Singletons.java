/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.clickhouse.clientv2.v0_6;

import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.javaagent.instrumentation.clickhouse.common.ClickHouseDbRequest;
import io.opentelemetry.javaagent.instrumentation.clickhouse.common.ClickHouseInstrumenterFactory;

public final class ClickHouseClientV2Singletons {

  private static final String INSTRUMENTER_NAME = "io.opentelemetry.clickhouse-clientv2-0.6";
  private static final Instrumenter<ClickHouseDbRequest, Void> INSTRUMENTER;

  static {
    INSTRUMENTER =
        ClickHouseInstrumenterFactory.createInstrumenter(
            INSTRUMENTER_NAME, ClickHouseClientV2AttributesGetter.create());
  }

  public static Instrumenter<ClickHouseDbRequest, Void> instrumenter() {
    return INSTRUMENTER;
  }

  private ClickHouseClientV2Singletons() {}
}
