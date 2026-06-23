import org.eclipse.jgit.api.Git
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.dicio.sentences.compiler.plugin)
    }
}

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.android)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.compose)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.parcelize)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.com.google.protobuf)
    alias(libs.plugins.dicio.sentences.compiler.plugin)
}

android {
    namespace = "org.stypox.dicio"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.stypox.dicio"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 18
        versionName = "4.1-lean"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            var normalizedGitBranch = gitBranch().replaceFirst("^[^A-Za-z]+", "").replace(Regex("[^0-9A-Za-z]+"), "")
            applicationIdSuffix = ".$normalizedGitBranch"
            versionNameSuffix = "-$normalizedGitBranch"
            resValue("string", "app_name", "Dicio-${gitBranch()}")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
            freeCompilerArgs = listOf("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    // sherpa-onnx .onnx model files are read from filesDir at runtime (not bundled in assets),
    // so no noCompress entry is needed. If you ever decide to bundle them in assets instead,
    // add: androidResources { noCompress += listOf("onnx") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        generateProtoTasks {
            all().forEach {
                it.builtins {
                    create("kotlin") { option("lite") }
                    create("java") { option("lite") }
                }
            }
        }
    }
}

// workaround for https://github.com/google/ksp/issues/1590
val kspKotlinRegex = "^ksp(.*)Kotlin$".toRegex()
androidComponents {
    onVariants(selector().all()) { variant ->
        afterEvaluate {
            tasks.named(kspKotlinRegex::matches).configureEach {
                val capName = kspKotlinRegex.find(name)!!.groupValues[1]
                dependsOn(tasks.named("generate${capName}Proto"))
            }
        }
    }
}

dependencies {
    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Dicio own libraries
    implementation(libs.dicio.numbers)
    implementation(project(":skill"))

    // ── sherpa-onnx ──────────────────────────────────────────────────────────
    // The AAR bundles both the Kotlin API classes and the native JNI .so files
    // for arm64-v8a, armeabi-v7a, x86, and x86_64.
    //
    // Setup (one-time, only needed when ONNX Whisper input is selected):
    //   1. Download the latest sherpa-onnx Android AAR from GitHub releases:
    //      https://github.com/k2-fsa/sherpa-onnx/releases
    //      File to download: sherpa-onnx-<version>.aar
    //      (built from the repo via JitPack, or see ONNX_WHISPER_SETUP.md for
    //       the alternative .so-files approach)
    //   2. Rename it to sherpa-onnx.aar and place it in app/libs/.
    //   3. Sync Gradle. The dependency below picks it up automatically.
    //
    // Tested with: sherpa-onnx 1.13.2
    // If the libs/ directory is absent or empty, compilation still succeeds
    // but OnnxWhisperInputDevice will fail to find the classes at runtime.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Android
    implementation(libs.appcompat)

    // Compose
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.debug.compose.ui.tooling)
    debugImplementation(libs.debug.compose.ui.test.manifest)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.hilt.android.testing)
    testAnnotationProcessor(libs.hilt.android.compiler)

    // Protobuf and Datastore
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.protobuf.java.lite)
    implementation(libs.datastore)

    // Navigation
    implementation(libs.kotlin.serialization)
    implementation(libs.navigation)

    // Permission Flow
    implementation(libs.permission.flow.android)
    implementation(libs.permission.flow.compose)

    // App-icon rendering for OpenSkill (bundled in Accompanist, no network needed)
    implementation("com.google.accompanist:accompanist-drawablepainter:0.37.3")

    // Testing
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.test.rules)
}

// this is required to avoid NoClassDefFoundError for ActivityInvoker during androidTest
configurations.configureEach {
    resolutionStrategy {
        force(libs.test.core)
    }
}

fun gitBranch(): String {
    return try {
        Git.open(rootDir).use { it.repository.branch }
    } catch (_: Exception) {
        "unknown"
    }
}
