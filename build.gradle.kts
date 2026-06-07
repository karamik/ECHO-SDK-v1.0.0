// echo-sdk/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("maven-publish")
}

android {
    namespace = "com.echo.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.goterl:lazysodium-android:5.1.0")
    implementation("net.java.dev.jna:jna:5.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.echo"
            artifactId = "echo-sdk"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
