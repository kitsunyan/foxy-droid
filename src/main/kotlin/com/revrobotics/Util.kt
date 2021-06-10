package com.revrobotics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.ArraySet
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nya.kitsunyan.foxydroid.Common
import nya.kitsunyan.foxydroid.MainActivity
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.utility.extension.resources.getColorFromAttr
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// This file was written by REV Robotics, but should only contain code that could be ported to upstream Foxy Droid.

val mainThreadHandler = Handler(Looper.getMainLooper())
val notificationManager = MainApplication.instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
val connectivityManager = MainApplication.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


private val downloadQueuingExecutor = Executors.newSingleThreadExecutor()

// queueDownloadAndUpdate function added by REV Robotics on 2021-05-09
fun queueDownloadAndUpdate(packageName: String, downloadConnection: Connection<DownloadService.Binder, DownloadService>) {
  downloadQueuingExecutor.submit {
    val repositoryMap = Database.RepositoryAdapter.getAll(null)
        .asSequence()
        .map {
          Pair(it.id, it)
        }.toMap()

    val products = Database.ProductAdapter.get(packageName, null)
    val product = if (products.isNotEmpty()) {
      products[0]
    } else {
      null
    }
    val repository = repositoryMap[product?.repositoryId]
    val installedApp = Database.InstalledAdapter.get(packageName, null)
    val compatibleReleases = product?.selectedReleases?.filter {
      installedApp == null || installedApp.signature == it.signature
    }
    val multipleCompatibleReleases = !(compatibleReleases == null || compatibleReleases.size < 2)
    val release  = if (multipleCompatibleReleases) {
      compatibleReleases!!
          .filter { it.platforms.contains(Android.primaryPlatform) }
          .minByOrNull { it.platforms.size }
          ?: compatibleReleases.minByOrNull { it.platforms.size }
          ?: compatibleReleases.firstOrNull()
    } else {
      compatibleReleases?.firstOrNull()
    }

    mainThreadHandler.post {
      if (product != null && repository != null && release != null) {
        downloadConnection.binder?.enqueue(packageName, product.name, repository, release)
      }
    }
  }
}

object LastUpdateOfAllReposTracker {
  private const val LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX = "lastUpdateCheckRepo"
  
  private val lastUpdateChangedCallbacks: MutableSet<()->Unit> = ArraySet()

  var lastUpdateOfAllRepos: Instant = Instant.MIN
    private set
    get() {
      synchronized(this) {
        return field
      }
    }

  val timeSinceLastUpdateOfAllRepos: Duration
    get() {
      return Duration.between(lastUpdateOfAllRepos, Instant.now())
    }

  /**
   * @returns true when the repos have not been updated for 6 weeks or more.
   */
  val reposAreVeryStale: Boolean
    get() {
      return timeSinceLastUpdateOfAllRepos > durationOfWeeks(6)
    }

  /**
   * Adds a callback that will be called once the time that all repositories were last updated changes.
   */
  fun addTimestampChangedCallback(callback: ()->Unit) {
    synchronized(lastUpdateChangedCallbacks) {
      lastUpdateChangedCallbacks.add(callback)
    }
  }

  fun removeTimestampChangedCallback(callback: ()->Unit) {
    synchronized(lastUpdateChangedCallbacks) {
      lastUpdateChangedCallbacks.remove(callback)
    }
  }

  fun markRepoAsJustDownloaded(repoId: Long) {
    RevConstants.SHARED_PREFS.edit().putLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + repoId, Instant.now().toEpochMilli()).apply()
    calculateLastDownloadOfAllReposInNewThread()
  }

  fun markRepoAsNeverDownloaded(repoId: Long) {
    RevConstants.SHARED_PREFS.edit().putLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + repoId, 0).apply()
    calculateLastDownloadOfAllReposInNewThread()
  }

  private fun calculateLastDownloadOfAllReposInNewThread() {
    thread {
      calculateLastDownloadOfAllRepos()
    }
  }

  private fun calculateLastDownloadOfAllRepos() {
    var updated = false
    synchronized(this) {
      var oldestRepoDownloadTime = Instant.MAX
      Database.RepositoryAdapter.getAll(null).asSequence()
          .filter { it.enabled }
          .forEach {
            val timeRepoWasLastUpdated = Instant.ofEpochMilli(RevConstants.SHARED_PREFS.getLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + it.id, 0))
            if (timeRepoWasLastUpdated < oldestRepoDownloadTime) {
              oldestRepoDownloadTime = timeRepoWasLastUpdated
            }
          }

      if (lastUpdateOfAllRepos != oldestRepoDownloadTime) {
        lastUpdateOfAllRepos = oldestRepoDownloadTime
        updated = true
      }
    }

    if (updated) {
      if (!reposAreVeryStale) {
        RevConstants.userDismissedStaleReposNotification = false // If the repos go stale again, we want the notification to show up
        dismissStaleReposNotification()
      }

      synchronized(lastUpdateChangedCallbacks) {
        for (callback in lastUpdateChangedCallbacks) {
          callback()
        }
      }
    }
  }
  
  init {
    calculateLastDownloadOfAllRepos()
  }
}

fun durationOfWeeks(weeks: Long): Duration {
  return Duration.ofDays(7 * weeks)
}

/**
 * This should be called even if productsAvailableForUpdate is empty
 */
fun refreshUpdatesAndStaleReposNotifications(productsAvailableForUpdate: List<ProductItem>) {
  if (productsAvailableForUpdate.isNotEmpty()) {
    // If any product available for update has not been included in a dismissed Updates notification, that means
    // there is new information, and we should show the Updates notification.
    for (packageAvailableForUpdate in productsAvailableForUpdate.map { it.packageName }) {
      if (packageAvailableForUpdate !in RevConstants.dismissedUpdateNotificationPackages) {
        // TODO(Noah): If dismissedUpdateNotificationPackages is not empty, note the number of new updates in the notification
        displayUpdatesNotification(productsAvailableForUpdate)
      }
    }
  } else if (LastUpdateOfAllReposTracker.reposAreVeryStale) { // Check for stale repos added by REV Robotics on 2021-06-04
    if (!RevConstants.userDismissedStaleReposNotification) {
      displayStaleReposNotification()
    }
  } else {
    dismissStaleReposNotification()
    dismissUpdatesNotification()
  }
}

/**
 * This function should ONLY be called if there are no updates known to be available
 */
private fun displayStaleReposNotification() {
  dismissUpdatesNotification()
  RevConstants.userDismissedStaleReposNotification = false
  NotificationChannel(RevConstants.NOTIF_CHANNEL_STALE_REPOS,
      "Check for update reminders", NotificationManager.IMPORTANCE_DEFAULT)
      .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
      .let(notificationManager::createNotificationChannel)

  val launchAppIntent = Intent(MainApplication.instance, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
  }
  val launchAppPendingIntent = PendingIntent.getActivity(MainApplication.instance, 0, launchAppIntent, 0)

  val notificationDismissedIntent = Intent(MainApplication.instance, MainApplication.NotificationDismissedReceiver::class.java)
      .apply {
        putExtra(RevConstants.EXTRA_DISMISSED_NOTIF_ID, RevConstants.NOTIF_ID_STALE_REPOS)
      }
  val notificationDismissedPendingIntent = PendingIntent.getBroadcast(
      MainApplication.instance,
      47926, // We just need to use a number that won't be used as a request code isn't used elsewhere.
      notificationDismissedIntent,
      PendingIntent.FLAG_UPDATE_CURRENT)

  val notification = NotificationCompat.Builder(MainApplication.instance, RevConstants.NOTIF_CHANNEL_STALE_REPOS)
      .setSmallIcon(R.drawable.ic_rev)
      .setContentTitle("Check for updates")
      .setStyle(NotificationCompat.BigTextStyle()
          .bigText("Please connect to the Internet so that the Driver Hub can check for updates"))
      .setContentText("Please connect to the Internet so that the Driver Hub can check for updates")
      .setContentIntent(launchAppPendingIntent)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setDeleteIntent(notificationDismissedPendingIntent)
      .build()
  NotificationManagerCompat.from(MainApplication.instance).notify(RevConstants.NOTIF_ID_STALE_REPOS, notification)
}

private fun dismissStaleReposNotification() {
  notificationManager.cancel(RevConstants.NOTIF_ID_STALE_REPOS)
}

// Copied from SyncService on 2021-06-06
// This function has been modified in subsequent commits
private fun displayUpdatesNotification(productItems: List<ProductItem>) {
  dismissStaleReposNotification() // The stale repos notification should only be displayed if no updates are available
  RevConstants.dismissedUpdateNotificationPackages = emptySet() // Make sure this notification is marked as un-dismissed

  // For the Driver Hub Software Manager, we moved creation of the Updates notification channel to this function, since
  // this function may be called before the SyncService is created.
  NotificationChannel(RevConstants.NOTIF_CHANNEL_UPDATES,
      MainApplication.instance.getString(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT)
      .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
      .let(notificationManager::createNotificationChannel)

  val maxUpdates = 5
  val shortDescription = if (productItems.size == 1) {
    productItems[0].name
  } else {
    MainApplication.instance.resources.getQuantityString(R.plurals.new_updates_DESC_FORMAT,
        productItems.size - 1, productItems[0].name, productItems.size - 1)
  }

  val updateTabIntent = Intent(MainApplication.instance, MainActivity::class.java)
      .apply { action = MainActivity.ACTION_UPDATES }
  val updateTabPendingIntent = PendingIntent.getActivity(
      MainApplication.instance,
      0,
      updateTabIntent,
      PendingIntent.FLAG_UPDATE_CURRENT)

  val updateAllIntent = Intent(MainApplication.instance, MainActivity::class.java)
      .apply { action = MainActivity.ACTION_UPDATE_ALL }
  val updateAllPendingIntent = PendingIntent.getActivity(
      MainApplication.instance,
      0,
      updateAllIntent,
      PendingIntent.FLAG_UPDATE_CURRENT)

  val notificationDismissedIntent = Intent(MainApplication.instance, MainApplication.NotificationDismissedReceiver::class.java)
      .apply {
        putExtra(RevConstants.EXTRA_DISMISSED_NOTIF_ID, Common.NOTIFICATION_ID_UPDATES)
        putExtra(RevConstants.EXTRA_DISMISSED_NOTIF_UPDATES_LIST, productItems.map { it.packageName }.toTypedArray())
      }
  val notificationDismissedPendingIntent = PendingIntent.getBroadcast(
      MainApplication.instance,
      982518, // We just need to use a number that won't be used as a request code elsewhere.
      notificationDismissedIntent,
      PendingIntent.FLAG_UPDATE_CURRENT)

  fun <T> T.applyHack(callback: T.() -> Unit): T = apply(callback)
  notificationManager.notify(Common.NOTIFICATION_ID_UPDATES, NotificationCompat
      .Builder(MainApplication.instance, RevConstants.NOTIF_CHANNEL_UPDATES)
      .setSmallIcon(R.drawable.ic_rev)
      .setContentTitle(MainApplication.instance.getString(R.string.new_updates_available))
      .setContentText(shortDescription)
      .setColor(ContextThemeWrapper(MainApplication.instance, R.style.Theme_Main_Light)
          .getColorFromAttr(android.R.attr.colorAccent).defaultColor)
      .setContentIntent(updateTabPendingIntent)
      .setStyle(NotificationCompat.InboxStyle().applyHack {
        for (productItem in productItems.take(maxUpdates)) {
          val builder = SpannableStringBuilder(productItem.name)
          builder.setSpan(ForegroundColorSpan(Color.BLACK), 0, builder.length,
              SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          builder.append(' ').append(productItem.version)
          addLine(builder)
        }
        if (productItems.size > maxUpdates) {
          val summary = MainApplication.instance.getString(R.string.plus_more_FORMAT, productItems.size - maxUpdates)
          if (Android.sdk(24)) {
            addLine(summary)
          } else {
            setSummaryText(summary)
          }
        }
      })
      .setOnlyAlertOnce(true)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .addAction(R.drawable.ic_launch, "Update All", updateAllPendingIntent)
      .setDeleteIntent(notificationDismissedPendingIntent)
      .build())
}

private fun dismissUpdatesNotification() {
  notificationManager.cancel(Common.NOTIFICATION_ID_UPDATES)
}
