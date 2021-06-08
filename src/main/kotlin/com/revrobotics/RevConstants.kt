package com.revrobotics

import android.content.Context
import android.content.SharedPreferences
import nya.kitsunyan.foxydroid.MainApplication

object RevConstants {
  const val DRIVER_HUB_OS_CONTAINER_PACKAGE = "com.revrobotics.driverhuboscontainer"
  const val DRIVER_HUB_OS_VERSION_NAME_PROPERTY = "ro.driverhub.os.version"
  const val DRIVER_HUB_OS_VERSION_CODE_PROPERTY = "ro.driverhub.os.versionnum"

  const val JOB_ID_NOTIFY_ABOUT_STALE_REPOS = 2200

  const val NOTIF_CHANNEL_STALE_REPOS = "StaleRepos"
  const val NOTIF_CHANNEL_UPDATES = "UpdatesREV"

  const val NOTIF_ID_STALE_REPOS = 2200

  const val NOTIF_GROUP_SYNC_FAILED = "syncFailed"

  const val EXTRA_DISMISSED_NOTIF_ID = "notificationId"
  const val EXTRA_DISMISSED_NOTIF_UPDATES_LIST = "updatesList"

  val SHARED_PREFS: SharedPreferences = MainApplication.instance.getSharedPreferences("revrobotics", Context.MODE_PRIVATE)

  private const val PREF_AUTO_INSTALL_OS_ON_NEXT_LAUNCH = "autoInstallOsOnNextLaunch"
  private const val PREF_AUTO_INSTALL_OS_WHEN_DOWNLOAD_COMPLETES = "autoInstallOsWhenDownloadCompletes"
  private const val PREF_DISMISSED_UPDATE_NOTIFICATION_PACKAGES = "dismissedUpdateNotificationPackages"
  private const val PREF_USER_DISMISSED_STALE_REPOS_NOTIFICATION = "userDismissedStaleReposNotification"

  var shouldAutoInstallOSWhenDownloadCompletes: Boolean
    get() {
      return SHARED_PREFS.getBoolean(PREF_AUTO_INSTALL_OS_WHEN_DOWNLOAD_COMPLETES, true)
    }
    set(value) {
      SHARED_PREFS.edit().putBoolean(PREF_AUTO_INSTALL_OS_WHEN_DOWNLOAD_COMPLETES, value).apply()
    }

  var shouldAutoInstallOsOnNextLaunch: Boolean
    get() {
      return SHARED_PREFS.getBoolean(PREF_AUTO_INSTALL_OS_ON_NEXT_LAUNCH, true)
    }
    set(value) {
      SHARED_PREFS.edit().putBoolean(PREF_AUTO_INSTALL_OS_ON_NEXT_LAUNCH, value).apply()
    }

  /**
   * The packages that were displayed in the update notification at the time it was dismissed.
   *
   * An empty list indicates that the update notification has not been dismissed since the last boot.
   */
  var dismissedUpdateNotificationPackages: Set<String>
    get() {
      return SHARED_PREFS.getStringSet(PREF_DISMISSED_UPDATE_NOTIFICATION_PACKAGES, emptySet())?.toSet().orEmpty()
    }
    set(value) {
      SHARED_PREFS.edit().putStringSet(PREF_DISMISSED_UPDATE_NOTIFICATION_PACKAGES, value).apply()
    }

  var userDismissedStaleReposNotification: Boolean
    get() {
      return SHARED_PREFS.getBoolean(PREF_USER_DISMISSED_STALE_REPOS_NOTIFICATION, false)
    }
    set(value) {
      SHARED_PREFS.edit().putBoolean(PREF_USER_DISMISSED_STALE_REPOS_NOTIFICATION, value).apply()
    }
}
