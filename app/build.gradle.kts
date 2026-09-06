plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.thgillwtnorizoh.modesty"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.thgillwtnorizoh.modesty"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.1-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
