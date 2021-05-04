package com.revrobotics

/**
 * Constants used in communication with the Control Hub Updater
 */
object ChUpdaterConstants {
  const val CONTROL_HUB_UPDATER_PACKAGE = "com.revrobotics.controlhubupdater"
  const val CONTROL_HUB_UPDATE_SERVICE = "com.revrobotics.controlhubupdater.UpdateService"
  const val ACTION_APPLY_OTA_UPDATE = "com.revrobotics.controlhubupdater.action.APPLY_OTA_UPDATE"
  const val ACTION_UPDATE_FTC_APP = "com.revrobotics.controlhubupdater.action.UPDATE_FTC_APP"
  const val EXTRA_UPDATE_FILE_PATH = "com.revrobotics.controlhubupdater.extra.UPDATE_FILE_PATH"
  const val EXTRA_RESULT_RECEIVER = "com.revrobotics.controlhubupdater.extra.RESULT_RECEIVER"

  // Result broadcast values (broadcasts are sent when the system does an action on boot, or when the ResultReceiver instance has been invalidated because the app restarted)
  const val RESULT_BROADCAST = "com.revrobotics.controlhubupdater.broadcast.RESULT_BROADCAST"
  const val RESULT_BROADCAST_BUNDLE_EXTRA = "com.revrobotics.controlhubupdater.broadcast.extra.BUNDLE" // Contains the exact same bundle as would have been passed to a ResultReceiver instance
  const val RESULT_BUNDLE_CATEGORY_KEY = "category" // Will contain a String representation of Result.Category
  const val RESULT_BUNDLE_PRESENTATION_TYPE_KEY = "presentationType" // Will contain a String representation of Result.PresentationType
  const val RESULT_BUNDLE_DETAIL_MESSAGE_TYPE_KEY = "detailMessageType" // Will contain a String representation of Result.DetailMessageType
  const val RESULT_BUNDLE_CODE_KEY = "resultCode" // Will contain an int.
  const val RESULT_BUNDLE_MESSAGE_KEY = "message" // Will contain a String, which can be ignored if you know what the result code means.
  const val RESULT_BUNDLE_CAUSE_KEY = "cause" // Will contain either null or a Throwable. Should be logged if not null.
  const val RESULT_BUNDLE_DETAIL_MESSAGE_KEY = "detailMessage" // Will contain either null or a String. Should be logged if not null.

  // Extras that we can send with intents as parameters
  const val EXTRA_DANGEROUS_ACTION_CONFIRMED = "com.revrobotics.controlhubupdater.extra.DANGEROUS_ACTION_CONFIRMED" // Optional, populate with boolean
}

