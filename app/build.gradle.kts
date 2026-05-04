import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun escapeBuildConfigString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

android {
    namespace = "com.rokidapks"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rokidapks"
        minSdk = 28
        targetSdk = 36
        versionCode = 120
        versionName = "1.2.0"

        buildConfigField(
            "String",
            "ROKID_CLIENT_SECRET",
            "\"${escapeBuildConfigString(localProperties.getProperty("rokid.clientSecret", ""))}\"",
        )
        buildConfigField(
            "String",
            "ROKID_AUTH_BLOB_NAME",
            "\"${escapeBuildConfigString(localProperties.getProperty("rokid.authBlobName", ""))}\"",
        )

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
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.rokid.cxr:client-m:1.2.1")
    implementation(files("libs/client-l-1.0.1-stripped.aar"))
}
