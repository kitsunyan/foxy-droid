package nya.kitsunyan.foxydroid.entity

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import nya.kitsunyan.foxydroid.utility.extension.json.*

data class ProductItem(val repositoryId: Long, val packageName: String,
  val name: String, val summary: String, val icon: String, val version: String, val installedVersion: String,
  val compatible: Boolean, val canUpdate: Boolean) {
  fun serialize(generator: JsonGenerator) {
    generator.writeNumberField("serialVersion", 1)
    generator.writeStringField("icon", icon)
    generator.writeStringField("version", version)
  }

  companion object {
    fun deserialize(repositoryId: Long, packageName: String, name: String, summary: String,
      installedVersion: String, compatible: Boolean, canUpdate: Boolean, parser: JsonParser): ProductItem {
      var icon = ""
      var version = ""
      parser.forEachKey {
        when {
          it.string("icon") -> icon = valueAsString
          it.string("version") -> version = valueAsString
          else -> skipChildren()
        }
      }
      return ProductItem(repositoryId, packageName, name, summary, icon,
        version, installedVersion, compatible, canUpdate)
    }
  }
}
