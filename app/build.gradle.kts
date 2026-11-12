plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}  

// Build flag: -PxrayOnly=true builds the Xray-only app (no Tor/WARP code, cores, assets, Psiphon aar).
// Without it the same tree builds the full 3-engine app (Xray + Tor + WARP).
val xrayOnly = (project.findProperty("xrayOnly") as? String)?.toBoolean() ?: false
// -PabiOnly=arm64-v8a restricts the APK to one ABI (default: arm64-v8a, armeabi-v7a, x86_64).
val abiOnly = (project.findProperty("abiOnly") as? String)?.takeIf { it.isNotBlank() }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.meh.vpn"
    minSdk = 24
    targetSdk = 36
    val appVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 20000
    val appVersionName = (project.findProperty("appVersionName") as? String) ?: "2.0"
    versionCode = appVersionCode
    versionName = appVersionName
    // In-app label, e.g. 1.0 -> "نسخه ۱", 1.2 -> "نسخه ۱.۲".
    val faDigits = appVersionName.removeSuffix(".0").map { if (it.isDigit()) '۰' + (it - '0') else it }.joinToString("")
    val label = (project.findProperty("appVersionLabel") as? String) ?: "نسخه $faDigits"
    buildConfigField("String", "VERSION_LABEL_FA", "\"$label\"")
    buildConfigField("boolean", "ENGINES_XRAY_ONLY", xrayOnly.toString())

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk { abiFilters += (abiOnly?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
  }

  sourceSets {
    getByName("main") {
      if (xrayOnly) {
        java.srcDir("src/xrayonly/java"); kotlin.srcDir("src/xrayonly/java")
        assets.srcDir("src/xrayonly/assets")
      } else {
        java.srcDir("src/engines3/java"); kotlin.srcDir("src/engines3/java")
        assets.srcDir("src/engines3/assets")
        jniLibs.srcDir("src/engines3/jniLibs")
      }
    }
    getByName("test") {
      if (!xrayOnly) { java.srcDir("src/testEngines3/java"); kotlin.srcDir("src/testEngines3/java") }
    }
  }

  packaging {
    // Engine cores are executables shipped as lib*.so; they must be extracted to run.
    jniLibs { useLegacyPackaging = true }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        // One JVM per test class, headless AWT (Robolectric bitmaps) and no CDS: avoids JDK class-load
        // corruption seen in long-lived test JVMs in the build sandbox.
        it.setForkEvery(1)
        it.jvmArgs("-Djava.awt.headless=true", "-Xshare:off")
        // -PtestJava=<java> runs the unit tests on another JVM (e.g. an x86_64 JDK under qemu-user,
        // for Robolectric native graphics screenshots on an arm64 Linux host).
        (project.findProperty("testJava") as? String)?.let { exe -> it.setExecutable(exe) }
        System.getenv("MOHR_SHOT_DIR")?.let { d -> it.environment("MOHR_SHOT_DIR", d) }
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
    // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  // implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
      // Uncomment to use Firestore:
  
  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
    // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
// implementation(libs.googleid)
        implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  if (!xrayOnly) implementation(files("libs/ca.psiphon.aar"))
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
