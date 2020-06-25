package nya.kitsunyan.foxydroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import nya.kitsunyan.foxydroid.utility.extension.android.*

abstract class ConnectionService<T: IBinder>: Service() {
  abstract override fun onBind(intent: Intent): T

  fun startSelf() {
    val intent = Intent(this, this::class.java)
    if (Android.sdk(26)) {
      startForegroundService(intent)
    } else {
      startService(intent)
    }
  }
}
