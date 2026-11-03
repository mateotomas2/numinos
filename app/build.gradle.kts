plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mateotomas2.numinos"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mateotomas2.numinos"
        minSdk = 26 // Android 8.0, for phones from about 2017-2018 onward
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
