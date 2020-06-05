package nya.kitsunyan.foxydroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

class Connection<T: IBinder>(private val serviceClass: Class<out Service>,
  private val onBind: ((Event<T>) -> Unit)? = null,
  private val onUnbind: ((Event<T>) -> Unit)? = null): ServiceConnection {
  class Event<T: IBinder>(val connection: Connection<T>, val binder: T)

  var binder: T? = null
    private set

  override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
    @Suppress("UNCHECKED_CAST")
    binder as T
    this.binder = binder
    onBind?.invoke(Event(this, binder))
  }

  override fun onServiceDisconnected(componentName: ComponentName) {
    binder?.let {
      binder = null
      onUnbind?.invoke(Event(this, it))
    }
  }

  fun bind(context: Context) {
    context.bindService(Intent(context, serviceClass), this, Context.BIND_AUTO_CREATE)
  }

  fun unbind(context: Context) {
    context.unbindService(this)
    binder?.let {
      binder = null
      onUnbind?.invoke(Event(this, it))
    }
  }
}
