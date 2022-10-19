package com.revrobotics;

/**
 * ResultType is built at runtime from the information sent by the ControlHubUpdater app.
 */
public class ChUpdaterResultType {
  private final ChUpdaterResult.Category category;
  private final int code;
  private final ChUpdaterResult.PresentationType presentationType;
  private final ChUpdaterResult.DetailMessageType detailMessageType;
  private final String message;

  public ChUpdaterResultType(ChUpdaterResult.Category category, int code, ChUpdaterResult.PresentationType presentationType, ChUpdaterResult.DetailMessageType detailMessageType, String message) {
    this.category = category;
    this.code = code;
    this.presentationType = presentationType;
    this.detailMessageType = detailMessageType;
    this.message = message;
  }

  public ChUpdaterResult.Category getCategory() {
    return category;
  }

  public int getCode() {
    return code;
  }

  public ChUpdaterResult.PresentationType getPresentationType() {
    return presentationType;
  }

  public ChUpdaterResult.DetailMessageType getDetailMessageType() {
    return detailMessageType;
  }

  public String getMessage() {
    return message;
  }
}
