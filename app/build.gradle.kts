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
    namespace = "io.github.miniontoby.rokidapkuploader"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.miniontoby.rokidapkuploader"
        minSdk = 28
        targetSdk = 36
        versionCode = 102
        versionName = "1.0.2"

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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.rokid.cxr:client-m:1.2.1")
}
