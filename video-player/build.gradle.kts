plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
val versionCode = 1
val versionName = "1.0.0"
android {
    namespace = "com.giffard.video_player"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
                // 指定构建目标架构为 arm64-v8a
//                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }
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
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

//    // 配置 AAR 输出路径
//    libraryVariants.all {
//        if (buildType.name == "release") {
//            outputs.configureEach {
//                val aar = file("build/outputs/aar/${project.name}-${versionName}.aar")
//                archiveFile.set(aar)
//            }
//        }
//    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}