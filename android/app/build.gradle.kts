plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
val apiCertPins: String = cfg("API_CERT_PINS") ?: ""   // comma-separated sha256/<base64> SPKI pins (used by the API client later)
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
    testImplementation("junit:junit:4.13.2")
}
