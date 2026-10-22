package social.tbone.webxdc

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in compatibility check against real `.xdc` packages (webxdc.org store and
 * Blossom/Nostr), mirroring BlossomLiveTest: skipped unless the fixture
 * directory is provided.
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*XdcRealFilesTest*' -DxdcFixtures=/path/to/xdcs
 *
 * It exercises the exact code path used for imports/downloads: archive
 * validation, manifest naming, icon discovery and the `webxdc.js` injection the
 * WebView performs before handing the page to the mini app.
 */
class XdcRealFilesTest {

    private val dir = File(System.getProperty("xdcFixtures").orEmpty())

    @Test
    fun parsesEveryRealPackage() {
        assumeTrue("set -DxdcFixtures=<dir> to run", dir.isDirectory)
        val files = dir.listFiles { file -> file.isFile && file.length() > 0 }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no .xdc fixtures found in $dir", files.isNotEmpty())

        for (file in files) {
            val pkg = XdcArchive.read(file.readBytes(), file.name)
            assertTrue("${file.name}: index.html missing", pkg.files.containsKey("index.html"))
            assertTrue("${file.name}: empty name", pkg.name.isNotBlank())
            assertTrue("${file.name}: no files", pkg.files.isNotEmpty())

            val html = String(pkg.files["index.html"]!!, Charsets.UTF_8)
            val served = XdcHtml.injectBridge(html)
            assertTrue("${file.name}: bridge not injected", served.contains("webxdc.js"))

            val icon = pkg.iconPath?.let { pkg.files[it] }?.size ?: 0
            println(
                "OK ${file.name} -> \"${pkg.name}\" " +
                    "files=${pkg.files.size} icon=$icon bytes archive=${pkg.sizeBytes}",
            )
        }
    }
}
