package fr.acinq.phoenixd.conf

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.testing.test
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PasswordFileTestsCommon {

    private class TestCommand : CliktCommand() {
        val password by option("--password-file").passwordFile()
        override fun run() {}
    }

    private fun tempFile(content: String? = null): Path {
        val path = Path(SystemTemporaryDirectory, "phoenixd-password-${randomBytes32().toHex().take(16)}")
        if (content != null) {
            SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
        }
        return path
    }

    @Test
    fun `read password from file`() {
        val path = tempFile("s3cr3t")
        val cmd = TestCommand()
        val result = cmd.test("--password-file=$path")
        assertEquals(0, result.statusCode, result.output)
        assertEquals("s3cr3t", cmd.password)
    }

    @Test
    fun `ignore surrounding whitespace`() {
        val path = tempFile("  s3cr3t\r\n")
        val cmd = TestCommand()
        val result = cmd.test("--password-file=$path")
        assertEquals(0, result.statusCode, result.output)
        assertEquals("s3cr3t", cmd.password)
    }

    @Test
    fun `reject missing file`() {
        val path = tempFile()
        val result = TestCommand().test("--password-file=$path")
        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "does not exist")
    }

    @Test
    fun `reject empty file`() {
        val path = tempFile("\n")
        val result = TestCommand().test("--password-file=$path")
        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "is empty")
    }

    @Test
    fun `option not provided`() {
        val cmd = TestCommand()
        val result = cmd.test("")
        assertEquals(0, result.statusCode, result.output)
        assertEquals(null, cmd.password)
    }
}
