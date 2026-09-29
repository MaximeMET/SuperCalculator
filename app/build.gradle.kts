plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.maximemet.supercalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.maximemet.supercalc"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 还没到发布阶段，先不折腾混淆
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Symja 2016 用到 java.util.function 之类 Java 8 的 API
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":engine"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
