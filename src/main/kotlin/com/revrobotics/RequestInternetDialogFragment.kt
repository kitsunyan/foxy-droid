package com.revrobotics

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.DialogFragment

class RequestInternetDialogFragment: DialogFragment() {
  companion object {
    var instance: RequestInternetDialogFragment? = null
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    instance = this
    return activity?.let {
      val dialog = AlertDialog.Builder(it)
          .setMessage("Please connect to the Internet")
          .setNegativeButton("Cancel", null)
          .setOnDismissListener {
            actionWaitingForInternetConnection = null
            instance = null
          }
          // We use a null listener, and later override the underlying onClickListener, so the dialog doesn't get dismissed
          .setPositiveButton("Wi-Fi settings", null)
          .create()

      dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
          startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            putExtra("com.revrobotics.wifiConnectionReason", "Connect to the Internet for software downloads")
          })
        }
      }

      return dialog
    } ?: throw IllegalStateException("Activity cannot be null")
  }
}
