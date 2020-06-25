package nya.kitsunyan.foxydroid.screen

import android.database.Cursor
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.CursorOwner
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration
import nya.kitsunyan.foxydroid.widget.RecyclerFastScroller

class ProductsFragment(): Fragment(), CursorOwner.Callback {
  companion object {
    private const val EXTRA_SOURCE = "source"

    private const val STATE_CURRENT_SEARCH_QUERY = "currentSearchQuery"
    private const val STATE_CURRENT_CATEGORY = "currentCategory"
    private const val STATE_CURRENT_ORDER = "currentOrder"
    private const val STATE_LAYOUT_MANAGER = "layoutManager"
  }

  enum class Source(val titleResId: Int, val categories: Boolean, val order: Boolean) {
    AVAILABLE(R.string.available, true, true),
    INSTALLED(R.string.installed, false, false),
    UPDATES(R.string.updates, false, false)
  }

  constructor(source: Source): this() {
    arguments = Bundle().apply {
      putString(EXTRA_SOURCE, source.name)
    }
  }

  val source: Source
    get() = requireArguments().getString(EXTRA_SOURCE)!!.let(Source::valueOf)

  private var searchQuery = ""
  private var category = ""
  private var order = ProductItem.Order.NAME

  private var currentSearchQuery = ""
  private var currentCategory = ""
  private var currentOrder = ProductItem.Order.NAME
  private var layoutManagerState: Parcelable? = null

  private var recyclerView: RecyclerView? = null

  private var repositoriesDisposable: Disposable? = null

  private val request: CursorOwner.Request
    get() {
      val searchQuery = searchQuery
      val category = if (source.categories) category else ""
      val order = if (source.order) order else ProductItem.Order.NAME
      return when (source) {
        Source.AVAILABLE -> CursorOwner.Request.ProductsAvailable(searchQuery, category, order)
        Source.INSTALLED -> CursorOwner.Request.ProductsInstalled(searchQuery, category, order)
        Source.UPDATES -> CursorOwner.Request.ProductsUpdates(searchQuery, category, order)
      }
    }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return RecyclerView(requireContext()).apply {
      id = android.R.id.list
      layoutManager = LinearLayoutManager(context)
      isMotionEventSplittingEnabled = false
      isVerticalScrollBarEnabled = false
      setHasFixedSize(true)
      recycledViewPool.setMaxRecycledViews(ProductsAdapter.ViewType.PRODUCT.ordinal, 30)
      adapter = ProductsAdapter { screenActivity.navigateProduct(it.packageName) }
      addItemDecoration(DividerItemDecoration(context,
        DividerItemDecoration.fixed(context.resources.sizeScaled(72), 0)))
      RecyclerFastScroller(this)
      recyclerView = this
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    currentSearchQuery = savedInstanceState?.getString(STATE_CURRENT_SEARCH_QUERY).orEmpty()
    currentCategory = savedInstanceState?.getString(STATE_CURRENT_CATEGORY).orEmpty()
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

    recyclerView = null

    screenActivity.cursorOwner.detach(this)
    repositoriesDisposable?.dispose()
    repositoriesDisposable = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putString(STATE_CURRENT_SEARCH_QUERY, currentSearchQuery)
    outState.putString(STATE_CURRENT_CATEGORY, currentCategory)
    outState.putString(STATE_CURRENT_ORDER, currentOrder.name)
    (layoutManagerState ?: recyclerView?.layoutManager?.onSaveInstanceState())
      ?.let { outState.putParcelable(STATE_LAYOUT_MANAGER, it) }
  }

  override fun onCursorData(request: CursorOwner.Request, cursor: Cursor?) {
    (recyclerView?.adapter as? ProductsAdapter)?.apply {
      this.cursor = cursor
      emptyText = when {
        cursor == null -> ""
        searchQuery.isNotEmpty() -> getString(R.string.empty_search_summary)
        else -> when (source) {
          Source.AVAILABLE -> getString(R.string.available_empty_summary)
          Source.INSTALLED -> getString(R.string.installed_empty_summary)
          Source.UPDATES -> getString(R.string.updates_empty_summary)
        }
      }
    }

    layoutManagerState?.let {
      layoutManagerState = null
      recyclerView?.layoutManager?.onRestoreInstanceState(it)
    }

    if (currentSearchQuery != searchQuery || currentCategory != category || currentOrder != order) {
      currentSearchQuery = searchQuery
      currentCategory = category
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

  internal fun setCategory(category: String) {
    if (this.category != category) {
      this.category = category
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
}
