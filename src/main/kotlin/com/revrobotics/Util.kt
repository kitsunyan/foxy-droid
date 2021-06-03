package com.revrobotics

import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import android.os.Handler
import android.os.Looper
import android.util.ArraySet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// This file was written by REV Robotics, but should only contain code that could be ported to upstream Foxy Droid.

val mainThreadHandler = Handler(Looper.getMainLooper())

private val executor = Executors.newSingleThreadExecutor()

// queueDownloadAndUpdate function added by REV Robotics on 2021-05-09
fun queueDownloadAndUpdate(packageName: String, downloadConnection: Connection<DownloadService.Binder, DownloadService>) {
  executor.submit {
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
          .minBy { it.platforms.size }
          ?: compatibleReleases.minBy { it.platforms.size }
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
   * Add a callback that will be called once the time that all repositories were last updated changes
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
