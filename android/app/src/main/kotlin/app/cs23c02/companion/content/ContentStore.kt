package app.cs23c02.companion.content

import java.io.File

/** An installed, verified package: the manifest plus a reader for its files. */
class InstalledPackage(val manifest: Manifest, private val readFile: (FileEntry) -> ByteArray) {
    private val byPath = manifest.files.associateBy { it.path }
    private val cache = HashMap<String, Lesson>()

    fun lesson(id: String): Lesson? = cache[id] ?: byPath["lessons/$id.json"]?.let { e ->
        val data = readFile(e)
        PackageVerifier.verifyFile(e, data)  // re-checked on every read: a changed file on disk is never shown
        ContentJson.decodeFromString(Lesson.serializer(), data.decodeToString()).also { cache[id] = it }
    }

    private val items = HashMap<String, Item>()
    fun item(id: String): Item? = items[id] ?: byPath["items/$id.json"]?.let { e ->
        val data = readFile(e)
        PackageVerifier.verifyFile(e, data)
        ContentJson.decodeFromString(Item.serializer(), data.decodeToString()).also { items[id] = it }
    }

    private val exercises = HashMap<String, Exercise>()
    fun exercise(id: String): Exercise? = exercises[id] ?: byPath["exercises/$id.json"]?.let { e ->
        val data = readFile(e)
        PackageVerifier.verifyFile(e, data)
        ContentJson.decodeFromString(Exercise.serializer(), data.decodeToString()).also { exercises[id] = it }
    }

    /** Lesson titles for lists, without parsing whole lessons repeatedly. */
    fun title(id: String): String = lesson(id)?.title ?: id
}

/**
 * Packages on the phone, in app-private storage (not backed up, wiped on sign-out). Layout:
 *   <dir>/files/<sha256>              content-addressed files
 *   <dir>/current                      signature line + manifest bytes of the CURRENT package, written in one atomic rename
 *                                      only after every file is present and verified
 * On every open the manifest signature is checked again, so a package altered on disk is refused.
 */
class ContentStore(private val dir: File, private val publicKeyB64: String, private val appVersion: String) {
    private val files = File(dir, "files")
    private val currentFile = File(dir, "current")

    fun current(): InstalledPackage? {
        if (!currentFile.exists()) return null
        return try {
            val all = currentFile.readBytes()
            val nl = all.indexOf('\n'.code.toByte())
            if (nl < 0) return null
            val m = PackageVerifier.verifyManifest(all.copyOfRange(nl + 1, all.size), all.copyOfRange(0, nl).decodeToString(),
                publicKeyB64, appVersion)
            InstalledPackage(m) { e -> File(files, e.sha256).readBytes() }
        } catch (e: ContentError) {
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    fun currentVersion(): String? = current()?.manifest?.book?.version

    /**
     * Verifies [manifestBytes], fetches every file not already present via [fetch], verifies each one, and only then makes
     * this package current. A failure at any point leaves the previous package untouched.
     */
    suspend fun install(manifestBytes: ByteArray, signatureB64: String, fetch: suspend (FileEntry) -> ByteArray): InstalledPackage {
        val m = PackageVerifier.verifyManifest(manifestBytes, signatureB64, publicKeyB64, appVersion)
        files.mkdirs()
        for (e in m.files) {
            val dest = File(files, e.sha256)
            if (dest.exists() && dest.length() == e.size && PackageVerifier.sha256(dest.readBytes()) == e.sha256) continue
            val data = fetch(e)
            PackageVerifier.verifyFile(e, data)
            atomicWrite(dest, data)
        }
        atomicWrite(currentFile, (signatureB64.trim() + "\n").toByteArray() + manifestBytes)
        val keep = m.files.map { it.sha256 }.toSet()
        files.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        return current() ?: throw ContentError("The lessons could not be saved.")
    }

    fun clear() { dir.deleteRecursively() }

    private fun atomicWrite(dest: File, data: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw ContentError("The lessons could not be saved.") }
    }
}
