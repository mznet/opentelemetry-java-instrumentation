/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.lettuce.v5_0.rx;

import static io.opentelemetry.javaagent.instrumentation.lettuce.v5_0.LettuceSingletons.instrumenter;

import io.lettuce.core.protocol.RedisCommand;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.incubator.config.internal.DeclarativeConfigUtil;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

public class LettuceMonoTerminationHandler<R, T>
    implements Consumer<R>, BiConsumer<T, Throwable>, Runnable {

  private static final boolean CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES =
      DeclarativeConfigUtil.getInstrumentationConfig(GlobalOpenTelemetry.get(), "lettuce")
          .getBoolean("experimental_span_attributes/development", false);

  private final RedisCommand<?, ?, ?> command;
  private final boolean finishSpanOnClose;
  private Context context;

  public LettuceMonoTerminationHandler(RedisCommand<?, ?, ?> command, boolean finishSpanOnClose) {
    this.command = command;
    this.finishSpanOnClose = finishSpanOnClose;
  }

  @Override
  public void accept(R r) {
    context = instrumenter().start(Context.current(), command);
    if (finishSpanOnClose) {
      finishSpan(false, null);
    }
  }

  @Override
  public void accept(T t, Throwable throwable) {
    finishSpan(false, throwable);
  }

  @Override
  public void run() {
    finishSpan(true, null);
  }

  private void finishSpan(boolean isCommandCancelled, Throwable throwable) {
    if (context != null) {
      if (CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES) {
        Span span = Span.fromContext(context);
        if (isCommandCancelled) {
          span.setAttribute("lettuce.command.cancelled", true);
        }
      }
      instrumenter().end(context, command, null, throwable);
    } else {
      Logger.getLogger(Mono.class.getName())
          .severe(
              "Failed to end this.context, LettuceMonoTerminationHandler cannot find this.context "
                  + "because it probably wasn't started.");
    }
  }
}
