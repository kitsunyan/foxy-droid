package nya.kitsunyan.foxydroid.screen

import androidx.fragment.app.Fragment

val Fragment.screenActivity: ScreenActivity
  get() = requireActivity() as ScreenActivity
