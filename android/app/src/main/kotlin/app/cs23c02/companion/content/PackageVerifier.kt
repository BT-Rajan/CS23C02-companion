package app.cs23c02.companion.content

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

class ContentError(message: String) : Exception(message)

/**
 * Nothing from a package is trusted until (1) the manifest's Ed25519 signature verifies with the public key built into the
 * app, (2) it is our format and the app is new enough, (3) every listed path has the expected shape, and (4) each file's
 * size and SHA-256 match the signed manifest. Mirrors backend/app/content/importer.py (attack C3: tampered content).
 */
object PackageVerifier {
    const val FORMAT = "cs23c02-package/0"
    private val PATH = Regex("^(lessons|items|exercises|assessments)/[A-Z]{3}-\\d{4,6}\\.json$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun verifyManifest(bytes: ByteArray, signatureB64: String, publicKeyB64: String, appVersion: String): Manifest {
        if (publicKeyB64.isBlank()) throw ContentError("This build has no content signing key.")
        try {
            val key = Base64.getDecoder().decode(publicKeyB64)
            Ed25519Verify(key).verify(Base64.getDecoder().decode(signatureB64.trim()), bytes)
        } catch (e: GeneralSecurityException) {
            throw ContentError("The lessons could not be verified.")
        } catch (e: IllegalArgumentException) {
            throw ContentError("The lessons could not be verified.")
        }
        val m = try { ContentJson.decodeFromString(Manifest.serializer(), bytes.decodeToString()) } catch (e: Exception) {
            throw ContentError("The lesson package is damaged.")
        }
        if (m.format != FORMAT) throw ContentError("These lessons need a newer version of the app.")
        if (compareVersions(appVersion, m.book.minAppVersion) < 0) throw ContentError("These lessons need a newer version of the app.")
        val seen = HashSet<String>()
        for (f in m.files) {
            if (!PATH.matches(f.path) || !SHA.matches(f.sha256) || f.size < 0 || !seen.add(f.path)) {
                throw ContentError("The lesson package is damaged.")
            }
        }
        return m
    }

    fun verifyFile(entry: FileEntry, data: ByteArray) {
        if (data.size.toLong() != entry.size || sha256(data) != entry.sha256) throw ContentError("A lesson file is damaged.")
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** "0.10.0" > "0.9.1"; missing parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
