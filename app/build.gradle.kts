import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 1. Read secret API key from root .env file (gitignored so it is never uploaded to GitHub)
val envVars = mutableMapOf<String, String>()
val envFile = rootProject.file(".env")
if (envFile.exists()) {
    envFile.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
            val key = line.substringBefore("=").trim()
            val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
            envVars[key] = value
        }
    }
}

// 2. Fallback to local.properties or system environment variable if .env is not set
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val openAiKey: String = envVars["OPENAI_API_KEY"]
    ?: localProps.getProperty("OPENAI_API_KEY")
    ?: System.getenv("OPENAI_API_KEY")
    ?: ""

val escapedOpenAiKey = openAiKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.calculator.scientific"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calculator.scientific"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "OPENAI_API_KEY", "\"$escapedOpenAiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // CameraX for automatic focus lock + paper sharpness detection + silent photo capture
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Coroutines & OkHttp for OpenAI GPT-4o Vision API requests
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
