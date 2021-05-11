package nya.kitsunyan.foxydroid.screen

import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.text.format.DateUtils
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.WEEK_IN_MILLIS
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.revrobotics.RevConstants
import com.revrobotics.RevUpdater
import com.revrobotics.mainThreadHandler
import com.revrobotics.queueDownloadAndUpdate
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.CursorOwner
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration
import nya.kitsunyan.foxydroid.widget.RecyclerFastScroller
import kotlin.concurrent.thread

class ProductsFragment(): ScreenFragment(), CursorOwner.Callback {
  companion object {
    // TAG for logging added by REV Robotics on 2021-05-09
    private const val TAG = "ProductsFragment"

    private const val EXTRA_SOURCE = "source"

    private const val STATE_CURRENT_SEARCH_QUERY = "currentSearchQuery"
    private const val STATE_CURRENT_SECTION = "currentSection"
    private const val STATE_CURRENT_ORDER = "currentOrder"
    private const val STATE_LAYOUT_MANAGER = "layoutManager"

    // Values and functions added by REV Robotics
    private const val JAN_1_2021_TIMESTAMP_MS = 1609459200000
    private const val LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX = "lastUpdateCheckRepo"
    private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    private val UPDATE_TAB_EMPTY_TEXT_UPDATE_TOKEN = Object()
    private val LAST_DOWNLOAD_TIMESTAMP_CHANGED_CALLBACKS: MutableList<()->Unit> = ArrayList()
    @Volatile
    private var lastDownloadOfAllReposTimestamp: Long = 0
      set(value) {
        field = value
        for (callback in LAST_DOWNLOAD_TIMESTAMP_CHANGED_CALLBACKS) {
          callback()
        }
      }

    fun markRepoAsJustDownloaded(repoId: Long) {
      RevConstants.SHARED_PREFS.edit().putLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + repoId, System.currentTimeMillis()).apply()
      calculateLastDownloadOfAllReposTimestamp()
    }

    fun markRepoAsNeverDownloaded(repoId: Long) {
      RevConstants.SHARED_PREFS.edit().putLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + repoId, 0).apply()
      calculateLastDownloadOfAllReposTimestamp()
    }

    private fun calculateLastDownloadOfAllReposTimestamp() {
      thread {
        synchronized(this) {
          var oldestTimestamp = Long.MAX_VALUE
          Database.RepositoryAdapter.getAll(null).asSequence()
            .filter { it.enabled }
            .forEach {
              val timeRepoWasLastUpdated = RevConstants.SHARED_PREFS.getLong(LAST_REPO_DOWNLOAD_TIMESTAMP_PREF_PREFIX + it.id, 0)
              if (timeRepoWasLastUpdated < oldestTimestamp) {
                oldestTimestamp = timeRepoWasLastUpdated
              }
            }
          // This if statement is to avoid calling the setter if the value hasn't changed
          if (lastDownloadOfAllReposTimestamp != oldestTimestamp) {
            lastDownloadOfAllReposTimestamp = oldestTimestamp
          }
        }
      }
    }


    init {
      calculateLastDownloadOfAllReposTimestamp()
    }
  }

  enum class Source(val titleResId: Int, val sections: Boolean, val order: Boolean) {
    // Modified by REV Robotics on 2021-04-28 to list the Updates tab first
    UPDATES(R.string.updates, false, false),
    AVAILABLE(R.string.available, true, true),
    INSTALLED(R.string.installed, false, false)
  }

  constructor(source: Source): this() {
    arguments = Bundle().apply {
      putString(EXTRA_SOURCE, source.name)
    }
  }

  val source: Source
    get() = requireArguments().getString(EXTRA_SOURCE)!!.let(Source::valueOf)

  private var searchQuery = ""
  private var section: ProductItem.Section = ProductItem.Section.All
  private var order = ProductItem.Order.NAME

  private var currentSearchQuery = ""
  private var currentSection: ProductItem.Section = ProductItem.Section.All
  private var currentOrder = ProductItem.Order.NAME
  private var layoutManagerState: Parcelable? = null

  private var recyclerView: RecyclerView? = null
  // updateAllButton added by REV Robotics on 2021-05-09
  private var updateAllButton: Button? = null

  private var repositoriesDisposable: Disposable? = null

  // downloadConnectionadded by REV Robotics on 2021-05-09
  private var downloadConnection: Connection<DownloadService.Binder, DownloadService>? = null

  private val request: CursorOwner.Request
    get() {
      val searchQuery = searchQuery
      val section = if (source.sections) section else ProductItem.Section.All
      val order = if (source.order) order else ProductItem.Order.NAME
      return when (source) {
        Source.AVAILABLE -> CursorOwner.Request.ProductsAvailable(searchQuery, section, order)
        Source.INSTALLED -> CursorOwner.Request.ProductsInstalled(searchQuery, section, order)
        Source.UPDATES -> CursorOwner.Request.ProductsUpdates(searchQuery, section, order)
      }
    }

  // Fields added by REV Robotics
  private val lastDownloadTimestampChangedCallback = {
    updateEmptyTextForUpdateTab(false)
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    // Dynamic empty text for the update tab was added by REV Robotics on 2021-04-29
    if (source == Source.UPDATES) {
      updateEmptyTextForUpdateTab(true)
      LAST_DOWNLOAD_TIMESTAMP_CHANGED_CALLBACKS.add(lastDownloadTimestampChangedCallback)
    }

    // The remainder of this function was reworked by REV Robotics on 2021-05-09 in order to support an Update ALl button

    downloadConnection = Connection(DownloadService::class.java)
    downloadConnection?.bind(requireContext())

    val layout = inflater.inflate(R.layout.products, container, false)
    val recyclerView: RecyclerView = layout.findViewById(R.id.products_recycler_view)
    val updateAllButton: Button = layout.findViewById(R.id.updateAllButton)

    recyclerView.setHasFixedSize(true)
    recyclerView.isVerticalScrollBarEnabled = false
    recyclerView.recycledViewPool.setMaxRecycledViews(ProductsAdapter.ViewType.PRODUCT.ordinal, 30)
    val adapter = ProductsAdapter { screenActivity.navigateProduct(it.packageName) }
    recyclerView.adapter = adapter
    recyclerView.addItemDecoration(DividerItemDecoration(recyclerView.context, adapter::configureDivider))
    RecyclerFastScroller(recyclerView)

    updateAllButton.setOnClickListener {
      updateAllButton.isEnabled = false
      updateAllButton.text = "Updating all software"
      RevUpdater.showProgressOnAnyScreen = true
      RevUpdater.showOrUpdateDialog("Downloading and installing all updates", activity!!)
      updateAll()
      // TODO(Noah): Reset the text and enable the button when all updates are installed
    }

    this.recyclerView = recyclerView
    this.updateAllButton = updateAllButton
    return layout
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    currentSearchQuery = savedInstanceState?.getString(STATE_CURRENT_SEARCH_QUERY).orEmpty()
    currentSection = savedInstanceState?.getParcelable(STATE_CURRENT_SECTION) ?: ProductItem.Section.All
    currentOrder = savedInstanceState?.getString(STATE_CURRENT_ORDER)
      ?.let(ProductItem.Order::valueOf) ?: ProductItem.Order.NAME
    layoutManagerState = savedInstanceState?.getParcelable(STATE_LAYOUT_MANAGER)

    screenActivity.cursorOwner.attach(this, request)
    repositoriesDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Repositories))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.RepositoryAdapter.getAll(it) } }
      .map { it.asSequence().map { Pair(it.id, it) }.toMap() }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { (recyclerView?.adapter as? ProductsAdapter)?.repositories = it }
  }

  override fun onDestroyView() {
    super.onDestroyView()

    // Added by REV Robotics on 2021-04-29: Cancel future updates of the update tab's empty text
    MAIN_HANDLER.removeCallbacksAndMessages(UPDATE_TAB_EMPTY_TEXT_UPDATE_TOKEN)
    LAST_DOWNLOAD_TIMESTAMP_CHANGED_CALLBACKS.remove(lastDownloadTimestampChangedCallback)

    recyclerView = null

    // These 3 lines added by REV Robotics on 2021-05-09
    updateAllButton = null
    downloadConnection?.unbind(requireContext())
    downloadConnection = null

    screenActivity.cursorOwner.detach(this)
    repositoriesDisposable?.dispose()
    repositoriesDisposable = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putString(STATE_CURRENT_SEARCH_QUERY, currentSearchQuery)
    outState.putParcelable(STATE_CURRENT_SECTION, currentSection)
    outState.putString(STATE_CURRENT_ORDER, currentOrder.name)
    (layoutManagerState ?: recyclerView?.layoutManager?.onSaveInstanceState())
      ?.let { outState.putParcelable(STATE_LAYOUT_MANAGER, it) }
  }

  override fun onCursorData(request: CursorOwner.Request, cursor: Cursor?) {
    (recyclerView?.adapter as? ProductsAdapter)?.apply {
      this.cursor = cursor
      emptyText = when {
        cursor == null -> ""
        searchQuery.isNotEmpty() -> getString(R.string.no_matching_applications_found)
        else -> when (source) {
          Source.AVAILABLE -> getString(R.string.no_applications_available)
          Source.INSTALLED -> getString(R.string.no_applications_installed)
          Source.UPDATES -> getUpdatesTabEmptyText() // Modified by REV Robotics on 2021-04-29
        }

      }
      // Lines added by REV Robotics on 2021-05-09 to control visibility of Update All button
      if (source == Source.UPDATES && itemCount > 0 && getItemEnumViewType(0) == ProductsAdapter.ViewType.PRODUCT) {
        updateAllButton?.visibility = View.VISIBLE
      } else {
        updateAllButton?.visibility = View.GONE
      }
    }

    layoutManagerState?.let {
      layoutManagerState = null
      recyclerView?.layoutManager?.onRestoreInstanceState(it)
    }

    if (currentSearchQuery != searchQuery || currentSection != section || currentOrder != order) {
      currentSearchQuery = searchQuery
      currentSection = section
      currentOrder = order
      recyclerView?.scrollToPosition(0)
    }
  }

  internal fun setSearchQuery(searchQuery: String) {
    if (this.searchQuery != searchQuery) {
      this.searchQuery = searchQuery
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  internal fun setSection(section: ProductItem.Section) {
    if (this.section != section) {
      this.section = section
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  internal fun setOrder(order: ProductItem.Order) {
    if (this.order != order) {
      this.order = order
      if (view != null) {
        screenActivity.cursorOwner.attach(this, request)
      }
    }
  }

  // These functions were added by REV Robotics on 2021-04-29
  // to support showing how long it has been since we successfully
  // downloaded updates for all repositories.
  private fun updateEmptyTextForUpdateTab(scheduleNextUpdate: Boolean) {
    MAIN_HANDLER.post {
      (recyclerView?.adapter as? ProductsAdapter)?.apply {
        emptyText = getUpdatesTabEmptyText()
      }
    }
    if (scheduleNextUpdate) {
      MAIN_HANDLER.postDelayed({
        updateEmptyTextForUpdateTab(true)
      }, UPDATE_TAB_EMPTY_TEXT_UPDATE_TOKEN, MINUTE_IN_MILLIS)
    }
  }

  private fun getUpdatesTabEmptyText(): String {
    val asOfPrefix = " as of "
    val msSinceTimestamp = System.currentTimeMillis() - lastDownloadOfAllReposTimestamp

    if (lastDownloadOfAllReposTimestamp < JAN_1_2021_TIMESTAMP_MS) {
      // If we haven't checked for updates in an impossibly long time, assume we never have.
      return "Please connect to the Internet and check for updates"
    }

    // We have to tell getRelativeTimeSpanString() whether we want the results in minutes, hours, days, or weeks
    val howLongAgo = when {
      msSinceTimestamp < 10 * MINUTE_IN_MILLIS -> { // Syncing repositories can take a while, so we have a ten minute grace window
        ""
      }
      msSinceTimestamp < HOUR_IN_MILLIS -> {
        asOfPrefix + DateUtils.getRelativeTimeSpanString(lastDownloadOfAllReposTimestamp, System.currentTimeMillis(), MINUTE_IN_MILLIS)
      }
      msSinceTimestamp < DAY_IN_MILLIS -> {
        asOfPrefix + DateUtils.getRelativeTimeSpanString(lastDownloadOfAllReposTimestamp, System.currentTimeMillis(), HOUR_IN_MILLIS)
      }
      msSinceTimestamp < WEEK_IN_MILLIS -> {
        asOfPrefix + DateUtils.getRelativeTimeSpanString(lastDownloadOfAllReposTimestamp, System.currentTimeMillis(), DAY_IN_MILLIS)
      }
      else -> {
        asOfPrefix + DateUtils.getRelativeTimeSpanString(lastDownloadOfAllReposTimestamp, System.currentTimeMillis(), WEEK_IN_MILLIS)
      }
    }
    return getString(R.string.all_applications_up_to_date) + howLongAgo
  }

  // updateAll() function added by REV Robotics on 2021-05-09
  private fun updateAll() {
    val downloadConnection = this.downloadConnection
    if (downloadConnection?.binder == null) {
      mainThreadHandler.postDelayed(::updateAll, 1000)
    } else {
      var thisApp: ProductItem? = null
      var driverHubOs: ProductItem? = null
      val adapter = recyclerView?.adapter as ProductsAdapter?
      if (adapter == null) {
        Log.e(TAG, "RecyclerView adapter is null in updateAll()")
        return
      }
      for (i in 0 until adapter.itemCount) {
        val product = adapter.getProductItem(i)
        when (product.packageName) {
          BuildConfig.APPLICATION_ID -> {
            Log.d("Noah", "Deferring update for ${product.packageName}")
            thisApp = product
          }
          RevConstants.DRIVER_HUB_OS_CONTAINER_PACKAGE -> {
            Log.d("Noah", "Deferring update for ${product.packageName}")
            driverHubOs = product
          }
          else -> {
            Log.d("Noah", "Queueing download for ${product.packageName}")
            queueDownloadAndUpdate(product.packageName, downloadConnection)
          }
        }
      } // for loop
      if (thisApp != null && driverHubOs != null) {
        // Both this app and the Driver Hub OS need to be updated. To handle this, we set a flag to cause the OS to
        // auto-update the next time this app is launched, another flag to specify that the OS should not be installed
        // immediately upon download completion, and then add the OS and this app to the update queue. The intended
        // behavior is for the OS to already be downloaded before this app restarts due to it being updated.
        RevConstants.shouldAutoInstallOsOnNextLaunch = true
        RevConstants.shouldAutoInstallOSWhenDownloadCompletes = false
        Log.d("Noah", "Queueing updates for OS and ourselves (${thisApp.packageName})")

        queueDownloadAndUpdate(RevConstants.DRIVER_HUB_OS_CONTAINER_PACKAGE, downloadConnection)
        queueDownloadAndUpdate(thisApp.packageName, downloadConnection)
      } else {
        // This app and the Driver Hub OS don't both need to be updated, so if one of them needs an update, queue it.
        thisApp?.let {
          Log.d("Noah", "Queueing download for ${it.packageName}")
          queueDownloadAndUpdate(it.packageName, downloadConnection)
        }
        driverHubOs?.let {
          Log.d("Noah", "Queueing download for ${it.packageName}")
          queueDownloadAndUpdate(it.packageName, downloadConnection)
        }
      }
    }
  }
}
