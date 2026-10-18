plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.script"
        compileSdk = 37
        minSdk = 26
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(libs.mozilla.rhino)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okhttp)
            implementation(libs.androidx.collection)
        }
    }
}
