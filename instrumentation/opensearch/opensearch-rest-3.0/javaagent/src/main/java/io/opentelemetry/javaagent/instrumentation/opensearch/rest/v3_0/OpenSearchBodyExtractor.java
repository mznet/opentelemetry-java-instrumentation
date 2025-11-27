/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.opensearch.rest.v3_0;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.hc.core5.http.HttpEntity;

public class OpenSearchBodyExtractor {
  public static String extract(HttpEntity httpEntity) {
    try (InputStream inputStream = httpEntity.getContent()) {

      if (inputStream == null) {
        return null;
      }

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }

      return baos.toString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }

  private OpenSearchBodyExtractor() {}
}
