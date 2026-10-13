package fr.acinq.phoenixd.conf

import fr.acinq.phoenixd.setPermissions
import fr.acinq.phoenixd.setPrivateUmask
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Make sure that files in the datadir are only accessible by the current user. Must be called before anything is created
 * in the datadir.
 *
 * New files are created with a private umask (on the jvm, the umask is set in the start script instead, see build.gradle.kts).
 */
fun restrictFilePermissions(datadir: Path) {
    setPrivateUmask()
    if (!SystemFileSystem.exists(datadir)) return
    setPermissions(datadir, "700".toInt(8))
    // Fix permissions for pre-existing installs so they end up like fresh ones:
    // 0700 for the datadir and its sub-directories (only exports/), 0600 for files.
    runCatching { SystemFileSystem.list(datadir) }.getOrDefault(emptyList()).forEach {
        val isDirectory = SystemFileSystem.metadataOrNull(it)?.isDirectory == true
        setPermissions(it, if (isDirectory) "700".toInt(8) else "600".toInt(8))
    }
}
