// Test harness for :mdm-lite: a WebView screen wired the way android-menu-board is,
// plus crash/ANR triggers. Not shipped.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mdmServerUrl: String = (project.findProperty("mdmServerUrl") as String?) ?: "http://10.32.1.170:8083"
val mdmEnrollToken: String = (project.findProperty("mdmEnrollToken") as String?) ?: ""
val demoUrl: String = (project.findProperty("demoUrl") as String?) ?: "https://example.com"

android {
    namespace = "com.aioapp.mdmlite.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aioapp.mdmlite.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MDM_SERVER_URL", "\"$mdmServerUrl\"")
        buildConfigField("String", "MDM_ENROLL_TOKEN", "\"$mdmEnrollToken\"")
        buildConfigField("String", "DEMO_URL", "\"$demoUrl\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":mdm-lite"))
}
