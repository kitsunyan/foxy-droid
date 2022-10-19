package com.revrobotics

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
import com.revrobotics.ChUpdaterResult.PresentationType.ERROR
import com.revrobotics.ChUpdaterResult.PresentationType.SUCCESS
import com.revrobotics.ChUpdaterResult.PresentationType.PROMPT
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.content.Cache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread

@SuppressLint("StaticFieldLeak") // We clear currentActivity when it becomes paused
object RevUpdater {
  const val TAG = "RevUpdateHandler"
  private val updateExecutor = Executors.newSingleThreadExecutor()
  private val shouldStatusDialogBeDisplayed = AtomicBoolean(false)

  @Volatile var currentActivity: Activity? = null
  @Volatile var dialogPrefix: String? = null

  // TODO(Noah): When update all has finished, show a popup that says "all software has been updated", even if the user pressed hide

  @Volatile var updatingAllSoftware = false // TODO(Noah): Set this to false after update all or launch-induced OS update has finished
    set (value) {
      field = value
      if (value) {
        shouldStatusDialogBeDisplayed.set(true)
      }
    }

  @Volatile var currentlyDisplayedPackageName: String? = null
    set(value) {
      field = value
      if (updatingAllSoftware) { return }

      if (value == null) {
        this.statusDialog?.dismiss()
      } else {
        val updateState = this.updateState ?: return
        if (value == updateState.packageBeingUpdated) {
          val wasAlertDialogBeingDisplayed = shouldStatusDialogBeDisplayed.getAndSet(true)
          val currentActivity = this.currentActivity
          if (!wasAlertDialogBeingDisplayed && currentActivity != null) {
            showOrUpdateDialog(updateState.currentStatus.message, currentActivity)
          }
        }
      }
    }

  @Volatile private var updateState: UpdateState? = null
  private var statusDialog: AlertDialog? = null

  fun performUpdateUsingControlHubUpdater(cacheFileName: String, packageName: String, versionName: String) {
    updateExecutor.submit(UpdateRunnable(cacheFileName, packageName, versionName))
  }

  private class UpdateRunnable(val cacheFileName: String, val packageName: String, val versionName: String): Runnable {
    private val updateFinishedLatch = CountDownLatch(1)
    private val resultReceiver = object : ResultReceiver(mainThreadHandler) {
      override fun onReceiveResult(resultCode: Int, resultBundle: Bundle?) {
        // This runs on the main/UI thread because we are using a Handler based on Looper.getMainLooper()
        if (resultBundle == null) { return }
        val result = ChUpdaterResult.fromBundle(resultBundle)
        Log.d(TAG, "CH Updater result: ${result.message}")

        if (!updatingAllSoftware && updateState?.packageBeingUpdated != packageName && currentlyDisplayedPackageName == packageName) {
          // We just received a result for a different package, whose ProductFragment is being displayed.
          shouldStatusDialogBeDisplayed.set(true)
        }

        val currentActivity = RevUpdater.currentActivity
        if (shouldStatusDialogBeDisplayed.get() && currentActivity != null) {
          var message = result.message
          if (updatingAllSoftware && result.presentationType == SUCCESS) {
            // TODO(Noah): If we just updated the last piece of software, erase the prefix, and add a different message instead.
            message += "\nWaiting for the next piece of software to finish downloading."
          }
          showOrUpdateDialog(message, currentActivity)
        } else {
          Toast.makeText(MainApplication.instance, result.message, Toast.LENGTH_SHORT).show()
        }

        // TODO(Noah): Show detail messages that are supposed to be displayed
        // TODO(Noah): Figure out why dialog doesn't display when rotated to portrait

        updateState = if (result.presentationType == ERROR || result.presentationType == SUCCESS) {
          // TODO(Noah): When updating all apps, button text should switch to "OK" after all apps have been updated
          if (!updatingAllSoftware) {
            statusDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "OK"
          }
          null
        } else if (result.presentationType == PROMPT) {
          // TODO(Noah): Handle prompts correctly
          statusDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Cancel"
          null
        } else {
          UpdateState(result, packageName)
        }

        if (result.presentationType == ERROR && result.message.contains("Busy with previous update")) {
          Log.w(TAG, "ControlHubUpdater has reported that it is busy with a previous update. Trying again.")

          // We have to run on a new thread, because this runnable blocks, we're currently on the main/UI thread, and
          // the single-threaded executor is still waiting for the update to be marked as finished (which is perfect,
          // because this way we ensure that we don't move on to the next update until this one has _actually_
          // completed or failed.
          thread {
            Thread.sleep(1000)
            run()
          }
        } else if (updateState == null) {
          // updateState being null indicates that there is no longer an in-flight installation
          updateFinishedLatch.countDown()
        }
      }
    }

    override fun run() {
      val apkFile = Cache.getReleaseFile(MainApplication.instance, cacheFileName)
      val updateIntent = Intent()
      updateIntent.component = ComponentName(ChUpdaterConstants.CONTROL_HUB_UPDATER_PACKAGE, ChUpdaterConstants.CONTROL_HUB_UPDATE_SERVICE)
      updateIntent.putExtra(ChUpdaterConstants.EXTRA_RESULT_RECEIVER, wrapResultReceiverForIpc(resultReceiver))

      val updatePath: String
      if (packageName == RevConstants.DRIVER_HUB_OS_CONTAINER_PACKAGE) {
        resultReceiver.send(0, ChUpdaterResult(ChUpdaterResultType(
            ChUpdaterResult.Category.OTA_UPDATE,
            0,
            ChUpdaterResult.PresentationType.STATUS,
            ChUpdaterResult.DetailMessageType.LOGGED,
            "Extracting Driver Hub OS update"), null, null).toBundle())

        val otaDestination = Cache.getReleaseFile(MainApplication.instance, "DriverHubOS-$versionName.zip")
        ZipFile(apkFile).use { apk ->
          apk.getInputStream(apk.getEntry("assets/ota.zip")).use { otaInputStream ->
            otaDestination.outputStream().use { otaDestinationOutputStream ->
              otaInputStream.copyTo(otaDestinationOutputStream)
            }
          }
        }
        updatePath = otaDestination.absolutePath
        updateIntent.action = ChUpdaterConstants.ACTION_APPLY_OTA_UPDATE
      } else {
        updatePath = apkFile.absolutePath
        updateIntent.action = ChUpdaterConstants.ACTION_UPDATE_FTC_APP
      }

      updateIntent.putExtra(ChUpdaterConstants.EXTRA_UPDATE_FILE_PATH, updatePath)
      MainApplication.instance.startService(updateIntent)
      updateFinishedLatch.await()
    }

  }

  fun showOrUpdateDialog(message: String, activity: Activity) {
    var statusDialog = this.statusDialog
    val messagePrefix = this.dialogPrefix

    var adjustedMessage = message
    if (messagePrefix != null) {
      adjustedMessage = messagePrefix + adjustedMessage
    }

    if (statusDialog == null) {
      statusDialog = AlertDialog.Builder(activity)
          .setOnDismissListener {
            this.shouldStatusDialogBeDisplayed.set(false)
            this.statusDialog = null
          }
          .setMessage(adjustedMessage)
          .setPositiveButton("Hide installation status") { _: DialogInterface, _: Int -> }
          // TODO(Noah): Show spinner of some kind
          .create()
      statusDialog.show()
      this.statusDialog = statusDialog
    } else {
      statusDialog.setMessage(adjustedMessage)
    }
  }
}

// https://stackoverflow.com/a/12183036
private fun wrapResultReceiverForIpc(actualReceiver: ResultReceiver): ResultReceiver {
  val parcel = Parcel.obtain()
  actualReceiver.writeToParcel(parcel, 0)
  parcel.setDataPosition(0)
  val receiverForSending = ResultReceiver.CREATOR.createFromParcel(parcel)
  parcel.recycle()
  return receiverForSending
}

private data class UpdateState(val currentStatus: ChUpdaterResult, val packageBeingUpdated: String)
