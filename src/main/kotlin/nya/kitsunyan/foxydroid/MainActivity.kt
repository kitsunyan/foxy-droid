package nya.kitsunyan.foxydroid

import android.content.Intent
import nya.kitsunyan.foxydroid.screen.ScreenActivity

class MainActivity: ScreenActivity() {
  companion object {
    const val ACTION_UPDATES = "${BuildConfig.APPLICATION_ID}.intent.action.UPDATES"
    const val ACTION_UPDATE_ALL = "${BuildConfig.APPLICATION_ID}.intent.action.UPDATE_ALL" // Added by REV Robotics on 2021-06-07
    const val ACTION_PERFORM_ACTION_WAITING_ON_INTERNET = "${BuildConfig.APPLICATION_ID}.intent.action.WAITING_FOR_INTERNET" // Added by REV Robotics on 2021-06-15
    const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL"
    const val EXTRA_CACHE_FILE_NAME = "${BuildConfig.APPLICATION_ID}.intent.extra.CACHE_FILE_NAME"
  }

  override fun handleIntent(intent: Intent?) {
    when (intent?.action) {
      ACTION_UPDATES -> handleSpecialIntent(SpecialIntent.Updates)
      ACTION_INSTALL -> handleSpecialIntent(SpecialIntent.Install(intent.packageName,
        intent.getStringExtra(EXTRA_CACHE_FILE_NAME)))
      ACTION_UPDATE_ALL -> handleSpecialIntent(SpecialIntent.UpdateAll) // Added by REV Robotics on 2021-06-07
      ACTION_PERFORM_ACTION_WAITING_ON_INTERNET -> handleSpecialIntent(SpecialIntent.PerformActionWaitingOnInternet) // Added by REV Robotics on 2021-06-07
      else -> super.handleIntent(intent)
    }
  }
}
