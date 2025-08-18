package org.apache.ambari.view.k8s.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

@Entity
@Table(name = "k8s_cmd_status")
public class CommandStatusEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(length = 128)
  private String viewInstance;

  @Column(length = 64)
  private String workerId;         // identifiant du process/worker en charge

  @Column
  private Integer attempt;         // nb de tentatives

  @Column(length = 32, nullable = false)
  private String type;             // enum name

  @Column(length = 32, nullable = false)
  private String state;            // enum name

  @Column
  private Integer percent;

  @Column
  private Integer step;

  @Column(length = 512)
  private String message;

  @Column(length = 40)
  private String createdAt;

  @Column(length = 40)
  private String updatedAt;

  @Column(length = 2048)
  private String error;

  @Lob
  private String requestJson;

  @Lob
  private String resultJson;

  // getters / setters
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getViewInstance() { return viewInstance; }
  public void setViewInstance(String viewInstance) { this.viewInstance = viewInstance; }
  public String getWorkerId() { return workerId; }
  public void setWorkerId(String workerId) { this.workerId = workerId; }
  public Integer getAttempt() { return attempt; }
  public void setAttempt(Integer attempt) { this.attempt = attempt; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public Integer getPercent() { return percent; }
  public void setPercent(Integer percent) { this.percent = percent; }
  public Integer getStep() { return step; }
  public void setStep(Integer step) { this.step = step; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getCreatedAt() { return createdAt; }
  public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
  public String getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
  public String getError() { return error; }
  public void setError(String error) { this.error = error; }
  public String getRequestJson() { return requestJson; }
  public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
  public String getResultJson() { return resultJson; }
  public void setResultJson(String resultJson) { this.resultJson = resultJson; }
}
