plugins {
    id("com.android.application")
}

// The release pipeline provides these. Local builds work without them.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "io.github.mateotomas2.numinos"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mateotomas2.numinos"
        minSdk = 26 // Android 8.0, for phones from about 2017-2018 onward
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = "0.3.0" // Raise this in every PR: merging publishes it as a final release.
        versionNameSuffix = System.getenv("VERSION_SUFFIX")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "numinos"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}
