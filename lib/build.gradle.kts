import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "com.sagar"
version = "0.11.0"

// Keep the published artifact id stable as "litertlm-kmp" even though the
// Gradle module is now ":lib" (sample-app is a sibling subproject). Without
// this, KMP would publish as "lib" / "lib-android" / "lib-iosarm64", which
// would break v0.1.0 consumers.
base.archivesName.set("litertlm-kmp")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            // LiteRT-LM artifacts on Google Maven are built against Kotlin 2.3.0
            // metadata. Silenced here so the dependency resolves cleanly across
            // minor Kotlin bumps; safe to remove once LiteRT-LM ships against
            // a metadata version that matches this project's Kotlin.
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.napier)
            api(libs.kotlin.inject.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.mediapipe.tasks.text)
            implementation(libs.litertlm.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.okhttp)
            // Argon2id (native, JNI) for passphrase-derived backup encryption keys.
            implementation(libs.signal.argon2)
            // EmbeddingGemma RAG embedder: ONNX Runtime for inference (Microsoft,
            // telemetry-free — no Google/Play deps). Tokenization is pure-Kotlin
            // (GemmaBpeTokenizer), so no onnxruntime-extensions native lib is needed.
            implementation(libs.onnxruntime.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.sagar.aicore"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // R8 keep rules for the engine's reflection/JNI surfaces (LiteRT-LM,
        // MediaPipe, Gson, kotlinx.serialization). Consumers inherit these in
        // their minified release builds automatically.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.kotlin.inject.compiler)
    add("kspAndroid", libs.kotlin.inject.compiler)
    add("kspIosX64", libs.kotlin.inject.compiler)
    add("kspIosArm64", libs.kotlin.inject.compiler)
    add("kspIosSimulatorArm64", libs.kotlin.inject.compiler)
}

// Override KMP-default artifactIds so they stay aligned with "litertlm-kmp"
// rather than the ":lib" Gradle module name. Wrapped in afterEvaluate so this
// runs AFTER the KMP plugin sets its own artifactIds — otherwise KMP overwrites
// our override and consumers see :lib-android instead of :litertlm-kmp-android.
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            artifactId = when (name) {
                "kotlinMultiplatform" -> "litertlm-kmp"
                "androidRelease" -> "litertlm-kmp-android"
                else -> "litertlm-kmp-${name.lowercase()}"
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("litertlm-kmp")
            description.set("Kotlin Multiplatform wrapper around LiteRT-LM for running Gemma-family models on-device.")
            url.set("https://github.com/sagar-develop/litertlm-kmp")

            licenses {
                license {
                    name.set("GNU Affero General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("sagar-develop")
                    name.set("Sagar Gupta")
                    email.set("sgupta8874@gmail.com")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/sagar-develop/litertlm-kmp.git")
                developerConnection.set("scm:git:ssh://github.com:sagar-develop/litertlm-kmp.git")
                url.set("https://github.com/sagar-develop/litertlm-kmp/tree/main")
            }
        }
    }
}
