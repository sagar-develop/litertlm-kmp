import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinKapt)
    // ObjectBox plugin must be applied AFTER the Kotlin plugins — it generates
    // MyObjectBox + the model from @Entity classes and wires the kapt processor.
    alias(libs.plugins.objectbox)
}

android {
    namespace = "com.nativelm.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.nativelm.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.4"

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
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    // ObjectBox — multi-conversation persistence (Conversation + Message entities).
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.napier)
    // Ktor — needed because EngineHolder constructs an HttpClient directly.
    // Production consumers using kotlin-inject (the library's DI graph) would
    // get this transitively; the manual-wiring sample needs it explicit.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    debugImplementation(libs.compose.ui.tooling)
}
