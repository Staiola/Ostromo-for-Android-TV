import com.android.build.api.variant.ApplicationVariant
import groovy.json.JsonSlurper
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The web app in ../../nostromo is the one source for both TVs; its version
// (appinfo.json, the webOS manifest) is this app's version too.
val webApp = rootProject.file("../nostromo")
val webVersion = (JsonSlurper().parse(File(webApp, "appinfo.json")) as Map<*, *>)["version"] as String

// The release signing key lives outside the repository, in ~/.android:
// ostromo-release.jks, and ostromo-release.properties with storePassword,
// keyAlias and keyPassword (see the README). Without them the release build
// is unsigned.
val signingFile = File(System.getProperty("user.home"), ".android/ostromo-release.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.unterface.ostromo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unterface.ostromo"
        minSdk = 26
        targetSdk = 35
        versionName = webVersion
        // 0.9.2 -> 902, 1.10.0 -> 11000
        versionCode = webVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = File(signingFile.parentFile, "ostromo-release.jks")
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
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
    implementation("androidx.webkit:webkit:1.12.1")
}

// Copies the web app into the APK's assets. Debug is this TV's build, like
// `npm run nostromo:install`: preset.js and sound set A included. Release is
// the public build, like scripts/build-release.sh: no preset, no set A, and
// the flag that tells settings.js so.
abstract class OstromoAssets : DefaultTask() {
    @get:InputDirectory abstract val app: DirectoryProperty
    @get:Input abstract val release: Property<Boolean>
    @get:OutputDirectory abstract val out: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val public = release.get()
        fs.sync {
            from(app)
            into(out)
            // webOS packaging only
            exclude("appinfo.json", "icon.png", "largeIcon.png")
            if (public) exclude("preset.js", "sfx/**")
            filesMatching("index.html") {
                if (public) filter { it.replace("<script src=\"preset.js\"></script>", "<script>window.UNTERFACE_RELEASE = true;</script>") }
            }
        }
        if (public) {
            val html = out.file("index.html").get().asFile.readText()
            check("preset.js" !in html) { "preset still referenced" }
            check("UNTERFACE_RELEASE" in html) { "release flag missing" }
        }
    }
}

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<OstromoAssets>("copy${name}OstromoAssets") {
            app.set(webApp)
            release.set(variant.buildType == "release")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, OstromoAssets::out)
    }
}
