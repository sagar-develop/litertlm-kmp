import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "com.sagar"
version = "0.1.0"

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
