package com.revrobotics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import nya.kitsunyan.foxydroid.MainActivity
import nya.kitsunyan.foxydroid.MainApplication
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.SyncService

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

      if (actionWaitingForInternetConnection != null) {
        // Tell the main activity to perform the action that was waiting for an Internet connection
        context.startActivity(
            Intent(MainApplication.instance, MainActivity::class.java)
                .apply {
                  action = MainActivity.ACTION_PERFORM_ACTION_WAITING_ON_INTERNET
                  flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })

        // TODO(Noah): Only execute the desired action immediately if the repository has been updated in the last 12 hours,
        //             or if a specific release was requested.
        //             Otherwise, wait to perform it until after the automatic sync.
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
