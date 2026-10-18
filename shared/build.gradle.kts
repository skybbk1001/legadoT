plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "io.legado.shared"
        compileSdk = 37
        minSdk = 26
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.room.common)
            api(libs.gson)
            api(libs.jsoup)
            api(libs.jsoupxpath)
            api(libs.json.path)
            api(libs.okhttp)
            api(libs.brotli.dec)
            api(libs.commons.lang3)
            api(libs.commons.text)
            api(project(":icu4j"))
            api(project(":modules:rhino"))
            api(project(":modules:book"))
            implementation(libs.androidx.collection)
        }

        androidMain.dependencies {
            implementation(libs.splitties.systemservices)
        }
    }
}
