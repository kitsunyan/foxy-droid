package nya.kitsunyan.foxydroid.screen

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toolbar
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.ProductPreferences
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.InstalledItem
import nya.kitsunyan.foxydroid.entity.Product
import nya.kitsunyan.foxydroid.entity.ProductPreference
import nya.kitsunyan.foxydroid.entity.Release
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.Utils.startPackageInstaller
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration

class ProductFragment(): ScreenFragment(), ProductAdapter.Callbacks {
  companion object {
    private const val EXTRA_PACKAGE_NAME = "packageName"

    private const val STATE_LAYOUT_MANAGER = "layoutManager"
    private const val STATE_ADAPTER = "adapter"
  }

  constructor(packageName: String): this() {
    arguments = Bundle().apply {
      putString(EXTRA_PACKAGE_NAME, packageName)
    }
  }

  private class Nullable<T>(val value: T?)

  private enum class Action(val id: Int, val adapterAction: ProductAdapter.Action, val iconResId: Int) {
    INSTALL(1, ProductAdapter.Action.INSTALL, R.drawable.ic_archive),
    UPDATE(2, ProductAdapter.Action.UPDATE, R.drawable.ic_archive),
    LAUNCH(3, ProductAdapter.Action.LAUNCH, R.drawable.ic_launch),
    DETAILS(4, ProductAdapter.Action.DETAILS, R.drawable.ic_tune),
    UNINSTALL(5, ProductAdapter.Action.UNINSTALL, R.drawable.ic_delete)
  }

  private class Installed(val installedItem: InstalledItem, val isSystem: Boolean,
    val launcherActivities: List<Pair<String, String>>)

  val packageName: String
    get() = requireArguments().getString(EXTRA_PACKAGE_NAME)!!

  private var layoutManagerState: LinearLayoutManager.SavedState? = null

  private var actions = Pair(emptySet<Action>(), null as Action?)
  private var products = emptyList<Pair<Product, Repository>>()
  private var installed: Installed? = null
  private var downloading = false

  private var toolbar: Toolbar? = null
  private var recyclerView: RecyclerView? = null

  private var productDisposable: Disposable? = null
  private var downloadDisposable: Disposable? = null
  private val downloadConnection = Connection(DownloadService::class.java, onBind = { _, binder ->
    updateDownloadState(binder.getState(packageName))
    downloadDisposable = binder.events(packageName).subscribe { updateDownloadState(it) }
  }, onUnbind = { _, _ ->
    downloadDisposable?.dispose()
    downloadDisposable = null
  })

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.fragment, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val toolbar = view.findViewById<Toolbar>(R.id.toolbar)!!
    screenActivity.onToolbarCreated(toolbar)
    toolbar.setTitle(R.string.application)
    this.toolbar = toolbar

    toolbar.menu.apply {
      for (action in Action.values()) {
        add(0, action.id, 0, action.adapterAction.titleResId)
          .setIcon(Utils.getToolbarIcon(toolbar.context, action.iconResId))
          .setVisible(false)
          .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
          .setOnMenuItemClickListener {
            onActionClick(action.adapterAction)
            true
          }
      }
    }

    val content = view.findViewById<FrameLayout>(R.id.fragment_content)!!
    content.addView(RecyclerView(content.context).apply {
      id = android.R.id.list
      val columns = (resources.configuration.screenWidthDp / 120).coerceIn(3, 5)
      val layoutManager = GridLayoutManager(context, columns)
      this.layoutManager = layoutManager
      isMotionEventSplittingEnabled = false
      isVerticalScrollBarEnabled = false
      val adapter = ProductAdapter(this@ProductFragment, columns)
      this.adapter = adapter
      layoutManager.spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
          return if (adapter.requiresGrid(position)) 1 else layoutManager.spanCount
        }
      }
      addOnScrollListener(scrollListener)
      addItemDecoration(adapter.gridItemDecoration)
      addItemDecoration(DividerItemDecoration(context, adapter::configureDivider))
      savedInstanceState?.getParcelable<ProductAdapter.SavedState>(STATE_ADAPTER)?.let(adapter::restoreState)
      layoutManagerState = savedInstanceState?.getParcelable(STATE_LAYOUT_MANAGER)
      recyclerView = this
    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    var first = true
    productDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Products))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.ProductAdapter.get(packageName, it) } }
      .flatMapSingle { products -> RxUtils
        .querySingle { Database.RepositoryAdapter.getAll(it) }
        .map { it.asSequence().map { Pair(it.id, it) }.toMap()
          .let { products.mapNotNull { product -> it[product.repositoryId]?.let { Pair(product, it) } } } } }
      .flatMapSingle { products -> RxUtils
        .querySingle { Nullable(Database.InstalledAdapter.get(packageName, it)) }
        .map { Pair(products, it) } }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe {
        val (products, installedItem) = it
        val firstChanged = first
        first = false
        val productChanged = this.products != products
        val installedItemChanged = this.installed?.installedItem != installedItem.value
        if (firstChanged || productChanged || installedItemChanged) {
          layoutManagerState?.let { recyclerView?.layoutManager!!.onRestoreInstanceState(it) }
          layoutManagerState = null
          if (firstChanged || productChanged) {
            this.products = products
          }
          if (firstChanged || installedItemChanged) {
            installed = installedItem.value?.let {
              val isSystem = try {
                ((requireContext().packageManager.getApplicationInfo(packageName, 0).flags)
                  and ApplicationInfo.FLAG_SYSTEM) != 0
              } catch (e: Exception) {
                false
              }
              val launcherActivities = if (packageName == requireContext().packageName) {
                // Don't allow to launch self
                emptyList()
              } else {
                val packageManager = requireContext().packageManager
                packageManager
                  .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                  .asSequence().mapNotNull { it.activityInfo }.filter { it.packageName == packageName }
                  .mapNotNull { activityInfo ->
                    val label = try {
                      activityInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                      e.printStackTrace()
                      null
                    }
                    label?.let { Pair(activityInfo.name, it) }
                  }
                  .toList()
              }
              Installed(it, isSystem, launcherActivities)
            }
          }
          val recyclerView = recyclerView!!
          val adapter = recyclerView.adapter as ProductAdapter
          if (firstChanged || productChanged || installedItemChanged) {
            adapter.setProducts(recyclerView.context, packageName, products, installedItem.value)
          }
          updateButtons()
        }
      }

    downloadConnection.bind(requireContext())
  }

  override fun onDestroyView() {
    super.onDestroyView()

    toolbar = null
    recyclerView = null

    productDisposable?.dispose()
    productDisposable = null
    downloadDisposable?.dispose()
    downloadDisposable = null
    downloadConnection.unbind(requireContext())
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    val layoutManagerState = layoutManagerState ?: recyclerView?.layoutManager?.onSaveInstanceState()
    layoutManagerState?.let { outState.putParcelable(STATE_LAYOUT_MANAGER, it) }
    val adapterState = (recyclerView?.adapter as? ProductAdapter)?.saveState()
    adapterState?.let { outState.putParcelable(STATE_ADAPTER, it) }
  }

  private fun updateButtons() {
    updateButtons(ProductPreferences[packageName])
  }

  private fun updateButtons(preference: ProductPreference) {
    val installed = installed
    val product = Product.findSuggested(products, installed?.installedItem) { it.first }?.first
    val compatible = product != null && product.selectedReleases.firstOrNull()
      .let { it != null && it.incompatibilities.isEmpty() }
    val canInstall = product != null && installed == null && compatible
    val canUpdate = product != null && compatible && product.canUpdate(installed?.installedItem) &&
      !preference.shouldIgnoreUpdate(product.versionCode)
    val canUninstall = product != null && installed != null && !installed.isSystem
    val canLaunch = product != null && installed != null && installed.launcherActivities.isNotEmpty()

    val actions = mutableSetOf<Action>()
    if (canInstall) {
      actions += Action.INSTALL
    }
    if (canUpdate) {
      actions += Action.UPDATE
    }
    if (canLaunch) {
      actions += Action.LAUNCH
    }
    if (installed != null) {
      actions += Action.DETAILS
    }
    if (canUninstall) {
      actions += Action.UNINSTALL
    }
    val primaryAction = when {
      canUpdate -> Action.UPDATE
      canLaunch -> Action.LAUNCH
      canInstall -> Action.INSTALL
      installed != null -> Action.DETAILS
      else -> null
    }

    val adapterAction = if (downloading) ProductAdapter.Action.CANCEL else primaryAction?.adapterAction
    (recyclerView?.adapter as? ProductAdapter)?.setAction(adapterAction)

    val toolbar = toolbar
    if (toolbar != null) {
      for (action in sequenceOf(Action.INSTALL, Action.UPDATE, Action.UNINSTALL)) {
        toolbar.menu.findItem(action.id).isEnabled = !downloading
      }
    }
    this.actions = Pair(actions, primaryAction)
    updateToolbarButtons()
  }

  private fun updateToolbarButtons() {
    val (actions, primaryAction) = actions
    val showPrimaryAction = recyclerView
      ?.let { (it.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() != 0 } == true
    val displayActions = actions.toMutableSet()
    if (!showPrimaryAction && primaryAction != null) {
      displayActions -= primaryAction
    }
    if (displayActions.size >= 4 && resources.configuration.screenWidthDp < 400) {
      displayActions -= Action.DETAILS
    }
    val toolbar = toolbar
    if (toolbar != null) {
      for (action in Action.values()) {
        toolbar.menu.findItem(action.id).isVisible = action in displayActions
      }
    }
  }

  private fun updateDownloadState(state: DownloadService.State?) {
    val status = when (state) {
      is DownloadService.State.Pending -> ProductAdapter.Status.Pending
      is DownloadService.State.Connecting -> ProductAdapter.Status.Connecting
      is DownloadService.State.Downloading -> ProductAdapter.Status.Downloading(state.read, state.total)
      is DownloadService.State.Success, is DownloadService.State.Error, is DownloadService.State.Cancel, null -> null
    }
    val downloading = status != null
    if (this.downloading != downloading) {
      this.downloading = downloading
      updateButtons()
    }
    (recyclerView?.adapter as? ProductAdapter)?.setStatus(status)
    if (state is DownloadService.State.Success && isResumed) {
      state.consume()
      screenActivity.startPackageInstaller(state.release.cacheFileName)
    }
  }

  private val scrollListener = object: RecyclerView.OnScrollListener() {
    private var lastPosition = -1

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
      val position = (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
      val lastPosition = lastPosition
      this.lastPosition = position
      if ((lastPosition == 0) != (position == 0)) {
        updateToolbarButtons()
      }
    }
  }

  override fun onActionClick(action: ProductAdapter.Action) {
    when (action) {
      ProductAdapter.Action.INSTALL,
      ProductAdapter.Action.UPDATE -> {
        val installedItem = installed?.installedItem
        val productRepository = Product.findSuggested(products, installedItem) { it.first }
        val compatibleReleases = productRepository?.first?.selectedReleases.orEmpty()
          .filter { installedItem == null || installedItem.signature == it.signature }
        val release = if (compatibleReleases.size >= 2) {
          compatibleReleases
            .filter { it.platforms.contains(Android.primaryPlatform) }
            .minByOrNull { it.platforms.size }
            ?: compatibleReleases.minByOrNull { it.platforms.size }
            ?: compatibleReleases.firstOrNull()
        } else {
          compatibleReleases.firstOrNull()
        }
        val binder = downloadConnection.binder
        if (productRepository != null && release != null && binder != null) {
          binder.enqueue(packageName, productRepository.first.name, productRepository.second, release)
        }
        Unit
      }
      ProductAdapter.Action.LAUNCH -> {
        val launcherActivities = installed?.launcherActivities.orEmpty()
        if (launcherActivities.size >= 2) {
          LaunchDialog(launcherActivities).show(childFragmentManager, LaunchDialog::class.java.name)
        } else {
          launcherActivities.firstOrNull()?.let { startLauncherActivity(it.first) }
        }
        Unit
      }
      ProductAdapter.Action.DETAILS -> {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
          .setData(Uri.parse("package:$packageName")))
      }
      ProductAdapter.Action.UNINSTALL -> {
        // TODO Handle deprecation
        @Suppress("DEPRECATION")
        startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE)
          .setData(Uri.parse("package:$packageName")))
      }
      ProductAdapter.Action.CANCEL -> {
        val binder = downloadConnection.binder
        if (downloading && binder != null) {
          binder.cancel(packageName)
        }
        Unit
      }
    }::class
  }

  private fun startLauncherActivity(name: String) {
    try {
      startActivity(Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(packageName, name))
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  override fun onPreferenceChanged(preference: ProductPreference) {
    updateButtons(preference)
  }

  override fun onPermissionsClick(group: String?, permissions: List<String>) {
    MessageDialog(MessageDialog.Message.Permissions(group, permissions)).show(childFragmentManager)
  }

  override fun onScreenshotClick(screenshot: Product.Screenshot) {
    val pair = products.asSequence()
      .map { Pair(it.second, it.first.screenshots.find { it === screenshot }?.identifier) }
      .filter { it.second != null }.firstOrNull()
    if (pair != null) {
      val (repository, identifier) = pair
      if (identifier != null) {
        ScreenshotsFragment(packageName, repository.id, identifier).show(childFragmentManager)
      }
    }
  }

  override fun onReleaseClick(release: Release) {
    val installedItem = installed?.installedItem
    when {
      release.incompatibilities.isNotEmpty() -> {
        MessageDialog(MessageDialog.Message.ReleaseIncompatible(release.incompatibilities,
          release.platforms, release.minSdkVersion, release.maxSdkVersion)).show(childFragmentManager)
      }
      installedItem != null && installedItem.versionCode > release.versionCode -> {
        MessageDialog(MessageDialog.Message.ReleaseOlder).show(childFragmentManager)
      }
      installedItem != null && installedItem.signature != release.signature -> {
        MessageDialog(MessageDialog.Message.ReleaseSignatureMismatch).show(childFragmentManager)
      }
      else -> {
        val productRepository = products.asSequence().filter { it.first.releases.any { it === release } }.firstOrNull()
        if (productRepository != null) {
          downloadConnection.binder?.enqueue(packageName, productRepository.first.name,
            productRepository.second, release)
        }
      }
    }
  }

  override fun onUriClick(uri: Uri, shouldConfirm: Boolean): Boolean {
    return if (shouldConfirm && (uri.scheme == "http" || uri.scheme == "https")) {
      MessageDialog(MessageDialog.Message.Link(uri)).show(childFragmentManager)
      true
    } else {
      try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
      } catch (e: ActivityNotFoundException) {
        e.printStackTrace()
        false
      }
    }
  }

  class LaunchDialog(): DialogFragment() {
    companion object {
      private const val EXTRA_NAMES = "names"
      private const val EXTRA_LABELS = "labels"
    }

    constructor(launcherActivities: List<Pair<String, String>>): this() {
      arguments = Bundle().apply {
        putStringArrayList(EXTRA_NAMES, ArrayList(launcherActivities.map { it.first }))
        putStringArrayList(EXTRA_LABELS, ArrayList(launcherActivities.map { it.second }))
      }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
      val names = requireArguments().getStringArrayList(EXTRA_NAMES)!!
      val labels = requireArguments().getStringArrayList(EXTRA_LABELS)!!
      return AlertDialog.Builder(requireContext())
        .setTitle(R.string.launch)
        .setItems(labels.toTypedArray()) { _, position -> (parentFragment as ProductFragment)
          .startLauncherActivity(names[position]) }
        .setNegativeButton(R.string.cancel, null)
        .create()
    }
  }
}
