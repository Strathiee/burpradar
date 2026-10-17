plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.legacy.kapt)
}

android {
    namespace = "com.example.burpradar"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.burpradar"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    androidResources {
        noCompress += "tflite"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // TensorFlow Lite Audio Task Library
  implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

  // CameraX dependencies
  val cameraVersion = "1.3.1"
  implementation("androidx.camera:camera-core:$cameraVersion")
  implementation("androidx.camera:camera-camera2:$cameraVersion")
  implementation("androidx.camera:camera-lifecycle:$cameraVersion")
  implementation("androidx.camera:camera-video:$cameraVersion")
  implementation("androidx.camera:camera-view:$cameraVersion")

  // Room Database
  val roomVersion = "2.8.4"
  implementation("androidx.room:room-runtime:$roomVersion")
  implementation("androidx.room:room-ktx:$roomVersion")
  "kapt"("androidx.room:room-compiler:$roomVersion")

  // Media3 (ExoPlayer) for Video Playback
  val media3Version = "1.2.1"
  implementation("androidx.media3:media3-exoplayer:$media3Version")
  implementation("androidx.media3:media3-ui:$media3Version")
}

tasks.withType<org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask>().configureEach {
    kaptProcessJvmArgs.add("-Dorg.sqlite.tmpdir=C:/Users/chris/AppData/Local/Temp")
}
