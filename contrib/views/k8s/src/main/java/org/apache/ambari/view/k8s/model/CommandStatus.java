package org.apache.ambari.view.k8s.model;

import java.util.Map;

public class CommandStatus {
  public String id;
  public CommandType type;
  public CommandState state;
  public int percent;           // 0..100
  public int step;              // 0..N (UI: Steps)
  public String message;        // petit texte "Validation…", "Sync dépôt…", etc.
  public String createdAt;      // ISO-8601 string
  public String updatedAt;      // ISO-8601 string
  public String error;          // stack/short message
  public Map<String,Object> result; // ex: release DTO

  public static CommandStatus pending(String id, CommandType t, String now) {
    CommandStatus s = new CommandStatus();
    s.id = id;
    s.type = t;
    s.state = CommandState.PENDING;
    s.percent = 0;
    s.step = 0;
    s.message = "En file d'attente…";
    s.createdAt = now;
    s.updatedAt = now;
    return s;
  }
}
