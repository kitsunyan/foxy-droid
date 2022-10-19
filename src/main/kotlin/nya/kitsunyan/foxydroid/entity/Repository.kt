package nya.kitsunyan.foxydroid.entity

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import nya.kitsunyan.foxydroid.utility.extension.json.*
import java.net.URL

data class Repository(val id: Long, val address: String, val mirrors: List<String>,
  val name: String, val description: String, val version: Int, val enabled: Boolean,
  val fingerprint: String, val lastModified: String, val entityTag: String,
  val updated: Long, val timestamp: Long, val authentication: String) {
  fun edit(address: String, fingerprint: String, authentication: String): Repository {
    val addressChanged = this.address != address
    val fingerprintChanged = this.fingerprint != fingerprint
    val changed = addressChanged || fingerprintChanged
    return copy(address = address, fingerprint = fingerprint, lastModified = if (changed) "" else lastModified,
      entityTag = if (changed) "" else entityTag, authentication = authentication)
  }

  fun update(mirrors: List<String>, name: String, description: String, version: Int,
    lastModified: String, entityTag: String, timestamp: Long): Repository {
    return copy(mirrors = mirrors, name = name, description = description,
      version = if (version >= 0) version else this.version, lastModified = lastModified,
      entityTag = entityTag, updated = System.currentTimeMillis(), timestamp = timestamp)
  }

  fun enable(enabled: Boolean): Repository {
    return copy(enabled = enabled, lastModified = "", entityTag = "")
  }

  fun serialize(generator: JsonGenerator) {
    generator.writeNumberField("serialVersion", 1)
    generator.writeStringField("address", address)
    generator.writeArray("mirrors") { mirrors.forEach { writeString(it) } }
    generator.writeStringField("name", name)
    generator.writeStringField("description", description)
    generator.writeNumberField("version", version)
    generator.writeBooleanField("enabled", enabled)
    generator.writeStringField("fingerprint", fingerprint)
    generator.writeStringField("lastModified", lastModified)
    generator.writeStringField("entityTag", entityTag)
    generator.writeNumberField("updated", updated)
    generator.writeNumberField("timestamp", timestamp)
    generator.writeStringField("authentication", authentication)
  }

  companion object {
    const val REV_ROBOTICS_MAIN_REPO_ADDRESS = "https://software-metadata.revrobotics.com/fdroid-repo"
    const val REV_ROBOTICS_STAGING_REPO_ADDRESS = "https://staging--rev-robotics-software-metadata.netlify.app/fdroid-repo"

    val REV_ROBOTICS_STAGING_REPO_DEFAULT =  defaultRepository(REV_ROBOTICS_STAGING_REPO_ADDRESS, "REV Robotics staging repo",
          "The staging repository for apps and operating system updates built or distributed by REV Robotics",
          20000, false, "2803D3952C3C0DDF3AD20F05632B79CC0A4001D928CBABFF521A606BF557B37F", "")

    fun deserialize(id: Long, parser: JsonParser): Repository {
      var address = ""
      var mirrors = emptyList<String>()
      var name = ""
      var description = ""
      var version = 0
      var enabled = false
      var fingerprint = ""
      var lastModified = ""
      var entityTag = ""
      var updated = 0L
      var timestamp = 0L
      var authentication = ""
      parser.forEachKey {
        when {
          it.string("address") -> address = valueAsString
          it.array("mirrors") -> mirrors = collectNotNullStrings()
          it.string("name") -> name = valueAsString
          it.string("description") -> description = valueAsString
          it.number("version") -> version = valueAsInt
          it.boolean("enabled") -> enabled = valueAsBoolean
          it.string("fingerprint") -> fingerprint = valueAsString
          it.string("lastModified") -> lastModified = valueAsString
          it.string("entityTag") -> entityTag = valueAsString
          it.number("updated") -> updated = valueAsLong
          it.number("timestamp") -> timestamp = valueAsLong
          it.string("authentication") -> authentication = valueAsString
          else -> skipChildren()
        }
      }
      return Repository(id, address, mirrors, name, description, version, enabled, fingerprint,
        lastModified, entityTag, updated, timestamp, authentication)
    }

    fun newRepository(address: String, fingerprint: String, authentication: String): Repository {
      val name = try {
        URL(address).let { "${it.host}${it.path}" }
      } catch (e: Exception) {
        address
      }
      return defaultRepository(address, name, "", 0, true, fingerprint, authentication)
    }

    private fun defaultRepository(address: String, name: String, description: String,
      version: Int, enabled: Boolean, fingerprint: String, authentication: String): Repository {
      return Repository(-1, address, emptyList(), name, description, version, enabled,
        fingerprint, "", "", 0L, 0L, authentication)
    }

    // TODO(Noah): When the user enables the app store, enable the F-Droid repository.
    //             When the user disables the app store, disable all non-REV repositories.
    // REV Robotics repo added and F-Droid repository disabled on 2021-05-03
    val defaultRepositories = listOf(run {
      defaultRepository(REV_ROBOTICS_MAIN_REPO_ADDRESS, "REV Robotics",
        "The official update repository for apps and operating system updates built or distributed by REV Robotics",
        20000, true, "2803D3952C3C0DDF3AD20F05632B79CC0A4001D928CBABFF521A606BF557B37F", "")
    }, run {
      defaultRepository("https://f-droid.org/repo", "F-Droid", "The official F-Droid Free Software repository. " +
        "Everything in this repository is always built from the source code.",
        21, false, "43238D512C1E5EB2D6569F4A3AFBF5523418B82E0A3ED1552770ABB9A9C9CCAB", "")
    }, run {
      defaultRepository("https://f-droid.org/archive", "F-Droid Archive", "The archive of the official F-Droid Free " +
        "Software repository. Apps here are old and can contain known vulnerabilities and security issues!",
        21, false, "43238D512C1E5EB2D6569F4A3AFBF5523418B82E0A3ED1552770ABB9A9C9CCAB", "")
    }, run {
      defaultRepository("https://guardianproject.info/fdroid/repo", "Guardian Project Official Releases", "The " +
        "official repository of The Guardian Project apps for use with the F-Droid client. Applications in this " +
        "repository are official binaries built by the original application developers and signed by the same key as " +
        "the APKs that are released in the Google Play Store.",
        21, false, "B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135", "")
    }, run {
      defaultRepository("https://guardianproject.info/fdroid/archive", "Guardian Project Archive", "The official " +
        "repository of The Guardian Project apps for use with the F-Droid client. This contains older versions of " +
        "applications from the main repository.", 21, false,
        "B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135", "")
    })
  }
}
