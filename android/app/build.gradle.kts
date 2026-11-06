import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---- Python runner assets (ADR 0013 slice 3) ------------------------------------------------------------------------------
// Pyodide is downloaded from npm at build time (never committed) and checked against a pinned SHA-256; any mismatch fails the
// build. 0.27.x = CPython 3.12, the same Python the book build pins (A5). Our recorder/explainer are copied from runtime/.
abstract class RunnerAssetsTask : DefaultTask() {
    @get:Input abstract val pyodideVersion: Property<String>
    @get:Input abstract val pyodideSha256: Property<String>
    @get:InputDirectory abstract val webDir: DirectoryProperty
    @get:InputFiles abstract val pythonFiles: ConfigurableFileCollection
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @TaskAction fun build() {
        val v = pyodideVersion.get()
        val tgz = cacheDir.get().file("pyodide-$v.tgz").asFile
        if (!tgz.exists() || sha256(tgz) != pyodideSha256.get()) {
            tgz.parentFile.mkdirs()
            val url = System.getenv("PYODIDE_URL") ?: "https://registry.npmjs.org/pyodide/-/pyodide-$v.tgz"
            logger.lifecycle("Downloading Pyodide $v from $url")
            URI(url).toURL().openStream().use { i -> tgz.outputStream().use { o -> i.copyTo(o) } }
            val got = sha256(tgz)
            if (got != pyodideSha256.get()) { tgz.delete(); throw GradleException("Pyodide $v checksum mismatch: $got") }
        }
        val keep = setOf("pyodide.js", "pyodide.asm.js", "pyodide.asm.wasm", "python_stdlib.zip", "pyodide-lock.json")
        val out = outputDir.get().dir("runner").asFile
        fs.sync {
            into(out)
            from(webDir)
            from(pythonFiles) { into("py") }
            from(archives.tarTree(archives.gzip(tgz))) {
                include { it.isDirectory || it.name in keep }
                eachFile { path = "pyodide/$name" }
                includeEmptyDirs = false
            }
        }
    }
}

val runnerAssets = tasks.register<RunnerAssetsTask>("runnerAssets") {
    pyodideVersion.set("0.27.8")
    pyodideSha256.set("2595c4b6800f8fc21d1e9b5222b3b3458b479b5f55fd241fee9ec6be0ad7edae")
    webDir.set(layout.projectDirectory.dir("src/main/runner"))
    pythonFiles.from(rootProject.file("../runtime/stepper/stepper.py"), rootProject.file("../runtime/tutor/explain.py"),
        rootProject.file("../runtime/tutor/live.py"), rootProject.file("../runtime/tutor/constructs.sample.json"))
    cacheDir.set(layout.buildDirectory.dir("downloads"))
    outputDir.set(layout.buildDirectory.dir("generated/runnerAssets"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(runnerAssets, RunnerAssetsTask::outputDir) }
}

// ---- Build-time configuration (ADR 0007 amendment) ------------------------------------------------------------------------
// Values come from the environment (CI: repository variables) or from android/.env (local; git-ignored). Android cannot read a
// .env at runtime: the value is baked into the app, so NOTHING SECRET may go here (anything in an APK can be extracted).
fun dotenv(): Map<String, String> {
    val f = rootProject.file(".env")
    if (!f.exists()) return emptyMap()
    return f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().trim('"') }
}
fun cfg(name: String): String? = (System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotenv()[name]?.takeIf { it.isNotBlank() })

val placeholderBaseUrl = "https://api.invalid"   // debug-only fallback; release builds refuse it
val apiBaseUrl: String = cfg("API_BASE_URL") ?: placeholderBaseUrl
val apiCertPins: String = cfg("API_CERT_PINS") ?: ""   // comma-separated sha256/<base64> SPKI pins, used by OkHttp's CertificatePinner
val pinList = apiCertPins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
val pinShape = Regex("^sha256/[A-Za-z0-9+/]{43}=$")
pinList.firstOrNull { !pinShape.matches(it) }?.let { throw GradleException("API_CERT_PINS entry is not a sha256/<base64> SPKI pin: '$it'") }
if (!Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(apiBaseUrl)) {
    throw GradleException("API_BASE_URL must look like https://host[:port] with no path or trailing slash (got '$apiBaseUrl'). HTTP is never allowed.")
}
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (releasing && apiBaseUrl == placeholderBaseUrl) {
        throw GradleException("A release build needs a real API_BASE_URL (set it in android/.env or the environment).")
    }
}

android {
    namespace = "app.cs23c02.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cs23c02.companion"
        minSdk = 26          // ADR 0007: covers far more older phones than Zigora's minSdk 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_CERT_PINS", "\"$apiCertPins\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Screenshot rendering (Robolectric + Roborazzi) is opt-in: gradle ... -Pscreenshots. Images land in app/build/screens.
        unitTests.all {
            if (project.hasProperty("screenshots")) {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("screens.out", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
                // Optional: where Robolectric downloads its Android runtime (e.g. a Maven Central mirror when Central throttles).
                System.getenv("ROBOLECTRIC_REPO")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
            } else {
                it.exclude("**/*ScreenshotTest*")
            }
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
}
