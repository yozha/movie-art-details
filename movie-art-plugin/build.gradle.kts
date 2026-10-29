import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Local, git-ignored settings (keystore.properties in the repository root):
// storeFile, storePassword, keyAlias, keyPassword: release signing for this fork.
// devTmdbKey: developer TMDB key, compiled into debug builds only.
val local = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.yozha.movieartdetails"
    compileSdk = libs.versions.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // namespace == applicationId: Projectivy resolves the relative ".settings.SettingsActivity" against applicationId.
        applicationId = "io.github.yozha.movieartdetails"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["pluginUuid"] = "db54f357-de39-4d1d-90e2-53a590c89a76"
        buildConfigField("String", "DEV_TMDB_KEY", "\"\"")
    }

    signingConfigs {
        local.getProperty("storeFile")?.let { path ->
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = local.getProperty("storePassword")
                keyAlias = local.getProperty("keyAlias")
                keyPassword = local.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_TMDB_KEY", "\"${local.getProperty("devTmdbKey", "")}\"")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":api"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.leanback)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
