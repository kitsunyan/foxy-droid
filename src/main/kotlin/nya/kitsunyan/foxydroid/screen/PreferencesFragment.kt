package nya.kitsunyan.foxydroid.screen

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreference
import io.reactivex.rxjava3.disposables.Disposable
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Preferences

class PreferencesFragment: PreferenceFragmentCompat() {
  private var disposable: Disposable? = null

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    val view = inflater.inflate(R.layout.fragment, container, false)
    val content = view.findViewById<FrameLayout>(R.id.fragment_content)
    val child = super.onCreateView(LayoutInflater.from(content.context), content, savedInstanceState)
    content.addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    return view
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    preferenceScreen.addCategory(getString(R.string.updates)).apply {
      addEnumeration(Preferences.Key.AutoSync, getString(R.string.sync_repositories_automatically)) {
        when (it) {
          Preferences.AutoSync.Never -> getString(R.string.never)
          Preferences.AutoSync.Wifi -> getString(R.string.over_wifi)
          Preferences.AutoSync.Always -> getString(R.string.always)
        }
      }
      addSwitch(Preferences.Key.UpdateNotify, getString(R.string.update_notifications),
        getString(R.string.update_notifications_summary))
      addSwitch(Preferences.Key.UpdateUnstable, getString(R.string.unstable_updates),
        getString(R.string.unstable_updates_summary))
    }
    preferenceScreen.addCategory(getString(R.string.proxy)).apply {
      addEnumeration(Preferences.Key.ProxyType, getString(R.string.proxy_type)) {
        when (it) {
          is Preferences.ProxyType.Direct -> getString(R.string.no_proxy)
          is Preferences.ProxyType.Http -> getString(R.string.http_proxy)
          is Preferences.ProxyType.Socks -> getString(R.string.socks_proxy)
        }
      }
      addEditString(Preferences.Key.ProxyHost, getString(R.string.proxy_host))
      addEditInt(Preferences.Key.ProxyPort, getString(R.string.proxy_port), 1 .. 65535)
    }
    preferenceScreen.addCategory(getString(R.string.other)).apply {
      addEnumeration(Preferences.Key.Theme, getString(R.string.theme)) {
        when (it) {
          is Preferences.Theme.Light -> getString(R.string.light)
          is Preferences.Theme.Dark -> getString(R.string.dark)
        }
      }
      addSwitch(Preferences.Key.IncompatibleVersions, getString(R.string.incompatible_versions),
        getString(R.string.incompatible_versions_summary))
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
    screenActivity.onFragmentViewCreated(toolbar)
    toolbar.setTitle(R.string.preferences)

    disposable = Preferences.observable.subscribe(this::updatePreference)
    updatePreference(null)
  }

  override fun onDestroyView() {
    super.onDestroyView()

    disposable?.dispose()
    disposable = null
  }

  private fun updatePreference(key: Preferences.Key<*>?) {
    if (key == null || key == Preferences.Key.ProxyType) {
      val enabled = when (Preferences[Preferences.Key.ProxyType]) {
        is Preferences.ProxyType.Direct -> false
        is Preferences.ProxyType.Http, is Preferences.ProxyType.Socks -> true
      }
      findPreference<Preference>(Preferences.Key.ProxyHost.name)!!.isEnabled = enabled
      findPreference<Preference>(Preferences.Key.ProxyPort.name)!!.isEnabled = enabled
    }
    if (key == Preferences.Key.Theme) {
      requireActivity().recreate()
    }
  }

  private fun PreferenceGroup.addCategory(title: String): PreferenceCategory {
    val preference = PreferenceCategory(context)
    preference.isIconSpaceReserved = false
    preference.title = title
    addPreference(preference)
    return preference
  }

  private fun PreferenceGroup.addSwitch(key: Preferences.Key<Boolean>, title: String, summary: String?) {
    val preference = SwitchPreference(context)
    preference.isIconSpaceReserved = false
    preference.title = title
    preference.summary = summary
    preference.key = key.name
    preference.setDefaultValue(key.default.value)
    addPreference(preference)
  }

  private fun PreferenceGroup.addEditString(key: Preferences.Key<String>, title: String) {
    val preference = EditTextPreference(context)
    preference.isIconSpaceReserved = false
    preference.title = title
    preference.dialogTitle = title
    preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text }
    preference.key = key.name
    preference.setDefaultValue(key.default.value)
    addPreference(preference)
  }

  private fun PreferenceGroup.addEditInt(key: Preferences.Key<Int>, title: String, range: IntRange?) {
    val preference = object: EditTextPreference(context) {
      override fun persistString(value: String?): Boolean {
        val intValue = value.orEmpty().toIntOrNull() ?: key.default.value
        val result = persistInt(intValue)
        if (intValue.toString() != value) {
          text = intValue.toString()
        }
        return result
      }

      override fun onSetInitialValue(defaultValue: Any?) {
        text = getPersistedInt((defaultValue as? Int) ?: key.default.value).toString()
      }
    }
    preference.isIconSpaceReserved = false
    preference.title = title
    preference.dialogTitle = title
    preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text }
    preference.key = key.name
    preference.setDefaultValue(key.default.value)
    preference.setOnBindEditTextListener {
      it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
      if (range != null) {
        it.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
          val value = (dest.substring(0, dstart) + source.substring(start, end) +
            dest.substring(dend, dest.length)).toIntOrNull()
          if (value != null && value in range) null else ""
        })
      }
    }
    addPreference(preference)
  }

  private fun <T: Preferences.Enumeration<T>> PreferenceGroup
    .addEnumeration(key: Preferences.Key<T>, title: String, valueString: (T) -> String) {
    val preference = ListPreference(context)
    preference.isIconSpaceReserved = false
    preference.title = title
    preference.dialogTitle = title
    preference.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
      val index = p.entryValues.indexOfFirst { it == p.value }
      if (index >= 0) p.entries[index] else valueString(key.default.value)
    }
    preference.key = key.name
    preference.setDefaultValue(key.default.value.valueString)
    preference.entryValues = key.default.value.values.map { it.valueString }.toTypedArray()
    preference.entries = key.default.value.values.map(valueString).toTypedArray()
    addPreference(preference)
  }
}
