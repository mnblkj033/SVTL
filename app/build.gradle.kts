plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.svtl.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.svtl.manager"
        minSdk = 26
        targetSdk = 35
        // CI 上用 run number 自增，保证每次安装都能覆盖升级
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = run ?: 1
        versionName = if (run != null) "0.2.$run" else "0.2.0"
    }

    signingConfigs {
        // 固定签名：debug/release 都用它，避免每次 CI 生成新 debug keystore 导致覆盖安装失败
        create("svtl") {
            storeFile = file("svtl.jks")
            storePassword = "svtl1234"
            keyAlias = "svtl"
            keyPassword = "svtl1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("svtl")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("svtl")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    debugImplementation("androidx.compose.ui:ui-tooling")
}
