plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appId = "com.rudisec.echocast"
val appVersionName = "2.1.0-fork"
val appVersionCode = 7

android {
    namespace = "com.rudisec.echocast"

    compileSdk = 34

    defaultConfig {
        applicationId = appId
        minSdk = 28
        targetSdk = 33
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE")
            storeFile = if (keystore != null) File(keystore) else null
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            } ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.10.0")

    // Coroutines for async tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

/**
 * Builds a flashable Magisk module for the given build type.
 *
 * Layout of the zip:
 *   module.prop
 *   META-INF/com/google/android/{update-binary,updater-script}
 *   system/priv-app/<appId>/app-<variant>.apk
 *   system/etc/permissions/privapp-permissions-<appId>.xml
 */
fun registerMagiskModule(variantName: String) {
    val cap = variantName.replaceFirstChar { it.uppercase() }

    val stagingDir = layout.buildDirectory.dir("magisk-module/$variantName")
    val apkFile = layout.buildDirectory.file("outputs/apk/$variantName/app-$variantName.apk")

    val prepare = tasks.register("prepareMagisk$cap") {
        group = "magisk"
        description = "Stages the Magisk module for the $variantName build."
        dependsOn("assemble$cap")

        inputs.dir(File(projectDir, "magisk"))
        inputs.file(apkFile)
        outputs.dir(stagingDir)

        doLast {
            val root = stagingDir.get().asFile
            root.deleteRecursively()
            root.mkdirs()

            // Scripts must have Unix line endings, otherwise Magisk refuses them.
            File(projectDir, "magisk").listFiles()?.filter { it.isFile }?.forEach { src ->
                val normalized = src.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                File(root, src.name).writeText(normalized, Charsets.UTF_8)
            }

            File(root, "module.prop").writeText(
                """
                id=$appId
                name=EchoCast (fork)
                version=v$appVersionName
                versionCode=$appVersionCode
                author=rudisec + fork
                description=Play sounds into phone calls. Fork with local preview, diagnostics and a working decoder.
                """.trimIndent()
            )

            val permissions = File(root, "system/etc/permissions")
            permissions.mkdirs()
            File(permissions, "privapp-permissions-$appId.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                    <privapp-permissions package="$appId">
                        <permission name="android.permission.CONTROL_INCALL_EXPERIENCE" />
                        <permission name="android.permission.MODIFY_PHONE_STATE" />
                    </privapp-permissions>
                </permissions>
                """.trimIndent()
            )

            val appDir = File(root, "system/priv-app/$appId")
            appDir.mkdirs()
            apkFile.get().asFile.copyTo(File(appDir, "app-$variantName.apk"), overwrite = true)
        }
    }

    tasks.register<Zip>("zip$cap") {
        group = "magisk"
        description = "Builds EchoCast-$appVersionName-$variantName.zip"
        dependsOn(prepare)

        archiveFileName.set("EchoCast-$appVersionName-$variantName.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions/$variantName"))

        from(stagingDir)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

registerMagiskModule("debug")
registerMagiskModule("release")
