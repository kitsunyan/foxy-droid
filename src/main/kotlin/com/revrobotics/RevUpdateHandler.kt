package com.revrobotics

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.content.Cache
import java.util.zip.ZipFile
import kotlin.concurrent.thread

object RevUpdateHandler {
  @Volatile var currentlyDisplayedPackageName: String? = null

  @Volatile private var updateState: UpdateState? = null
  @Volatile private var shouldAlertDialogBeDisplayed = false

  fun performUpdateUsingControlHubUpdater(cacheFileName: String, packageName: String, versionName: String) {
    thread {
      val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
          // Because we are using Looper.getMainLooper(), this runs on the main/UI thread
          if (resultData == null) { return }

          // TODO(Noah): Report progress
        }
      }

      val apkFile = Cache.getReleaseFile(MainApplication.instance, cacheFileName)
      val updateIntent = Intent()
      updateIntent.component = ComponentName(ChUpdaterConstants.CONTROL_HUB_UPDATER_PACKAGE, ChUpdaterConstants.CONTROL_HUB_UPDATE_SERVICE)
      updateIntent.putExtra(ChUpdaterConstants.EXTRA_RESULT_RECEIVER, wrapResultReceiverForIpc(resultReceiver))

      val updatePath: String
      if (packageName == MainApplication.DRIVER_HUB_OS_CONTAINER_PACKAGE) {
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
        // TODO(Noah): Allow installing any app targeting API 23+ via ChUpdater
        updatePath = apkFile.absolutePath
        updateIntent.action = ChUpdaterConstants.ACTION_UPDATE_FTC_APP
      }

      updateIntent.putExtra(ChUpdaterConstants.EXTRA_UPDATE_FILE_PATH, updatePath)
      MainApplication.instance.startService(updateIntent)
    }
  }
}
// TODO(Noah): Keep track of whether or not an AlertDialog should be displayed.
//             On cancel, set to false.
//             On navigation to any ProductFragment, record which product is being displayed, and set to true if that one is being updated.
//             On navigation away from any ProductFragment, set to false, and record that no ProductFragment is displayed.
//             When any update is started, set to true if the relevant ProductFragment is currently displayed.
//             If a status comes in while set to false, show a toast instead

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
