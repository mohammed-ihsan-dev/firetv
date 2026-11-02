import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: created once by scripts/build.sh, kept in signing/ (needed for in-place updates).
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.shahu.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shahu.tv"
        minSdk = 28        // Fire OS 7 / Android 9
        targetSdk = 28     // match the device platform exactly
        versionCode = 3
        versionName = "3.0.0"
    }

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Separate package so a debug/probe build never replaces the real install.
            applicationIdSuffix = ".probe"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        // targetSdk 28 is intentional (device is Android 9); don't fail release on Play-store policy checks.
        checkReleaseBuilds = false
        abortOnError = false
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/shahuAssets"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Bundle config/wallpapers.json + only the wallpapers it references (JPG/PNG/WebP) as built-in defaults.
val shahuConfig = rootProject.file("config/wallpapers.json")
val copyShahuAssets by tasks.registering(Sync::class) {  // Sync: also removes wallpapers no longer referenced
    val referenced = Regex("\"wallpapers/([^\"/]+\\.(?:jpe?g|png|webp))\"")
        .findAll(shahuConfig.readText()).map { it.groupValues[1] }.toSet()
    inputs.file(shahuConfig)
    into(layout.buildDirectory.dir("generated/shahuAssets"))
    from(rootProject.file("wallpapers")) { into("wallpapers"); include(referenced) }
    from(shahuConfig)
}
tasks.named("preBuild") { dependsOn(copyShahuAssets) }
