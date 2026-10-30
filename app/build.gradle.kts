import java.util.Properties

plugins {
    alias(libs.plugins.google.services)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val enterpriseConfig = Properties().apply {
    val file = rootProject.file("enterprise.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android {
    namespace = "com.example.fatiguesense.enterprise"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fatiguesense.enterprise"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        mapOf(
            "BACKEND_URL" to "https://YOUR_BACKEND_HOST.invalid/api",
        ).forEach { (key, fallback) ->
            val value = enterpriseConfig.getProperty(key, fallback)
            require(!value.contains('"') && !value.contains('\\') && !value.contains('\n'))
            buildConfigField("String", key, "\"$value\"")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    androidResources {
        noCompress("tflite")
    }
}

dependencies {
    // Compatible with the cloned Kotlin 2.0 toolchain; 34.19.0 Auth requires Kotlin 2.3 metadata.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // ML & Video Streaming
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
    implementation(libs.mlkit.face)

    // Room DB
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
