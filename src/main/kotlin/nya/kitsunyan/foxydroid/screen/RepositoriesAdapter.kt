package nya.kitsunyan.foxydroid.screen

import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.widget.CursorRecyclerAdapter

class RepositoriesAdapter(private val onClick: (Repository) -> Unit,
  private val onSwitch: (repository: Repository, isEnabled: Boolean) -> Boolean):
  CursorRecyclerAdapter<RepositoriesAdapter.ViewType, RecyclerView.ViewHolder>() {
  enum class ViewType { REPOSITORY }

  private class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    val name = itemView.findViewById<TextView>(R.id.name)!!
    val enabled = itemView.findViewById<Switch>(R.id.enabled)!!

    var listenSwitch = true
  }

  override val viewTypeClass: Class<ViewType>
    get() = ViewType::class.java

  override fun getItemEnumViewType(position: Int): ViewType {
    return ViewType.REPOSITORY
  }

  private fun getRepository(position: Int): Repository {
    return Database.RepositoryAdapter.transform(moveTo(position))
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: ViewType): RecyclerView.ViewHolder {
    return ViewHolder(parent.inflate(R.layout.repository_item)).apply {
      itemView.setOnClickListener { onClick(getRepository(adapterPosition)) }
      enabled.setOnCheckedChangeListener { _, isChecked ->
        if (listenSwitch) {
          if (!onSwitch(getRepository(adapterPosition), isChecked)) {
            listenSwitch = false
            enabled.isChecked = !isChecked
            listenSwitch = true
          }
        }
      }
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    holder as ViewHolder
    val repository = getRepository(position)
    val lastListenSwitch = holder.listenSwitch
    holder.listenSwitch = false
    holder.enabled.isChecked = repository.enabled
    holder.listenSwitch = lastListenSwitch
    holder.name.text = repository.name
  }
}
