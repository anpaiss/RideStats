import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Release identity comes from CI: the tag gives the version name, the run
// number the version code. A local build keeps fixed values.
val releaseVersion: String =
    System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1"
val buildNumber: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
val appVersionCode: Int = if (buildNumber > 0) 100 + buildNumber else 100

// The keystore travels as a base64 secret and is decoded next to the module,
// outside build/ so `clean` cannot remove it mid-build. *.keystore is ignored.
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }

// Google OAuth client for the Karoo ("TVs and limited input devices"), as
// downloaded from the Cloud console into google-client.json (git-ignored); CI
// passes the same file as the GOOGLE_CLIENT_JSON secret. For installed apps
// Google does not treat the client secret as confidential, it ships in the APK.
val googleClient: Map<*, *> = run {
    val text = System.getenv("GOOGLE_CLIENT_JSON")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("google-client.json").takeIf { it.exists() }?.readText()
        ?: error("google-client.json missing: download the Karoo OAuth client from the Google Cloud console")
    @Suppress("UNCHECKED_CAST")
    (groovy.json.JsonSlurper().parseText(text) as Map<String, Any>)["installed"] as Map<*, *>
}

android {
    namespace  = "com.anpaiss.ridestats"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.ridestats"
        minSdk        = 26
        // 28 like BetterETA: READ_EXTERNAL_STORAGE still opens /sdcard/FitFiles
        targetSdk     = 28
        versionCode   = appVersionCode
        versionName   = releaseVersion
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${googleClient["client_id"]}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${googleClient["client_secret"]}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    if (keystoreBase64 != null) {
        signingConfigs {
            create("release") {
                val ksFile = layout.projectDirectory.file(".release.keystore").asFile
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile     = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // With the release key when the environment has it, as in BetterETA: the backup
            // provider of the other extensions asks for a signature permission. Stays debuggable.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")   // lifecycleScope in MainActivity

    testImplementation(libs.junit)
    // Real org.json for JVM tests: the android.jar one is a stub
    testImplementation("org.json:json:20240303")
}
