package org.apache.ambari.view.k8s.service;

import com.google.gson.*;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;
import java.lang.reflect.Type;

import java.lang.reflect.Type;
import java.time.ZonedDateTime;          // ← 4 lignes à ajouter
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.temporal.TemporalAccessor; 

@Provider                    // Jersey le découvre automatiquement
public class GsonConfigurator implements ContextResolver<Gson> {

  private final Gson gson;

  public GsonConfigurator() {
    gson = new GsonBuilder()
        .registerTypeAdapter(ZonedDateTime.class,  (JsonSerializer<ZonedDateTime>)  this::asString)
        .registerTypeAdapter(OffsetDateTime.class, (JsonSerializer<OffsetDateTime>) this::asString)
        .registerTypeAdapter(LocalDateTime.class,  (JsonSerializer<LocalDateTime>)  this::asString)
        .registerTypeAdapter(Instant.class,        (JsonSerializer<Instant>)        this::asString)
        .create();
  }

  private JsonElement asString(TemporalAccessor t, Type type, JsonSerializationContext ctx) {
    return new JsonPrimitive(t.toString());             // ISO-8601
  }

  @Override
  public Gson getContext(Class<?> type) {
    return gson;    // Jersey l’utilise pour toutes les réponses JSON
  }
}
