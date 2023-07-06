/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logsearch.layout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.jackson.ContextDataSerializer;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Plugin(name = "LogSearchJsonLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class LogSearchJsonLayout extends AbstractStringLayout {

  private final ObjectMapper objectMapper;
  private static final String NEW_LINE = System.getProperty("line.separator");

  public LogSearchJsonLayout(Charset charset) {
    super(charset);
    SimpleModule module = new SimpleModule();
    module.addSerializer(LogEvent.class, new LogEventSerializer());
    module.addSerializer(ReadOnlyStringMap.class, new ContextDataSerializer() {
    });
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(module);
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
  }

  @Override
  public String toSerializable(LogEvent logEvent) {
    try {
      return objectMapper.writeValueAsString(logEvent) + NEW_LINE;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static class LogEventSerializer extends StdSerializer<LogEvent> {
    LogEventSerializer() {
      super(LogEvent.class);
    }

    @Override
    public void serialize(LogEvent value, JsonGenerator gen, SerializerProvider provider) throws IOException {
      gen.writeStartObject();
      gen.writeStringField("level", value.getLevel().name());
      gen.writeStringField("thread_name", value.getThreadName());
      gen.writeStringField("logger_name", value.getLoggerName());
      if (value.getSource() != null) {
        StackTraceElement source = value.getSource();
        if (source.getFileName() != null) {
          gen.writeStringField("file", source.getFileName());
        }
        gen.writeNumberField("line_number", source.getLineNumber());
      }
      gen.writeObjectField("log_message", getLogMessage(value));
      gen.writeStringField("logtime", Long.toString(value.getTimeMillis()));
      gen.writeEndObject();
    }

    private String getLogMessage(LogEvent logEvent) {
      String logMessage = logEvent.getMessage() != null ? logEvent.getMessage().getFormattedMessage() : "";
      if (logEvent.getThrown() != null) {
        logMessage += NEW_LINE;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        logEvent.getThrown().printStackTrace(pw);
        logMessage += sw.toString();
      }
      return logMessage;
    }
  }

  @PluginBuilderFactory
  public static <B extends Builder<B>> B newBuilder() {
    return new Builder<B>().asBuilder();
  }

  public static class Builder<B extends Builder<B>> extends org.apache.logging.log4j.core.layout.AbstractStringLayout.Builder<B> implements org.apache.logging.log4j.core.util.Builder<LogSearchJsonLayout> {
    Builder() {
      this.setCharset(StandardCharsets.UTF_8);
    }

    public LogSearchJsonLayout build() {
      return new LogSearchJsonLayout(this.getCharset());
    }
  }

}
