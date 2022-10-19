package nya.kitsunyan.foxydroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import com.revrobotics.LastUpdateOfAllReposTracker
import com.revrobotics.RevConstants
import com.revrobotics.refreshUpdatesAndStaleReposNotifications
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.Common
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.index.RepositoryUpdater
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.utility.extension.text.*
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.*

class SyncService: ConnectionService<SyncService.Binder>() {
  companion object {
    private const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.intent.action.CANCEL"

    private val stateSubject = PublishSubject.create<State>()
    private val finishSubject = PublishSubject.create<Unit>()
  }

  private sealed class State {
    data class Connecting(val name: String): State()
    data class Syncing(val name: String, val stage: RepositoryUpdater.Stage,
      val read: Long, val total: Long?): State()
    object Finishing: State()
  }

  private class Task(val repositoryId: Long, val useForegroundService: Boolean)
  private data class CurrentTask(val task: Task?, val disposable: Disposable,
    val hasUpdates: Boolean, val lastState: State)
  // Modified by REV Robotics on 2021-06-03: Renamed entries to clarify what they mean
  private enum class Started { NO, BACKGROUND, FOREGROUND_SERVICE }

  private var started = Started.NO
  private val tasks = mutableListOf<Task>()
  private var currentTask: CurrentTask? = null

  private var updateNotificationBlockerFragment: WeakReference<Fragment>? = null

  // Modified by REV Robotics on 2021-06-03: added AUTO_WITH_FOREGROUND_NOTIFICATION entry
  enum class SyncRequest { AUTO, AUTO_WITH_FOREGROUND_NOTIFICATION, MANUAL, FORCE }

  inner class Binder: android.os.Binder() {
    val finish: Observable<Unit>
      get() = finishSubject

    private fun sync(ids: List<Long>, request: SyncRequest) {
      val cancelledTask = cancelCurrentTask { request == SyncRequest.FORCE && it.task?.repositoryId in ids }
      cancelTasks { !it.useForegroundService && it.repositoryId in ids }
      val currentIds = tasks.asSequence().map { it.repositoryId }.toSet()
      val useForegroundService = request != SyncRequest.AUTO
      tasks += ids.asSequence().filter { it !in currentIds &&
        it != currentTask?.task?.repositoryId }.map { Task(it, useForegroundService) }
      handleNextTask(cancelledTask?.hasUpdates == true)
      // Show a foreground notification for all SyncRequest types except for AUTO
      if (request != SyncRequest.AUTO && started == Started.BACKGROUND) {
        started = Started.FOREGROUND_SERVICE
        startSelf()
        handleSetStarted()
        currentTask?.lastState?.let { publishForegroundState(true, it) }
      }
    }

    fun sync(request: SyncRequest) {
      val ids = Database.RepositoryAdapter.getAll(null)
        .asSequence().filter { it.enabled }.map { it.id }.toList()
      sync(ids, request)
    }

    fun sync(repository: Repository) {
      if (repository.enabled) {
        sync(listOf(repository.id), SyncRequest.FORCE)
      }
    }

    fun cancelAuto(): Boolean {
      val removed = cancelTasks { !it.useForegroundService }
      val currentTask = cancelCurrentTask { it.task?.useForegroundService == false }
      handleNextTask(currentTask?.hasUpdates == true)
      return removed || currentTask != null
    }

    fun setUpdateNotificationBlocker(fragment: Fragment?) {
      updateNotificationBlockerFragment = fragment?.let(::WeakReference)
      if (fragment != null) {
        // Modified by REV Robotics on 2021-06-06: Don't cancel the updates available notification just because the user
        // is on the updates tab

        // notificationManager.cancel(Common.NOTIFICATION_ID_UPDATES)
      }
    }

    fun setEnabled(repository: Repository, enabled: Boolean): Boolean {
      Database.RepositoryAdapter.put(repository.enable(enabled))

      // Line added by REV Robotics on 2021-04-30
      LastUpdateOfAllReposTracker.markRepoAsNeverDownloaded(repository.id)

      if (enabled) {
        if (repository.id != currentTask?.task?.repositoryId && !tasks.any { it.repositoryId == repository.id }) {
          tasks += Task(repository.id, true)
          handleNextTask(false)
        }
      } else {
        cancelTasks { it.repositoryId == repository.id }
        val cancelledTask = cancelCurrentTask { it.task?.repositoryId == repository.id }
        handleNextTask(cancelledTask?.hasUpdates == true)
      }
      return true
    }

    fun isCurrentlySyncing(repositoryId: Long): Boolean {
      return currentTask?.task?.repositoryId == repositoryId
    }

    fun deleteRepository(repositoryId: Long): Boolean {
      val repository = Database.RepositoryAdapter.get(repositoryId)
      return repository != null && run {
        setEnabled(repository, false)
        Database.RepositoryAdapter.markAsDeleted(repository.id)
        true
      }
    }
  }

  private val binder = Binder()
  override fun onBind(intent: Intent): Binder = binder

  private var stateDisposable: Disposable? = null

  override fun onCreate() {
    super.onCreate()

    if (Android.sdk(26)) {
      NotificationChannel(Common.NOTIFICATION_CHANNEL_SYNCING,
        getString(R.string.syncing), NotificationManager.IMPORTANCE_LOW)
        .apply { setShowBadge(false) }
        .let(notificationManager::createNotificationChannel)

      // Modified by REV Robotics on 2021-06-07: Delete the old updates channel in favor of a new one.
      // We want the importance level to be default instead of low, but we aren't able to change the
      // importance level of an existing notification channel.
      // The new channel gets created in displayUpdatesNotification(), as it may be needed before SyncService is created.
      notificationManager.deleteNotificationChannel("updates")
    }

    stateDisposable = stateSubject
      .sample(500L, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
      .subscribe { publishForegroundState(false, it) }
  }

  override fun onDestroy() {
    super.onDestroy()

    stateDisposable?.dispose()
    stateDisposable = null
    cancelTasks { true }
    cancelCurrentTask { true }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      tasks.clear()
      val cancelledTask = cancelCurrentTask { it.task != null }
      handleNextTask(cancelledTask?.hasUpdates == true)
    }
    return START_NOT_STICKY
  }

  private fun cancelTasks(condition: (Task) -> Boolean): Boolean {
    return tasks.removeAll(condition)
  }

  private fun cancelCurrentTask(condition: ((CurrentTask) -> Boolean)): CurrentTask? {
    return currentTask?.let {
      if (condition(it)) {
        currentTask = null
        it.disposable.dispose()
        RepositoryUpdater.await()
        it
      } else {
        null
      }
    }
  }

  private fun showNotificationError(repository: Repository, exception: Exception) {
    notificationManager.notify("repository-${repository.id}", Common.NOTIFICATION_ID_SYNCING, NotificationCompat
      .Builder(this, Common.NOTIFICATION_CHANNEL_SYNCING)
      .setSmallIcon(android.R.drawable.stat_sys_warning)
      .setColor(ContextThemeWrapper(this, R.style.Theme_Main_Light)
        .getColorFromAttr(android.R.attr.colorAccent).defaultColor)
      .setContentTitle(getString(R.string.could_not_sync_FORMAT, repository.name))
      // Explicit grouping added by REV Robotics on 2021-06-07, to avoid undesirable grouping imposed by Android
      .setGroup(RevConstants.NOTIF_GROUP_SYNC_FAILED)
      .setContentText(getString(when (exception) {
        is RepositoryUpdater.UpdateException -> when (exception.errorType) {
          RepositoryUpdater.ErrorType.NETWORK -> R.string.network_error_DESC
          RepositoryUpdater.ErrorType.HTTP -> R.string.http_error_DESC
          RepositoryUpdater.ErrorType.VALIDATION -> R.string.validation_index_error_DESC
          RepositoryUpdater.ErrorType.PARSING -> R.string.parsing_index_error_DESC
        }
        else -> R.string.unknown_error_DESC
      }))
      .build())
  }

  private val stateNotificationBuilder by lazy { NotificationCompat
    .Builder(this, Common.NOTIFICATION_CHANNEL_SYNCING)
    .setSmallIcon(R.drawable.ic_sync)
    .setColor(ContextThemeWrapper(this, R.style.Theme_Main_Light)
      .getColorFromAttr(android.R.attr.colorAccent).defaultColor)
    .addAction(0, getString(R.string.cancel), PendingIntent.getService(this, 0,
      Intent(this, this::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT)) }

  private fun publishForegroundState(force: Boolean, state: State) {
    if (force || currentTask?.lastState != state) {
      currentTask = currentTask?.copy(lastState = state)
      if (started == Started.FOREGROUND_SERVICE) {
        startForeground(Common.NOTIFICATION_ID_SYNCING, stateNotificationBuilder.apply {
          when (state) {
            is State.Connecting -> {
              setContentTitle(getString(R.string.syncing_FORMAT, state.name))
              setContentText(getString(R.string.connecting))
              setProgress(0, 0, true)
            }
            is State.Syncing -> {
              setContentTitle(getString(R.string.syncing_FORMAT, state.name))
              when (state.stage) {
                RepositoryUpdater.Stage.DOWNLOAD -> {
                  if (state.total != null) {
                    setContentText("${state.read.formatSize()} / ${state.total.formatSize()}")
                    setProgress(100, (100f * state.read / state.total).roundToInt(), false)
                  } else {
                    setContentText(state.read.formatSize())
                    setProgress(0, 0, true)
                  }
                }
                RepositoryUpdater.Stage.PROCESS -> {
                  val progress = state.total?.let { 100f * state.read / it }?.roundToInt()
                  setContentText(getString(R.string.processing_FORMAT, "${progress ?: 0}%"))
                  setProgress(100, progress ?: 0, progress == null)
                }
                RepositoryUpdater.Stage.MERGE -> {
                  val progress = (100f * state.read / (state.total ?: state.read)).roundToInt()
                  setContentText(getString(R.string.merging_FORMAT, "${state.read} / ${state.total ?: state.read}"))
                  setProgress(100, progress, false)
                }
                RepositoryUpdater.Stage.COMMIT -> {
                  setContentText(getString(R.string.saving_details))
                  setProgress(0, 0, true)
                }
              }
            }
            is State.Finishing -> {
              setContentTitle(getString(R.string.syncing))
              setContentText(null)
              setProgress(0, 0, true)
            }
          }::class
        }.build())
      }
    }
  }

  private fun handleSetStarted() {
    stateNotificationBuilder.setWhen(System.currentTimeMillis())
  }

  // updatesAvailable parameter renamed to aRepoHasBeenUpdated by REV Robotics on 2021-06-06
  private fun handleNextTask(aRepoHasBeenUpdated: Boolean) {
    if (currentTask == null) {
      if (tasks.isNotEmpty()) {
        val task = tasks.removeAt(0)
        val repository = Database.RepositoryAdapter.get(task.repositoryId)
        if (repository != null && repository.enabled) {
          val lastStarted = started
          val newStarted = if (task.useForegroundService || lastStarted == Started.FOREGROUND_SERVICE) Started.FOREGROUND_SERVICE else Started.BACKGROUND
          started = newStarted
          if (newStarted == Started.FOREGROUND_SERVICE && lastStarted != Started.FOREGROUND_SERVICE) {
            startSelf()
            handleSetStarted()
          }
          val initialState = State.Connecting(repository.name)
          publishForegroundState(true, initialState)
          val unstable = Preferences[Preferences.Key.UpdateUnstable]
          lateinit var disposable: Disposable
          disposable = RepositoryUpdater
            .update(repository, unstable) { stage, progress, total ->
              if (!disposable.isDisposed) {
                stateSubject.onNext(State.Syncing(repository.name, stage, progress, total))
              }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { result, throwable ->
              currentTask = null
              throwable?.printStackTrace()
              if (throwable != null && task.useForegroundService) {
                showNotificationError(repository, throwable as Exception)
              }
              if (throwable == null) {
                // Added by REV Robotics on 2021-04-29: Mark the repo as having been just downloaded
                LastUpdateOfAllReposTracker.markRepoAsJustDownloaded(repository.id)
                // Added by REV Robotics on 2021-06-02: Clear any existing error notification for this repository
                notificationManager.cancel("repository-${repository.id}", Common.NOTIFICATION_ID_SYNCING)
              }
              handleNextTask(result == true || aRepoHasBeenUpdated)
            }
          currentTask = CurrentTask(task, disposable, aRepoHasBeenUpdated, initialState)
        } else {
          handleNextTask(aRepoHasBeenUpdated)
        }
      } else if (started != Started.NO) {
        if (aRepoHasBeenUpdated && Preferences[Preferences.Key.UpdateNotify]) {
          val disposable = RxUtils
            .querySingle { Database.ProductAdapter
              .query(true, true, "", ProductItem.Section.All, ProductItem.Order.NAME, it)
              .use { it.asSequence().map(Database.ProductAdapter::transformItem).toList() } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { result, throwable ->
              throwable?.printStackTrace()
              currentTask = null
              handleNextTask(false)

              // Modified by REV Robotics on 2021-06-09 to use shared notification logic
              refreshUpdatesAndStaleReposNotifications(result)

            }
          currentTask = CurrentTask(null, disposable, true, State.Finishing)
        } else {
          finishSubject.onNext(Unit)
          val needStop = started == Started.FOREGROUND_SERVICE
          started = Started.NO
          if (needStop) {
            stopForeground(true)
            stopSelf()
          }
        }
      }
    }
  }

  /* Code commented out on 2021-06-06 by REV Robotics, as we have copied this function to Util.kt for external use
  private fun displayUpdatesNotification(productItems: List<ProductItem>) {
    val maxUpdates = 5
    fun <T> T.applyHack(callback: T.() -> Unit): T = apply(callback)
    notificationManager.notify(Common.NOTIFICATION_ID_UPDATES, NotificationCompat
      .Builder(this, Common.NOTIFICATION_CHANNEL_UPDATES)
      .setSmallIcon(R.drawable.ic_new_releases)
      .setContentTitle(getString(R.string.new_updates_available))
      .setContentText(resources.getQuantityString(R.plurals.new_updates_DESC_FORMAT,
        productItems.size, productItems.size))
      .setColor(ContextThemeWrapper(this, R.style.Theme_Main_Light)
        .getColorFromAttr(android.R.attr.colorAccent).defaultColor)
      .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .setAction(MainActivity.ACTION_UPDATES), PendingIntent.FLAG_UPDATE_CURRENT))
      .setStyle(NotificationCompat.InboxStyle().applyHack {
        for (productItem in productItems.take(maxUpdates)) {
          val builder = SpannableStringBuilder(productItem.name)
          builder.setSpan(ForegroundColorSpan(Color.BLACK), 0, builder.length,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          builder.append(' ').append(productItem.version)
          addLine(builder)
        }
        if (productItems.size > maxUpdates) {
          val summary = getString(R.string.plus_more_FORMAT, productItems.size - maxUpdates)
          if (Android.sdk(24)) {
            addLine(summary)
          } else {
            setSummaryText(summary)
          }
        }
      })
      .build())
  } */

  class Job: JobService() {
    private var syncParams: JobParameters? = null
    private var syncDisposable: Disposable? = null
    private val syncConnection = Connection(SyncService::class.java, onBind = { connection, binder ->
      syncDisposable = binder.finish.subscribe {
        val params = syncParams
        if (params != null) {
          syncParams = null
          syncDisposable?.dispose()
          syncDisposable = null
          connection.unbind(this)
          jobFinished(params, false)
        }
      }
      binder.sync(SyncRequest.AUTO)
    }, onUnbind = { _, binder ->
      syncDisposable?.dispose()
      syncDisposable = null
      binder.cancelAuto()
      val params = syncParams
      if (params != null) {
        syncParams = null
        jobFinished(params, true)
      }
    })

    override fun onStartJob(params: JobParameters): Boolean {
      // Modified by REV Robotics on 2021-06-03: Skip JobScheduler-based automatic repo sync if repositories were synced in past 11 hours
      if (LastUpdateOfAllReposTracker.timeSinceLastUpdateOfAllRepos < Duration.ofHours(11)) {
        Log.i("SyncService.Job", "Skipping JobScheduler-based automatic repo sync, because the repositories have been synced within the last 11 hours")
        return false
      }

      syncParams = params
      syncConnection.bind(this)
      return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
      syncParams = null
      syncDisposable?.dispose()
      syncDisposable = null
      val reschedule = syncConnection.binder?.cancelAuto() == true
      syncConnection.unbind(this)
      return reschedule
    }
  }
}
