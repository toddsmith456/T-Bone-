package social.tbone.webxdc

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in: unpacks every `.xdc` in `-DxdcFixtures=<dir>` into
 * `-DxdcHarnessOut=<dir>`, using the **production** code paths
 * ([XdcArchive.read], [XdcHtml.injectBridge], [WebxdcShim.build]).
 *
 * The exported tree is what the Android WebView would serve, so it can be
 * loaded in a desktop browser for compatibility checks and screenshots:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*XdcHarnessExportTest*' \
 *         -DxdcFixtures=/path/to/xdcs -DxdcHarnessOut=/path/to/export
 *
 * Like [XdcRealFilesTest] it is skipped unless both properties are provided.
 */
class XdcHarnessExportTest {

    private val fixtures = File(System.getProperty("xdcFixtures").orEmpty())
    private val out = File(System.getProperty("xdcHarnessOut").orEmpty())

    @Test
    fun exportsServedTree() {
        assumeTrue("set -DxdcFixtures and -DxdcHarnessOut to run", fixtures.isDirectory && out.isDirectory)

        val apps = fixtures.listFiles { f -> f.isFile && f.length() > 0 }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no .xdc fixtures in $fixtures", apps.isNotEmpty())

        val index = StringBuilder("[\n")
        for ((i, file) in apps.withIndex()) {
            val pkg = XdcArchive.read(file.readBytes(), file.name)
            val dir = File(out, pkg.id)
            pkg.files.forEach { (path, bytes) ->
                val target = File(dir, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            File(dir, "webxdc.js").writeText(WebxdcShim.build("npub1zx2tlzz35z44lcs47g3mgjts769wmxdjyzugc7jpfha7xrm72puq09ftjq", "tbone"))
            // Same transformation the WebViewClient applies to HTML responses.
            val html = String(pkg.files["index.html"]!!, Charsets.UTF_8)
            File(dir, "index.html").writeText(XdcHtml.injectBridge(html))

            index.append("  {\"id\": \"").append(pkg.id)
                .append("\", \"title\": ").append(quote(pkg.name))
                .append(", \"source\": ").append(quote(file.name))
                .append(", \"bytes\": ").append(pkg.sizeBytes)
                .append("}").append(if (i == apps.lastIndex) "\n" else ",\n")

            println("exported ${pkg.name} -> $dir (files=${pkg.files.size})")
        }
        index.append("]\n")
        File(out, "apps.json").writeText(index.toString())
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
