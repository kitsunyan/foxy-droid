package com.revrobotics

import android.content.Context
import android.content.SharedPreferences
import nya.kitsunyan.foxydroid.MainApplication

object RevConstants {
  const val DRIVER_HUB_OS_CONTAINER_PACKAGE = "com.revrobotics.driverhuboscontainer"
  const val DRIVER_HUB_OS_VERSION_NAME_PROPERTY = "ro.driverhub.os.version"
  const val DRIVER_HUB_OS_VERSION_CODE_PROPERTY = "ro.driverhub.os.versionnum"
  val SHARED_PREFS: SharedPreferences = MainApplication.instance.getSharedPreferences("revrobotics", Context.MODE_PRIVATE)

}
