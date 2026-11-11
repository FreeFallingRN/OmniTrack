import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

val supabaseUrl: String = (localProperties.getProperty("SUPABASE_URL")
    ?: System.getenv("SUPABASE_URL") ?: "").trim()
val supabaseAnonKey: String = (localProperties.getProperty("SUPABASE_ANON_KEY")
    ?: System.getenv("SUPABASE_ANON_KEY") ?: "").trim()

val keystoreFile = rootProject.file("omnitrack-release.jks")
val keystorePassword: String = (localProperties.getProperty("KEYSTORE_PASSWORD")
    ?: System.getenv("KEYSTORE_PASSWORD") ?: "").trim()
val keyAlias: String = (localProperties.getProperty("KEY_ALIAS")
    ?: (localProperties.getProperty("KEYSTORE_ALIAS")
        ?: (System.getenv("KEY_ALIAS") ?: (System.getenv("KEYSTORE_ALIAS") ?: "")))).trim()
val keyPassword: String = (localProperties.getProperty("KEY_PASSWORD")
    ?: System.getenv("KEY_PASSWORD") ?: "").trim()

val isKeystoreFileValid = keystoreFile.exists() && keystoreFile.length() > 0L
val hasValidReleaseSigning = isKeystoreFileValid
        && keystorePassword.isNotEmpty()
        && keyAlias.isNotEmpty()
        && keyPassword.isNotEmpty()

extensions.configure<ApplicationExtension> {
    namespace = "com.omnitrack.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omnitrack.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        create("release") {
            if (hasValidReleaseSigning) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    this.keyAlias = "androiddebugkey"
                    this.keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false

            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            } else {
                val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
                if (isReleaseTask) {
                    val missing = mutableListOf<String>()
                    if (!keystoreFile.exists()) missing.add("Keystore file 'omnitrack-release.jks' missing at ${keystoreFile.absolutePath}")
                    else if (keystoreFile.length() == 0L) missing.add("Keystore file 'omnitrack-release.jks' exists but is 0 bytes")
                    if (keystorePassword.isEmpty()) missing.add("KEYSTORE_PASSWORD environment variable is empty")
                    if (keyAlias.isEmpty()) missing.add("KEY_ALIAS / KEYSTORE_ALIAS environment variable is empty")
                    if (keyPassword.isEmpty()) missing.add("KEY_PASSWORD environment variable is empty")

                    throw GradleException("Release build failed because signing credentials are incomplete:\n  - " + missing.joinToString("\n  - "))
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.graphics)
    implementation(libs.androidx.compose.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
}