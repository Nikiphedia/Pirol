import java.util.Properties

// T126: Signierschluessel fuer Release-Builds aus keystore.properties (nicht im Repo, siehe .gitignore).
// Fehlt die Datei, bleibt der Release-Build unsigniert (z.B. in der CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.etasystems.pirol"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "ch.etasystems.pirol"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.0.11"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // 16-KB-Page-Size-Kompatibilitaet (Android 15+/16)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // T132: lokale Oboe-Kopie statt Git-Clone pro ABI und Arbeitsordner (spart 10-30 min).
                // Gesetzt ueber Umgebungsvariable PIROL_OBOE_DIR, z.B. D:/S/.oboe-1.9.0 (Tag 1.9.0).
                System.getenv("PIROL_OBOE_DIR")?.takeIf { file(it).isDirectory }?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_OBOE=" + it.replace(File.separatorChar, '/')
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // T159: Release = je eine APK pro ABI (arm64-v8a, armeabi-v7a), kein x86_64, keine universelle APK.
    // Splits gelten global in AGP und vertragen sich nicht mit ndk.abiFilters. Darum nur aktiv, wenn
    // ein Release-Task angefordert wird; Debug (Emulator x86_64) baut eine APK mit allen drei ABIs.
    val releaseRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
    }
    splits {
        abi {
            isEnable = releaseRequested
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            if (!releaseRequested) {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            }
        }
        release {
            if (!keystoreProps.getProperty("storeFile").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // T159: sprechende Dateinamen, gleicher versionCode fuer beide ABI-APKs
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val abi = out.getFilter("ABI")
            if (abi != null) out.outputFileName = "Pirol-${versionName}-${abi}-${buildType.name}.apk"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    // T243: Zusatzsprachen (fr, it, ca, pt, nl, gsw) fallen laut CLAUDE.md auf Deutsch zurueck; de/en sind vollstaendig
    lint {
        disable += "MissingTranslation"
    }

    // T94: android.util.Log in Pipeline/InferenceWorker liefert im JVM-Unit-Test Defaults statt Exception
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.window)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Ktor (Xeno-Canto API, spaeter)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Serialization
    implementation(libs.serialization.json)

    // ONNX Runtime Mobile (BirdNET V3.0 Inference)
    implementation(libs.onnxruntime.android)

    // Google Play Services Location (GPS)
    implementation(libs.play.services.location)

    // WorkManager (Background-Uploads)
    implementation(libs.work.runtime.ktx)

    // osmdroid (OpenStreetMap Karte)
    implementation(libs.osmdroid.android)

    // DocumentFile (SAF-Unterstuetzung fuer oeffentlichen Speicher, T51)
    implementation(libs.documentfile)

    // NAS-Upload via SMB + verschluesseltes Passwort (T125)
    implementation(libs.smbj)

    // Unit-Tests (T53: AccuracyFilterTest, MedianSmoothingTest)
    testImplementation("junit:junit:4.13.2")
}
