plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.silicontutor.silicontutor"
    compileSdk = 36

    sourceSets {
        getByName("main").assets.srcDir("../../curriculum")
        getByName("test").resources.srcDir("../../curriculum")
    }

    defaultConfig {
        applicationId = "dev.silicontutor.silicontutor"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
