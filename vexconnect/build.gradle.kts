plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace   = "id.pixelgenius.vexconnect"
    compileSdk  = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // X25519 ECDH + HKDF for the session key handshake - pure Java, no native
    // code, works down to minSdk 21 (Android's own XDH crypto provider
    // support is inconsistent/absent on older API levels).
    implementation(libs.bouncycastle)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId    = "id.pixelgenius"
                artifactId = "vexconnect"
                version    = "1.0.0"
            }
        }
    }
}
