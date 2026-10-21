plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.simats.sehatsethu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simats.sehatsethu"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        buildConfig = true
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.play.services.location)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.lifecycle.runtime.ktx)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.alphacephei:vosk-android:0.3.47")

}