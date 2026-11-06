package app.cs23c02.companion.content

import app.cs23c02.companion.SampleContent
import com.google.crypto.tink.subtle.Ed25519Sign
import java.io.File
import java.util.Base64

/** The real debug SAMPLE package (built and signed by android/scripts/make-sample-content.py). */
object Sample {
    val dir = File("src/debug/assets/sample-content")
    val manifest: ByteArray get() = File(dir, "manifest.json").readBytes()
    val signature: String get() = File(dir, "manifest.sig").readText().trim()
    const val KEY = SampleContent.PUBLIC_KEY
    fun file(path: String) = File(dir, path).readBytes()
}

/** Signs arbitrary manifests in tests, to check rules beyond the signature itself. */
class TestSigner {
    private val pair = Ed25519Sign.KeyPair.newKeyPair()
    val publicKey: String = Base64.getEncoder().encodeToString(pair.publicKey)
    fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(Ed25519Sign(pair.privateKey).sign(bytes))
}
