package fr.acinq.phoenixd.conf

import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Converts the option value (a file path) to the password contained in that file.
 *
 * This allows secrets to be provided without exposing them in process arguments or in `phoenix.conf`
 * (e.g. Docker secrets mounted in `/run/secrets`). Leading and trailing whitespace, such as a trailing
 * newline, is ignored.
 */
fun RawOption.passwordFile(): NullableOption<String, String> = convert("FILE") { value ->
    val path = Path(value)
    if (!SystemFileSystem.exists(path)) fail("file $path does not exist")
    val password = try {
        SystemFileSystem.source(path).buffered().use { it.readString() }.trim()
    } catch (e: IOException) {
        fail("cannot read file $path: ${e.message}")
    }
    if (password.isEmpty()) fail("file $path is empty")
    password
}
