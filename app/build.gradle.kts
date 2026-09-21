plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.trevor.assistant"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.trevor.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.0.3"
    }

    buildFeatures {
        compose = true
    }

    dependencies {
        implementation(platform("androidx.compose:compose-bom:2024.12.01"))

        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.ui:ui-tooling-preview")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.compose.material:material-icons-extended")
        implementation("androidx.activity:activity-compose:1.10.0")
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

        debugImplementation("androidx.compose.ui:ui-tooling")

        implementation("androidx.room:room-runtime:2.8.5")
        ksp("androidx.room:room-compiler:2.8.5")
    }
}
