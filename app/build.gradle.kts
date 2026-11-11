import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------------------------
// ONNX Runtime flavour (see gradle.properties). The QNN and the NNAPI/CPU AARs both contain
// libonnxruntime.so, so exactly one of them may be on the classpath.
// ---------------------------------------------------------------------------------------------
val ortRuntime: String = (providers.gradleProperty("swapstudio.ortRuntime").orNull ?: "qnn").trim()

android {
    namespace = "com.swapstudio.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swapstudio.ai"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // The QNN HTP/GPU libraries that ship inside onnxruntime-android-qnn are 64-bit only, and
        // every Snapdragon flagship is arm64. Keep x86_64 so the NNAPI flavour still runs on an
        // emulator; drop it for a smaller store bundle.
        ndk { abiFilters += listOf("arm64-v8a") }
        if (ortRuntime == "nnapi") ndk.abiFilters += "x86_64"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        // The QNN backends (libQnnHtp.so, libQnnHtpV*Skel.so, ...) are dlopen'ed by path from the
        // app's native library directory, so the native libraries must be extracted to disk.
        jniLibs { useLegacyPackaging = true }
    }

    androidResources { noCompress += listOf("task", "onnx", "bin") }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    // ---- AndroidX core / lifecycle / compose -------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- Background work / storage -----------------------------------------------------------
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    // ---- Media3 (ExoPlayer preview + Transformer based MP4 export) ---------------------------
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // ---- DI ----------------------------------------------------------------------------------
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // ---- Networking (model downloads with pause/resume) --------------------------------------
    implementation(libs.okhttp)

    // ---- On-device inference -----------------------------------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    if (ortRuntime == "nnapi") {
        implementation(libs.onnxruntime.android)
    } else {
        implementation(libs.onnxruntime.android.qnn)
    }
    implementation(libs.mediapipe.tasks.vision)
    // OpenCV 4.9 ships as a Maven artifact next to the SDK download since 4.9.0.
    implementation(libs.opencv)

    // ---- Optional FFmpegKit bridge ------------------------------------------------------------
    val ffmpegKit = providers.gradleProperty("swapstudio.ffmpegKit").orNull?.trim().orEmpty()
    if (ffmpegKit.isNotEmpty()) {
        if (ffmpegKit.endsWith(".aar") || ffmpegKit.endsWith(".jar")) implementation(files(ffmpegKit))
        else implementation(ffmpegKit)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
