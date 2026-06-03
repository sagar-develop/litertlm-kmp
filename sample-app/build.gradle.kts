import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.kotlinSerialization)
    // ObjectBox plugin must be applied AFTER the Kotlin plugins — it generates
    // MyObjectBox + the model from @Entity classes and wires the kapt processor.
    alias(libs.plugins.objectbox)
}

// Release signing — credentials live in sample-app/keystore.properties (gitignored).
// Falls back to debug signing if the file is absent, so a fresh clone still builds.
val keystorePropsFile = rootProject.file("sample-app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.nativelm.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.nativelm.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 5
        versionName = "0.6.1"

        // Only arm64-v8a: every device with the RAM to run a 2.6 GB LLM is
        // 64-bit, and the LiteRT-LM / MediaPipe .so dominate APK size.
        ndk { abiFilters += "arm64-v8a" }

        // Pull the model URL from sample-app/local.properties (gitignored).
        // Visitors who clone the repo paste their own Firebase/CDN URL there
        // before running. See README → "Running the sample app".
        val localProps = Properties()
        val localPropsFile = project.file("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        val modelUrl = localProps.getProperty("model.url")
            ?: "https://REPLACE_WITH_YOUR_HOST/gemma-4-E2B-it.litertlm"
        buildConfigField("String", "MODEL_URL", "\"$modelUrl\"")
        buildConfigField(
            "String",
            "MODEL_FILE_NAME",
            "\"${localProps.getProperty("model.fileName") ?: "gemma-4-E2B-it.litertlm"}\""
        )
        buildConfigField(
            "long",
            "MODEL_SIZE_BYTES",
            "${localProps.getProperty("model.sizeBytes") ?: "2588000000"}L"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // LiteRT-LM dlopen()s some .so by path → keep them page-unaligned-safe.
            useLegacyPackaging = true
            // LiteRT-LM and MediaPipe each bundle TFLite .so — keep the first.
            pickFirsts += listOf("**/libtensorflowlite_jni.so", "**/libtensorflowlite_gpu_jni.so")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation(project(":lib"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)

    // ObjectBox — multi-conversation persistence + document-chunk HNSW vector index.
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)

    // PDFBox (Android port) — text extraction from imported PDFs for document RAG.
    implementation(libs.pdfbox.android)
    // ML Kit on-device OCR (bundled model) — text from scanned PDFs and images.
    implementation(libs.mlkit.text.recognition)
    // Argon2id (native) — derives the AES key for encrypted local backups from a passphrase.
    implementation(libs.signal.argon2)

    implementation(libs.kotlinx.coroutines.android)
    // Runtime-only JSON (no compiler plugin, matching :lib's usage) — used to
    // persist a message's RAG citations as a string column on MessageEntity.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.napier)
    // Ktor — needed because EngineHolder constructs an HttpClient directly.
    // Production consumers using kotlin-inject (the library's DI graph) would
    // get this transitively; the manual-wiring sample needs it explicit.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    // JVM unit tests for the pure RAG logic (chunker, context formatter, retriever
    // relevance gate). No Android framework needed — these run on testDebugUnitTest.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
