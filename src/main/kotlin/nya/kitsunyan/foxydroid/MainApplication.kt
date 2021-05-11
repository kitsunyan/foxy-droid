package nya.kitsunyan.foxydroid

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import com.revrobotics.RevConstants
import com.revrobotics.RevUpdater
import com.revrobotics.mainThreadHandler
import com.revrobotics.queueDownloadAndUpdate
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.content.ProductPreferences
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.InstalledItem
import nya.kitsunyan.foxydroid.index.RepositoryUpdater
import nya.kitsunyan.foxydroid.network.Downloader
import nya.kitsunyan.foxydroid.network.PicassoDownloader
import nya.kitsunyan.foxydroid.screen.ProductsFragment
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.service.SyncService
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import java.net.InetSocketAddress
import java.net.Proxy

@Suppress("unused")
class MainApplication: Application() {
  // companion object added by REV Robotics on 2021-04-29
  companion object {
    lateinit var instance: MainApplication
  }

  private fun PackageInfo.toInstalledItem(): InstalledItem {
    val signatureString = singleSignature?.let(Utils::calculateHash).orEmpty()
    return InstalledItem(packageName, versionName.orEmpty(), versionCodeCompat, signatureString)
  }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(Utils.configureLocale(base))
  }

  override fun onCreate() {
    super.onCreate()

    // Line added by REV Robotics on 2021-04-29
    instance = this

    val databaseUpdated = Database.init(this)
    Preferences.init(this)
    ProductPreferences.init(this)
    RepositoryUpdater.init(this)
    listenApplications()
    listenPreferences()

    Picasso.setSingletonInstance(Picasso.Builder(this)
      .downloader(OkHttp3Downloader(PicassoDownloader.Factory(Cache.getImagesDir(this)))).build())

    // Modified by REV Robotics on 2021-05-10: Sync all repositories even if the database wasn't updated
    if (databaseUpdated) {
      syncAll(force = true)
    } else {
      syncAll(force = false)
    }

    Cache.cleanup(this)
    updateSyncJob(false)

    // Support for updating the Driver Hub OS on app launch added by REV Robotics on 2021-05-10
    RevConstants.shouldAutoInstallOSWhenDownloadCompletes = true

    if (RevConstants.shouldAutoInstallOsOnNextLaunch) {
      RevConstants.shouldAutoInstallOsOnNextLaunch = false
      // TODO(Noah): The status updates are not displayed in a dialog like they should be
      RevUpdater.updatingAllSoftware = true
      installOsUpdate()
    }
  }

  private fun listenApplications() {
    registerReceiver(object: BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.let { if (it.scheme == "package") it.schemeSpecificPart else null }
        if (packageName != null) {
          when (intent.action.orEmpty()) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED -> {
              val packageInfo = try {
                packageManager.getPackageInfo(packageName, Android.PackageManager.signaturesFlag)
              } catch (e: Exception) {
                null
              }
              if (packageInfo != null) {
                Database.InstalledAdapter.put(packageInfo.toInstalledItem())
              } else {
                Database.InstalledAdapter.delete(packageName)
              }
            }
          }
        }
      }
    }, IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addDataScheme("package")
    })
    val installedItems = packageManager.getInstalledPackages(Android.PackageManager.signaturesFlag)
      .map { it.toInstalledItem() }
    Database.InstalledAdapter.putAll(installedItems)

    // Modified by REV Robotics: Add entry for the Driver Hub OS container based on the actual
    // Driver Hub OS version, rather than the version of the container, which shouldn't even be installed.
    @SuppressLint("PrivateApi")
    val systemPropertiesClass = Class.forName("android.os.SystemProperties")
    val getStringMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
    val getIntMethod = systemPropertiesClass.getMethod("getInt", String::class.java, Int::class.java)

    val driverHubOsVersionString: String = getStringMethod.invoke(null, RevConstants.DRIVER_HUB_OS_VERSION_NAME_PROPERTY, "") as String
    val driverHubOsVersionCode: Int = getIntMethod.invoke(null, RevConstants.DRIVER_HUB_OS_VERSION_CODE_PROPERTY, 0) as Int

    if (driverHubOsVersionString.isNotBlank()) {
      Database.InstalledAdapter.put(InstalledItem(RevConstants.DRIVER_HUB_OS_CONTAINER_PACKAGE, driverHubOsVersionString, driverHubOsVersionCode.toLong(), "2de81609ece923768afd554228986159"))
    }
  }

  private fun listenPreferences() {
    updateProxy()
    var lastAutoSync = Preferences[Preferences.Key.AutoSync]
    var lastUpdateUnstable = Preferences[Preferences.Key.UpdateUnstable]
    Preferences.observable.subscribe {
      if (it == Preferences.Key.ProxyType || it == Preferences.Key.ProxyHost || it == Preferences.Key.ProxyPort) {
        updateProxy()
      } else if (it == Preferences.Key.AutoSync) {
        val autoSync = Preferences[Preferences.Key.AutoSync]
        if (lastAutoSync != autoSync) {
          lastAutoSync = autoSync
          updateSyncJob(true)
        }
      } else if (it == Preferences.Key.UpdateUnstable) {
        val updateUnstable = Preferences[Preferences.Key.UpdateUnstable]
        if (lastUpdateUnstable != updateUnstable) {
          lastUpdateUnstable = updateUnstable
          syncAll(force = true)
        }
      }
    }
  }

  private fun updateSyncJob(force: Boolean) {
    val jobScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
    val reschedule = force || !jobScheduler.allPendingJobs.any { it.id == Common.JOB_ID_SYNC }
    if (reschedule) {
      val autoSync = Preferences[Preferences.Key.AutoSync]
      when (autoSync) {
        Preferences.AutoSync.Never -> {
          jobScheduler.cancel(Common.JOB_ID_SYNC)
        }
        Preferences.AutoSync.Wifi, Preferences.AutoSync.Always -> {
          val period = 12 * 60 * 60 * 1000L // 12 hours
          val wifiOnly = autoSync == Preferences.AutoSync.Wifi
          jobScheduler.schedule(JobInfo
            .Builder(Common.JOB_ID_SYNC, ComponentName(this, SyncService.Job::class.java))
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .apply {
              if (Android.sdk(26)) {
                setRequiresBatteryNotLow(true)
                setRequiresStorageNotLow(true)
              }
              if (Android.sdk(24)) {
                setPeriodic(period, JobInfo.getMinFlexMillis())
              } else {
                setPeriodic(period)
              }
            }
            .build())
          Unit
        }
      }::class.java
    }
  }

  private fun updateProxy() {
    val type = Preferences[Preferences.Key.ProxyType].proxyType
    val host = Preferences[Preferences.Key.ProxyHost]
    val port = Preferences[Preferences.Key.ProxyPort]
    val socketAddress = when (type) {
      Proxy.Type.DIRECT -> {
        null
      }
      Proxy.Type.HTTP, Proxy.Type.SOCKS -> {
        try {
          InetSocketAddress.createUnresolved(host, port)
        } catch (e: Exception) {
          e.printStackTrace()
          null
        }
      }
    }
    val proxy = socketAddress?.let { Proxy(type, socketAddress) }
    Downloader.proxy = proxy
  }

  // Modified by REV Robotics on 2021-05-10 to support doing a non-forced sync on every application start
  private fun syncAll(force: Boolean) {
    if (force) {
      Database.RepositoryAdapter.getAll(null).forEach {
        if (it.lastModified.isNotEmpty() || it.entityTag.isNotEmpty()) {
          Database.RepositoryAdapter.put(it.copy(lastModified = "", entityTag = ""))
        }
        // Line added by REV Robotics on 2021-04-30
        ProductsFragment.markRepoAsNeverDownloaded(it.id)
      }
    }
    val syncType = if (force) {
      SyncService.SyncRequest.FORCE
    } else {
      SyncService.SyncRequest.MANUAL
    }
    Connection(SyncService::class.java, onBind = { connection, binder ->
      binder.sync(syncType)
      connection.unbind(this)
    }).bind(this)
  }

  // installOsUpdate() function added by REV Robotics on 2021-05-10
  private fun installOsUpdate() {
    Connection(DownloadService::class.java, onBind = { connection, _ ->
      mainThreadHandler.postDelayed(::installOsUpdate, 1000)
      queueDownloadAndUpdate(RevConstants.DRIVER_HUB_OS_CONTAINER_PACKAGE, connection)
      connection.unbind(this)
    }).bind(this)
  }

  class BootReceiver: BroadcastReceiver() {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) = Unit
  }
}
