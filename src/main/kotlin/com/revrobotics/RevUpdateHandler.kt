package com.revrobotics

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.widget.Toast
import com.revrobotics.ChUpdaterResult.PresentationType.ERROR
import com.revrobotics.ChUpdaterResult.PresentationType.SUCCESS
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.content.Cache
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread

@SuppressLint("StaticFieldLeak") // We clear currentActivity when it becomes paused
object RevUpdateHandler {
  @Volatile var currentActivity: Activity? = null
  @Volatile var currentlyDisplayedPackageName: String? = null
    set(value) {
      field = value
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
  private var shouldStatusDialogBeDisplayed = AtomicBoolean(false)
  private var statusDialog: AlertDialog? = null

  fun performUpdateUsingControlHubUpdater(cacheFileName: String, packageName: String, versionName: String) {
    thread {
      val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultBundle: Bundle?) {
          // Because we are using Looper.getMainLooper(), this runs on the main/UI thread
          if (resultBundle == null) { return }
          val result = ChUpdaterResult.fromBundle(resultBundle)

          if (updateState?.packageBeingUpdated != packageName && currentlyDisplayedPackageName == packageName) {
            // We just received a result for a different package, whose ProductFragment is being displayed.
            shouldStatusDialogBeDisplayed.set(true)
          }

          val currentActivity = RevUpdateHandler.currentActivity
          if (shouldStatusDialogBeDisplayed.get() && currentActivity != null) {
            showOrUpdateDialog(result.message, currentActivity)
          } else {
            Toast.makeText(MainApplication.instance, result.message, Toast.LENGTH_SHORT).show()
          }

          // TODO(Noah): Handle prompts
          // TODO(Noah): Handle detail messages that are supposed to be displayed
          // TODO(Noah): Figure out why dialog doesn't display when rotated to portrait

          updateState = if (result.presentationType == ERROR || result.presentationType == SUCCESS) {
            statusDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "OK"
            null
          } else {
            UpdateState(result, packageName)
          }
        }
      }

      val apkFile = Cache.getReleaseFile(MainApplication.instance, cacheFileName)
      val updateIntent = Intent()
      updateIntent.component = ComponentName(ChUpdaterConstants.CONTROL_HUB_UPDATER_PACKAGE, ChUpdaterConstants.CONTROL_HUB_UPDATE_SERVICE)
      updateIntent.putExtra(ChUpdaterConstants.EXTRA_RESULT_RECEIVER, wrapResultReceiverForIpc(resultReceiver))

      val updatePath: String
      if (packageName == MainApplication.DRIVER_HUB_OS_CONTAINER_PACKAGE) {
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
    }
  }

  private fun showOrUpdateDialog(message: String, activity: Activity) {
    var statusDialog = this.statusDialog
    if (statusDialog == null) {
      statusDialog = AlertDialog.Builder(activity)
          .setOnDismissListener {
            this.shouldStatusDialogBeDisplayed.set(false)
            this.statusDialog = null
          }
          .setMessage(message)
          .setPositiveButton("Hide progress") { _: DialogInterface, _: Int -> }
          // TODO(Noah): Show spinner of some kind
          .create()
      statusDialog.show()
      this.statusDialog = statusDialog
    } else {
      statusDialog.setMessage(message)
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
