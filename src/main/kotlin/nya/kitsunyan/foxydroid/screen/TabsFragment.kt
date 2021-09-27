package nya.kitsunyan.foxydroid.screen

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.database.CursorOwner
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.SyncService
import nya.kitsunyan.foxydroid.utility.RxUtils
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.widget.DividerItemDecoration
import nya.kitsunyan.foxydroid.widget.FocusSearchView
import nya.kitsunyan.foxydroid.widget.StableRecyclerAdapter
import kotlin.math.*

class TabsFragment: ScreenFragment() {
  companion object {
    private const val STATE_SEARCH_FOCUSED = "searchFocused"
    private const val STATE_SEARCH_QUERY = "searchQuery"
    private const val STATE_SHOW_SECTIONS = "showSections"
    private const val STATE_SECTIONS = "sections"
    private const val STATE_SECTION = "section"
  }

  private class Layout(view: View) {
    val tabs = view.findViewById<LinearLayout>(R.id.tabs)!!
    val sectionLayout = view.findViewById<ViewGroup>(R.id.section_layout)!!
    val sectionChange = view.findViewById<View>(R.id.section_change)!!
    val sectionName = view.findViewById<TextView>(R.id.section_name)!!
    val sectionIcon = view.findViewById<ImageView>(R.id.section_icon)!!
    val updateAll = view.findViewById<Button>(R.id.update_all)!! // TODO update visibility based on need
  }

  private var searchMenuItem: MenuItem? = null
  private var sortOrderMenu: Pair<MenuItem, List<MenuItem>>? = null
  private var syncRepositoriesMenuItem: MenuItem? = null
  private var layout: Layout? = null
  private var sectionsList: RecyclerView? = null
  private var viewPager: ViewPager2? = null
  private var productsList: MutableList<ProductItem> = mutableListOf() // TODO to keep a list of updated apps

  private var showSections = false
    set(value) {
      if (field != value) {
        field = value
        val layout = layout
        layout?.tabs?.let { (0 until it.childCount)
          .forEach { index -> it.getChildAt(index)!!.isEnabled = !value } }
        layout?.sectionIcon?.scaleY = if (value) -1f else 1f
        if ((sectionsList?.parent as? View)?.height ?: 0 > 0) {
          animateSectionsList()
        }
      }
    }

  private var searchQuery = ""
  private var sections = listOf<ProductItem.Section>(ProductItem.Section.All)
  private var section: ProductItem.Section = ProductItem.Section.All

  private val syncConnection = Connection(SyncService::class.java, onBind = { _, _ ->
    viewPager?.let {
      val source = ProductsFragment.Source.values()[it.currentItem]
      updateUpdateNotificationBlocker(source)
    }
  })

  private var sortOrderDisposable: Disposable? = null
  private var categoriesDisposable: Disposable? = null
  private var repositoriesDisposable: Disposable? = null
  private var sectionsAnimator: ValueAnimator? = null

  private var needSelectUpdates = false

  private val productFragments: Sequence<ProductsFragment>
    get() = if (host == null) emptySequence() else
      childFragmentManager.fragments.asSequence().mapNotNull { it as? ProductsFragment }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.inflate(R.layout.fragment, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    syncConnection.bind(requireContext())

    val toolbar = view.findViewById<Toolbar>(R.id.toolbar)!!
    screenActivity.onToolbarCreated(toolbar)
    toolbar.setTitle(R.string.application_name)
    // Move focus from SearchView to Toolbar
    toolbar.isFocusableInTouchMode = true

    val searchView = FocusSearchView(toolbar.context)
    searchView.allowFocus = savedInstanceState?.getBoolean(STATE_SEARCH_FOCUSED) == true
    searchView.maxWidth = Int.MAX_VALUE
    searchView.queryHint = getString(R.string.search)
    searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
      override fun onQueryTextSubmit(query: String?): Boolean {
        searchView.clearFocus()
        return true
      }

      override fun onQueryTextChange(newText: String?): Boolean {
        if (isResumed) {
          searchQuery = newText.orEmpty()
          productFragments.forEach { it.setSearchQuery(newText.orEmpty()) }
        }
        return true
      }
    })

    toolbar.menu.apply {
      if (Android.sdk(28) && !Android.Device.isHuaweiEmui) {
        setGroupDividerEnabled(true)
      }

      searchMenuItem = add(0, R.id.toolbar_search, 0, R.string.search)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_search))
        .setActionView(searchView)
        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)

      sortOrderMenu = addSubMenu(0, 0, 0, R.string.sorting_order)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_sort))
        .let { menu ->
          menu.item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
          val items = Preferences.Key.SortOrder.default.value.values
            .map { sortOrder -> menu
              .add(sortOrder.order.titleResId)
              .setOnMenuItemClickListener {
                Preferences[Preferences.Key.SortOrder] = sortOrder
                true
              } }
          menu.setGroupCheckable(0, true, true)
          Pair(menu.item, items)
        }

      syncRepositoriesMenuItem = add(0, 0, 0, R.string.sync_repositories)
        .setIcon(Utils.getToolbarIcon(toolbar.context, R.drawable.ic_sync))
        .setOnMenuItemClickListener {
          syncConnection.binder?.sync(SyncService.SyncRequest.MANUAL)
          true
        }

      add(1, 0, 0, R.string.repositories)
        .setOnMenuItemClickListener {
          view.post { screenActivity.navigateRepositories() }
          true
        }

      add(1, 0, 0, R.string.preferences)
        .setOnMenuItemClickListener {
          view.post { screenActivity.navigatePreferences() }
          true
        }
    }

    searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
    productFragments.forEach { it.setSearchQuery(searchQuery) }

    val toolbarExtra = view.findViewById<FrameLayout>(R.id.toolbar_extra)!!
    toolbarExtra.addView(toolbarExtra.inflate(R.layout.tabs_toolbar))
    val layout = Layout(view)
    this.layout = layout

    layout.tabs.background = TabsBackgroundDrawable(layout.tabs.context,
      layout.tabs.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    ProductsFragment.Source.values().forEach {
      val tab = TextView(layout.tabs.context)
      val selectedColor = tab.context.getColorFromAttr(android.R.attr.textColorPrimary).defaultColor
      val normalColor = tab.context.getColorFromAttr(android.R.attr.textColorSecondary).defaultColor
      tab.gravity = Gravity.CENTER
      tab.typeface = TypefaceExtra.medium
      tab.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(selectedColor, normalColor)))
      tab.setTextSizeScaled(14)
      tab.isAllCaps = true
      tab.text = getString(it.titleResId)
      tab.background = tab.context.getDrawableFromAttr(android.R.attr.selectableItemBackground)
      tab.setOnClickListener { _ ->
        setSelectedTab(it)
        viewPager!!.setCurrentItem(it.ordinal, Utils.areAnimationsEnabled(tab.context))
      }
      layout.tabs.addView(tab, 0, LinearLayout.LayoutParams.MATCH_PARENT)
      (tab.layoutParams as LinearLayout.LayoutParams).weight = 1f
    }

    showSections = savedInstanceState?.getByte(STATE_SHOW_SECTIONS)?.toInt() ?: 0 != 0
    sections = savedInstanceState?.getParcelableArrayList<ProductItem.Section>(STATE_SECTIONS).orEmpty()
    section = savedInstanceState?.getParcelable(STATE_SECTION) ?: ProductItem.Section.All
    layout.sectionChange.setOnClickListener { showSections = sections
      .any { it !is ProductItem.Section.All } && !showSections }
    layout.updateAll.setOnClickListener { updateAll() }

    updateOrder()
    sortOrderDisposable = Preferences.observable.subscribe {
      if (it == Preferences.Key.SortOrder) {
        updateOrder()
      }
    }

    val content = view.findViewById<FrameLayout>(R.id.fragment_content)!!

    viewPager = ViewPager2(content.context).apply {
      id = R.id.fragment_pager
      adapter = object: FragmentStateAdapter(this@TabsFragment) {
        override fun getItemCount(): Int = ProductsFragment.Source.values().size
        override fun createFragment(position: Int): Fragment = ProductsFragment(ProductsFragment
          .Source.values()[position])
      }
      content.addView(this, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
      registerOnPageChangeCallback(pageChangeCallback)
      offscreenPageLimit = 1
    }

    categoriesDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Products))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.CategoryAdapter.getAll(it) } }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { setSectionsAndUpdate(it.asSequence().sorted()
        .map(ProductItem.Section::Category).toList(), null) }
    repositoriesDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Repositories))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.RepositoryAdapter.getAll(it) } }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { setSectionsAndUpdate(null, it.asSequence().filter { it.enabled }
        .map { ProductItem.Section.Repository(it.id, it.name) }.toList()) }
    updateSection()

    val sectionsList = RecyclerView(toolbar.context).apply {
      id = R.id.sections_list
      layoutManager = LinearLayoutManager(context)
      isMotionEventSplittingEnabled = false
      isVerticalScrollBarEnabled = false
      setHasFixedSize(true)
      val adapter = SectionsAdapter({ sections }) {
        if (showSections) {
          showSections = false
          section = it
          updateSection()
        }
      }
      this.adapter = adapter
      addItemDecoration(DividerItemDecoration(context, adapter::configureDivider))
      setBackgroundColor(context.getColorFromAttr(android.R.attr.colorPrimaryDark).defaultColor)
      elevation = resources.sizeScaled(4).toFloat()
      content.addView(this, FrameLayout.LayoutParams.MATCH_PARENT, 0)
      visibility = View.GONE
    }
    this.sectionsList = sectionsList

    var lastContentHeight = -1
    content.viewTreeObserver.addOnGlobalLayoutListener {
      if (this.view != null) {
        val initial = lastContentHeight <= 0
        val contentHeight = content.height
        if (lastContentHeight != contentHeight) {
          lastContentHeight = contentHeight
          if (initial) {
            sectionsList.layoutParams.height = if (showSections) contentHeight else 0
            sectionsList.visibility = if (showSections) View.VISIBLE else View.GONE
            sectionsList.requestLayout()
          } else {
            animateSectionsList()
          }
        }
      }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()

    searchMenuItem = null
    sortOrderMenu = null
    syncRepositoriesMenuItem = null
    layout = null
    sectionsList = null
    viewPager = null

    syncConnection.unbind(requireContext())
    sortOrderDisposable?.dispose()
    sortOrderDisposable = null
    categoriesDisposable?.dispose()
    categoriesDisposable = null
    repositoriesDisposable?.dispose()
    repositoriesDisposable = null
    sectionsAnimator?.cancel()
    sectionsAnimator = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    outState.putBoolean(STATE_SEARCH_FOCUSED, searchMenuItem?.actionView?.hasFocus() == true)
    outState.putString(STATE_SEARCH_QUERY, searchQuery)
    outState.putByte(STATE_SHOW_SECTIONS, if (showSections) 1 else 0)
    outState.putParcelableArrayList(STATE_SECTIONS, ArrayList(sections))
    outState.putParcelable(STATE_SECTION, section)
  }

  override fun onViewStateRestored(savedInstanceState: Bundle?) {
    super.onViewStateRestored(savedInstanceState)

    (searchMenuItem?.actionView as FocusSearchView).allowFocus = true
    if (needSelectUpdates) {
      needSelectUpdates = false
      selectUpdatesInternal(false)
    }
  }

  override fun onAttachFragment(childFragment: Fragment) {
    super.onAttachFragment(childFragment)

    if (view != null && childFragment is ProductsFragment) {
      childFragment.setSearchQuery(searchQuery)
      childFragment.setSection(section)
      childFragment.setOrder(Preferences[Preferences.Key.SortOrder].order)
    }
  }

  override fun onBackPressed(): Boolean {
    return when {
      searchMenuItem?.isActionViewExpanded == true -> {
        searchMenuItem?.collapseActionView()
        true
      }
      showSections -> {
        showSections = false
        true
      }
      else -> {
        super.onBackPressed()
      }
    }
  }

  private fun setSelectedTab(source: ProductsFragment.Source) {
    val layout = layout!!
    (0 until layout.tabs.childCount).forEach { layout.tabs.getChildAt(it).isSelected = it == source.ordinal }
  }

  internal fun selectUpdates() = selectUpdatesInternal(true)

  private fun selectUpdatesInternal(allowSmooth: Boolean) {
    if (view != null) {
      val viewPager = viewPager
      viewPager?.setCurrentItem(ProductsFragment.Source.UPDATES.ordinal, allowSmooth && viewPager.isLaidOut)
    } else {
      needSelectUpdates = true
    }
  }

  private fun updateUpdateNotificationBlocker(activeSource: ProductsFragment.Source) {
    val blockerFragment = if (activeSource == ProductsFragment.Source.UPDATES) {
      productFragments.find { it.source == activeSource }
    } else {
      null
    }
    syncConnection.binder?.setUpdateNotificationBlocker(blockerFragment)
  }

  private fun updateOrder() {
    val order = Preferences[Preferences.Key.SortOrder].order
    sortOrderMenu!!.second[order.ordinal].isChecked = true
    productFragments.forEach { it.setOrder(order) }
  }

  private inline fun <reified T: ProductItem.Section> collectOldSections(list: List<T>?): List<T>? {
    val oldList = sections.mapNotNull { it as? T }
    return if (list == null || oldList == list) oldList else null
  }

  private fun setSectionsAndUpdate(categories: List<ProductItem.Section.Category>?,
    repositories: List<ProductItem.Section.Repository>?) {
    val oldCategories = collectOldSections(categories)
    val oldRepositories = collectOldSections(repositories)
    if (oldCategories == null || oldRepositories == null) {
      sections = listOf(ProductItem.Section.All) +
        (categories ?: oldCategories).orEmpty() +
        (repositories ?: oldRepositories).orEmpty()
      updateSection()
    }
  }

  private fun updateSection() {
    if (section !in sections) {
      section = ProductItem.Section.All
    }
    layout?.sectionName?.text = when (val section = section) {
      is ProductItem.Section.All -> getString(R.string.all_applications)
      is ProductItem.Section.Category -> section.name
      is ProductItem.Section.Repository -> section.name
    }
    layout?.sectionIcon?.visibility = if (sections.any { it !is ProductItem.Section.All }) View.VISIBLE else View.GONE
    productFragments.forEach { it.setSection(section) }
    sectionsList?.adapter?.notifyDataSetChanged()
  }

  private fun animateSectionsList() {
    val sectionsList = sectionsList!!
    val value = if (sectionsList.visibility != View.VISIBLE) 0f else
      sectionsList.height.toFloat() / (sectionsList.parent as View).height
    val target = if (showSections) 1f else 0f
    sectionsAnimator?.cancel()
    sectionsAnimator = null

    if (value != target) {
      sectionsAnimator = ValueAnimator.ofFloat(value, target).apply {
        duration = (250 * abs(target - value)).toLong()
        interpolator = if (target >= 1f) AccelerateInterpolator(2f) else DecelerateInterpolator(2f)
        addUpdateListener {
          val newValue = animatedValue as Float
          sectionsList.apply {
            val height = ((parent as View).height * newValue).toInt()
            val visible = height > 0
            if ((visibility == View.VISIBLE) != visible) {
              visibility = if (visible) View.VISIBLE else View.GONE
            }
            if (layoutParams.height != height) {
              layoutParams.height = height
              requestLayout()
            }
          }
          if (target <= 0f && newValue <= 0f || target >= 1f && newValue >= 1f) {
            sectionsAnimator = null
          }
        }
        start()
      }
    }
  }

  fun updateAll() {
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.update_all)
      .setMessage(productsList.joinToString(",") { it.name }) // TODO or maybe use a checklist
      .setPositiveButton(android.R.string.yes) { _: DialogInterface, _: Int ->
        productsList.forEach {
          // TODO startUpdateInstallAction for each (selected) app after accomulating the needed info
        }
      }
      .setNegativeButton(android.R.string.no, null)
      .create()
      .show()
  }

  private val pageChangeCallback = object: ViewPager2.OnPageChangeCallback() {
    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
      val layout = layout!!
      val from = ProductsFragment.Source.values()[position]
      val to = ProductsFragment.Source.values()[if (positionOffset <= 0f) position else position + 1]
      val fromSections = from.sections
      val fromUpdates = from.updates
      val toSections = to.sections
      val toUpdates = to.updates
      val sectionsOffset = if (fromSections != toSections) {
        if (fromSections) 1f - positionOffset else positionOffset
      } else {
        if (fromSections) 1f else 0f
      }
      val updatesOffset = if (fromUpdates != toUpdates) {
        if (fromUpdates) 1f - positionOffset else positionOffset
      } else {
        if (fromUpdates) 1f else 0f
      }
      (layout.tabs.background as TabsBackgroundDrawable)
        .update(position + positionOffset, layout.tabs.childCount)
      assert(layout.sectionLayout.childCount == 1)
      val child = layout.sectionLayout.getChildAt(0)
      val height = child.layoutParams.height
      assert(height > 0)
      val sectionsCurrentHeight = (sectionsOffset * height).roundToInt()
      if (layout.sectionLayout.layoutParams.height != sectionsCurrentHeight) {
        layout.sectionLayout.layoutParams.height = sectionsCurrentHeight
        layout.sectionLayout.requestLayout()
      }
      val updatesCurrentHeight = (updatesOffset * height).roundToInt()
      if (layout.updateAll.layoutParams.height != updatesCurrentHeight) {
        layout.updateAll.layoutParams.height = updatesCurrentHeight
        layout.updateAll.requestLayout()
      }
    }

    override fun onPageSelected(position: Int) {
      val source = ProductsFragment.Source.values()[position]
      updateUpdateNotificationBlocker(source)
      sortOrderMenu!!.first.isVisible = source.order
      syncRepositoriesMenuItem!!.setShowAsActionFlags(if (!source.order ||
        resources.configuration.screenWidthDp >= 400) MenuItem.SHOW_AS_ACTION_ALWAYS else 0)
      setSelectedTab(source)
      if (showSections && !source.sections) {
        showSections = false
      }
    }

    override fun onPageScrollStateChanged(state: Int) {
      val source = ProductsFragment.Source.values()[viewPager!!.currentItem]
      layout!!.sectionChange.isEnabled = state != ViewPager2.SCROLL_STATE_DRAGGING && source.sections
      if (state == ViewPager2.SCROLL_STATE_IDLE) {
        // onPageSelected can be called earlier than fragments created
        updateUpdateNotificationBlocker(source)
      }
    }
  }

  private class TabsBackgroundDrawable(context: Context, private val rtl: Boolean): Drawable() {
    private val height = context.resources.sizeScaled(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = context.getColorFromAttr(android.R.attr.colorAccent).defaultColor
    }

    private var position = 0f
    private var total = 0

    fun update(position: Float, total: Int) {
      this.position = position
      this.total = total
      invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
      if (total > 0) {
        val bounds = bounds
        val width = bounds.width() / total.toFloat()
        val x = width * position
        if (rtl) {
          canvas.drawRect(bounds.right - width - x, (bounds.bottom - height).toFloat(),
            bounds.right - x, bounds.bottom.toFloat(), paint)
        } else {
          canvas.drawRect(bounds.left + x, (bounds.bottom - height).toFloat(),
            bounds.left + x + width, bounds.bottom.toFloat(), paint)
        }
      }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }

  private class SectionsAdapter(private val sections: () -> List<ProductItem.Section>,
    private val onClick: (ProductItem.Section) -> Unit): StableRecyclerAdapter<SectionsAdapter.ViewType,
    RecyclerView.ViewHolder>() {
    enum class ViewType { SECTION }

    private class SectionViewHolder(context: Context): RecyclerView.ViewHolder(TextView(context)) {
      val title: TextView
        get() = itemView as TextView

      init {
        itemView as TextView
        itemView.gravity = Gravity.CENTER_VERTICAL
        itemView.resources.sizeScaled(16).let { itemView.setPadding(it, 0, it, 0) }
        itemView.setTextColor(context.getColorFromAttr(android.R.attr.textColorPrimary))
        itemView.setTextSizeScaled(16)
        itemView.background = context.getDrawableFromAttr(android.R.attr.selectableItemBackground)
        itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
          itemView.resources.sizeScaled(48))
      }
    }

    fun configureDivider(context: Context, position: Int, configuration: DividerItemDecoration.Configuration) {
      val currentSection = sections()[position]
      val nextSection = sections().getOrNull(position + 1)
      when {
        nextSection != null && currentSection.javaClass != nextSection.javaClass -> {
          val padding = context.resources.sizeScaled(16)
          configuration.set(true, false, padding, padding)
        }
        else -> {
          configuration.set(false, false, 0, 0)
        }
      }
    }

    override val viewTypeClass: Class<ViewType>
      get() = ViewType::class.java

    override fun getItemCount(): Int = sections().size
    override fun getItemDescriptor(position: Int): String = sections()[position].toString()
    override fun getItemEnumViewType(position: Int): ViewType = ViewType.SECTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: ViewType): RecyclerView.ViewHolder {
      return SectionViewHolder(parent.context).apply {
        itemView.setOnClickListener { onClick(sections()[adapterPosition]) }
      }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      holder as SectionViewHolder
      val section = sections()[position]
      val previousSection = sections().getOrNull(position - 1)
      val nextSection = sections().getOrNull(position + 1)
      val margin = holder.itemView.resources.sizeScaled(8)
      val layoutParams = holder.itemView.layoutParams as RecyclerView.LayoutParams
      layoutParams.topMargin = if (previousSection == null ||
        section.javaClass != previousSection.javaClass) margin else 0
      layoutParams.bottomMargin = if (nextSection == null ||
        section.javaClass != nextSection.javaClass) margin else 0
      holder.title.text = when (section) {
        is ProductItem.Section.All -> holder.itemView.resources.getString(R.string.all_applications)
        is ProductItem.Section.Category -> section.name
        is ProductItem.Section.Repository -> section.name
      }
    }
  }
}
