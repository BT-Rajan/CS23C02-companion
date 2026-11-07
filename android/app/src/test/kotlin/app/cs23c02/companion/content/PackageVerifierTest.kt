package app.cs23c02.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageVerifierTest {
    private fun refused(block: () -> Unit): ContentError {
        try { block() } catch (e: ContentError) { return e }
        throw AssertionError("should be refused")
    }

    @Test fun genuineSamplePackageVerifies() {
        val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
        assertEquals("cs23c02-package/0", m.format)
        assertEquals(listOf("LSN-9001", "LSN-9002", "LSN-9003", "LSN-9004", "LSN-9005"), m.nav.lessonIds())
        m.files.forEach { PackageVerifier.verifyFile(it, Sample.file(it.path)) }
    }

    @Test fun oneChangedByteBreaksTheSignature() {
        val bytes = Sample.manifest.copyOf()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        refused { PackageVerifier.verifyManifest(bytes, Sample.signature, Sample.KEY, "0.0.1") }
    }

    @Test fun anotherKeyIsRefused() {
        refused { PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, TestSigner().publicKey, "0.0.1") }
    }

    @Test fun garbageSignatureOrMissingKeyIsRefused() {
        refused { PackageVerifier.verifyManifest(Sample.manifest, "not base64 !!", Sample.KEY, "0.0.1") }
        refused { PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, "", "0.0.1") }
    }

    private fun manifest(files: String = """[{"path":"lessons/LSN-0001.json","sha256":"${"a".repeat(64)}","size":1}]""",
                         format: String = "cs23c02-package/0", minApp: String = "0.0.1") =
        """{"format":"$format","book":{"id":"BOK-0001","title":"t","edition":"e","version":"1","min_app_version":"$minApp","python":"3.12"},
           "nav":{"units":[]},"files":$files}""".toByteArray()

    @Test fun signedButWrongFormatOrTooNewIsRefused() {
        val s = TestSigner()
        val bad = manifest(format = "something-else/9")
        assertTrue(refused { PackageVerifier.verifyManifest(bad, s.sign(bad), s.publicKey, "1.0.0") }.message!!.contains("newer version"))
        val future = manifest(minApp = "9.0.0")
        assertTrue(refused { PackageVerifier.verifyManifest(future, s.sign(future), s.publicKey, "1.0.0") }.message!!.contains("newer version"))
        val ok = manifest()
        assertEquals("e", PackageVerifier.verifyManifest(ok, s.sign(ok), s.publicKey, "1.0.0").book.edition)
    }

    @Test fun signedButUnsafePathsAreRefused() {
        val s = TestSigner()
        for (p in listOf("../../etc/passwd", "lessons/../x.json", "lessons/lsn-1.json", "code/LSN-0001.json")) {
            val m = manifest("""[{"path":"$p","sha256":"${"a".repeat(64)}","size":1}]""")
            refused { PackageVerifier.verifyManifest(m, s.sign(m), s.publicKey, "1.0.0") }
        }
        val dup = manifest("""[{"path":"lessons/LSN-0001.json","sha256":"${"a".repeat(64)}","size":1},{"path":"lessons/LSN-0001.json","sha256":"${"b".repeat(64)}","size":1}]""")
        refused { PackageVerifier.verifyManifest(dup, s.sign(dup), s.publicKey, "1.0.0") }
    }

    @Test fun fileMustMatchItsHashAndSize() {
        val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
        val e = m.files.first()
        val data = Sample.file(e.path)
        refused { PackageVerifier.verifyFile(e, data + byteArrayOf(32)) }
        val changed = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        refused { PackageVerifier.verifyFile(e, changed) }
    }

    @Test fun versionCompare() {
        assertTrue(PackageVerifier.compareVersions("0.10.0", "0.9.1") > 0)
        assertEquals(0, PackageVerifier.compareVersions("1.0", "1.0.0"))
        assertTrue(PackageVerifier.compareVersions("0.0.1", "0.1.0") < 0)
    }
}
