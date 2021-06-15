package com.revrobotics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import nya.kitsunyan.foxydroid.MainActivity
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.entity.Release
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.service.SyncService
import nya.kitsunyan.foxydroid.utility.extension.json.Json

/**
 * This class receives broadcast notifications that Internet is available from the RevUpdateInterface, which is always
 * running on the Driver Hub.
 *
 * This broadcast receiver is registered in the manifest, so that it will be executed even if the app was not running
 * previously.
 */
class InternetAvailableBroadcastReceiver : BroadcastReceiver() {
  private val tag = "InternetAvailableReceiver"

  override fun onReceive(context: Context, intent: Intent) {
    if ("com.revrobotics.revupdateinterface.INTERNET_AVAILABLE" == intent.action) {
      Log.i(tag, "Internet is available")

      // TODO(Noah): Only execute the desired action immediately if the repository has been updated in the last 12 hours,
      //             or if a specific release was requested.
      //             Otherwise, wait to perform it until after the automatic sync.
      Log.i(tag, "Executing desired action")
      val desiredAction = desiredActionAfterInternetConnected
      if (desiredAction != null) {
        desiredActionAfterInternetConnected = null
        RequestInternetDialogFragment.instance?.dismiss()
        when (desiredAction) {
          UpdateAll -> {
            context.startActivity(
                Intent(MainApplication.instance, MainActivity::class.java)
                    .apply { action = MainActivity.ACTION_UPDATE_ALL })
          }
          is InstallApk -> {
            Connection(DownloadService::class.java, onBind = { connection, _ ->
              connection.binder?.enqueue(
                  desiredAction.packageName,
                  desiredAction.productName,
                  Repository.deserialize(desiredAction.repositoryId, Json.factory.createParser(desiredAction.serializedRepository)),
                  Release.deserialize(Json.factory.createParser(desiredAction.serializedRelease)))
              connection.unbind(context)
            }).bind(context)
          }
        }
      }

      if (LastUpdateOfAllReposTracker.timeSinceLastUpdateOfAllRepos.toHours() > 12) {
        // TODO(Noah): In the wait for Internet dialog, show that we are connected to the Internet, but are syncing.
        Log.i(tag, "It has been more than 12 hours since all repositories were updated; initiating repository sync")
        Connection(SyncService::class.java, onBind = { connection, binder ->
          // We want to have the foreground notification, because otherwise the app isn't guaranteed to stay running
          // long enough to finish.
          binder.sync(SyncService.SyncRequest.AUTO_WITH_FOREGROUND_NOTIFICATION)
          connection.unbind(MainApplication.instance)
        }).bind(MainApplication.instance)
      } else {
        // TODO(Noah): Move the desired action execution to this block
      }
    }
  }
}
