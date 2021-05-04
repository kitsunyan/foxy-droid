package com.revrobotics;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A result sent to us by the Control Hub Updater
 *
 * May be a status update, a prompt, an error, or a success message.
 */
public final class ChUpdaterResult {
  @NonNull private final ChUpdaterResultType resultType;
  @Nullable private String detailMessage;
  @Nullable private Throwable cause;

  public ChUpdaterResult(@NonNull ChUpdaterResultType resultType, @Nullable String detailMessage, @Nullable Throwable cause) {
    this.resultType = resultType;
    this.detailMessage = detailMessage;
    this.cause = cause;
  }

  public Category getCategory() {
    return resultType.getCategory();
  }

  public int getCode() {
    return resultType.getCode();
  }

  public String getMessage() {
    String message = resultType.getMessage();
    if (getDetailMessageType() == DetailMessageType.SUBSTITUTED) {
      String localDetailMessage = detailMessage; // Save a copy of the detail message so we don't overwrite the real one. BTW, it's important not to use getDetailMessage here.
      if (localDetailMessage == null) {
        localDetailMessage = "";
      }
      message = String.format(message, localDetailMessage);
    }
    return message;
  }

  @Nullable
  public String getDetailMessage() {
    if (getDetailMessageType() == DetailMessageType.SUBSTITUTED) {
      return null; // The detail message will be returned as a part of the main message. We should pretend it doesn't exist outside of that.
    }
    return detailMessage;
  }

  public DetailMessageType getDetailMessageType() {
    return resultType.getDetailMessageType();
  }

  /**
   * Get the cause of the error (may be null)
   */
  @Nullable
  public Throwable getCause() {
    return cause;
  }

  public ChUpdaterResultType getResultType() {
    return resultType;
  }

  public PresentationType getPresentationType() {
    return resultType.getPresentationType();
  }

  /**
   * Parse a Bundle into a ChUpdaterResult
   */
  public static ChUpdaterResult fromBundle(Bundle bundle) {
    // Build the ChUpdaterResultType object
    Category category = Category.fromString(bundle.getString(ChUpdaterConstants.RESULT_BUNDLE_CATEGORY_KEY));
    int code = bundle.getInt(ChUpdaterConstants.RESULT_BUNDLE_CODE_KEY);
    String message = bundle.getString(ChUpdaterConstants.RESULT_BUNDLE_MESSAGE_KEY);
    PresentationType presentationType = PresentationType.fromString(bundle.getString(ChUpdaterConstants.RESULT_BUNDLE_PRESENTATION_TYPE_KEY));
    DetailMessageType detailMessageType = DetailMessageType.fromString(bundle.getString(ChUpdaterConstants.RESULT_BUNDLE_DETAIL_MESSAGE_TYPE_KEY));
    ChUpdaterResultType ChUpdaterResultType = new ChUpdaterResultType(category, code, presentationType, detailMessageType, message);

    // Return a new Result object
    String detailMessage = bundle.getString(ChUpdaterConstants.RESULT_BUNDLE_DETAIL_MESSAGE_KEY);
    Throwable cause = (Throwable) bundle.getSerializable(ChUpdaterConstants.RESULT_BUNDLE_CAUSE_KEY);
    return new ChUpdaterResult(ChUpdaterResultType, detailMessage, cause);
  }

  public final Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putString(ChUpdaterConstants.RESULT_BUNDLE_CATEGORY_KEY, getCategory().name());
    bundle.putString(ChUpdaterConstants.RESULT_BUNDLE_PRESENTATION_TYPE_KEY, getPresentationType().name());
    bundle.putString(ChUpdaterConstants.RESULT_BUNDLE_DETAIL_MESSAGE_TYPE_KEY, getDetailMessageType().name());
    bundle.putInt(ChUpdaterConstants.RESULT_BUNDLE_CODE_KEY, getCode());
    bundle.putString(ChUpdaterConstants.RESULT_BUNDLE_MESSAGE_KEY, getMessage());
    bundle.putString(ChUpdaterConstants.RESULT_BUNDLE_DETAIL_MESSAGE_KEY, getDetailMessage());
    bundle.putSerializable(ChUpdaterConstants.RESULT_BUNDLE_CAUSE_KEY, getCause());
    return bundle;
  }


  /**
   * Enum that specifies which ChUpdaterResultType category this result is
   */
  public enum Category {
    COMMON, OTA_UPDATE, APP_UPDATE;

    public static Category fromString(String string) {
      for (Category category : Category.values()) {
        if (category.name().equals(string)) return category;
      }
      return null;
    }
  }

  /**
   * Enum that specifies how this result is to be displayed to the user
   */
  public enum PresentationType {
    // Note: Status messages are intended to be non-dismissable
    SUCCESS, ERROR, STATUS, PROMPT;

    public static PresentationType fromString(String string) {
      for (PresentationType presentationType : PresentationType.values()) {
        if (presentationType.name().equals(string)) return presentationType;
      }
      return null;
    }
  }

  /**
   * Enum that specifies what should be done with the result's detail message
   */
  public enum DetailMessageType {
    LOGGED, // The detail message should merely be logged, if it is present
    DISPLAYED, // The detail message should be displayed, if it is present
    SUBSTITUTED; // The detail message should be provided by the CH updater, and will be injected into the main message. If no detail message is provided, a blank string will be injected.

    public static DetailMessageType fromString(String string) {
      for (DetailMessageType detailMessageType : DetailMessageType.values()) {
        if (detailMessageType.name().equals(string)) return detailMessageType;
      }
      return null;
    }
  }
}
