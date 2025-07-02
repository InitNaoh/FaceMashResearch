plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("de.undercouch.download")
}

android {
    namespace = "com.infinity.facemashresearch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.infinity.facemashresearch"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
}

// Định nghĩa biến ASSET_DIR
extra.set("ASSET_DIR", "${projectDir}/src/main/assets")

// Import file script phụ
apply {
    from("download_tasks.gradle")   // Nếu file là Groovy DSL
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)


    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")

    // WindowManager
    implementation("androidx.window:window:1.4.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}