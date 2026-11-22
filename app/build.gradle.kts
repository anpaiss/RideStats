import java.util.Base64
import java.util.Properties

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
// Local builds: the shared key from local.properties or ../BigFields/local.properties (as RouteProfile)
val localSigning: Properties? = listOf(rootProject.file("local.properties"), rootProject.file("../BigFields/local.properties"))
    .filter { it.exists() }
    .map { f -> Properties().apply { f.inputStream().use { load(it) } } }
    .firstOrNull { it.containsKey("signing.storeFile") }

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
        //noinspection ExpiredTargetSdkVersion
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
    } else if (localSigning != null) {
        signingConfigs {
            create("release") {
                storeFile     = file(localSigning["signing.storeFile"] as String)
                storePassword = localSigning["signing.storePassword"] as String
                keyAlias      = localSigning["signing.keyAlias"] as String
                keyPassword   = localSigning["signing.keyPassword"] as String
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

// Karoo extension manifest: the JSON the Karoo fetches from MANIFEST_URL (see
// AndroidManifest.xml). Published next to the APK with every release; the APK
// is uploaded under the fixed name RideStats.apk so releases/latest/download
// works. Schema: io.hammerhead.karooext.models.KarooAppManifest.
val generateManifest by tasks.registering {
    val out  = layout.projectDirectory.file("manifest.json")
    val repo = "https://github.com/anpaiss/RideStats"
    val version = releaseVersion
    val code = appVersionCode
    // What's new, shown by the Karoo's extension library: the env wins, else app/release-notes.txt
    val notes = System.getenv("RELEASE_NOTES")?.takeIf { it.isNotBlank() }
        ?: layout.projectDirectory.file("release-notes.txt").asFile.takeIf { it.exists() }?.readText()?.trim()
        ?: "See the GitHub release."
    inputs.property("version", version)
    inputs.property("code", code)
    inputs.property("notes", notes)
    outputs.file(out)
    doLast {
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        out.asFile.writeText(
            """
            {
              "label": "RideStats & Backups",
              "packageName": "com.anpaiss.ridestats",
              "iconUrl": ${q("https://raw.githubusercontent.com/anpaiss/RideStats/master/docs/icon.png")},
              "latestApkUrl": ${q("$repo/releases/latest/download/RideStats.apk")},
              "latestVersion": ${q(version)},
              "latestVersionCode": $code,
              "developer": "Andrea Paissan",
              "description": ${q("Every ride goes to your own Google Drive when it ends, and the RideStats dashboard on your phone or PC shows your kilometres, climbing and time by week, month and year. It can also back up what our other extensions have learned, to restore it after a reset of the Karoo.")},
              "releaseNotes": ${q(notes)},
              "screenshotUrls": [
                ${q("https://raw.githubusercontent.com/anpaiss/RideStats/master/docs/dashboard.png")},
                ${q("https://raw.githubusercontent.com/anpaiss/RideStats/master/docs/app.png")}
              ],
              "tags": ["utility"]
            }
            """.trimIndent()
        )
    }
}
tasks.named("preBuild") { dependsOn(generateManifest) }

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
