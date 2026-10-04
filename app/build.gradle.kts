import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arjun.gander"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arjun.gander"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A device test that hangs blocks the whole nightly run behind it, and
        // an emulator gives no sign of the difference between slow and stuck.
        // Three minutes is far longer than the slowest of these needs.
        testInstrumentationRunnerArguments["timeout_msec"] = "180000"
    }

    // The release keystore is intentionally not in the repo. Contributors without
    // it get an unsigned release build; debug builds always work.
    //
    // The password is read from GANDER_STORE_PASSWORD / GANDER_KEY_PASSWORD, which
    // belong in ~/.gradle/gradle.properties rather than here, because this file is
    // public and a password committed to it is a password published. The fallback
    // is the throwaway one the README tells contributors to generate their own key
    // with, so cloning and building keeps working with no configuration at all.
    val releaseKeystore = rootProject.file("keystore/gander.jks")
    if (releaseKeystore.exists()) {
        val storePass = (findProperty("GANDER_STORE_PASSWORD") as String?) ?: "gander-local"
        val keyPass = (findProperty("GANDER_KEY_PASSWORD") as String?) ?: storePass
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePass
                keyAlias = "gander"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // The app is 1,700 lines of Kotlin; the dex was 14.8 MB, nearly all of it
            // library code that nothing here calls. R8 keeps the reachable part and
            // drops the rest, and shrinkResources does the same for the resource table.
            // Kept in the open, so obfuscation buys no secrecy: what it buys is the
            // shrinking and optimisation that come with it. See proguard-rules.pro for
            // why line numbers survive it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // So a debug build installs alongside an existing release install rather
            // than being refused for having a different signing key, which would
            // otherwise mean uninstalling and losing recents and folder grants.
            applicationIdSuffix = ".debug"
        }
    }

    // No per-ABI split: with PDF rendering moved off Pdfium the app ships no native
    // code at all, so one APK serves every architecture.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Warnings are frozen into the baseline below, so that a new one fails the
        // build while the existing ones stay visible in the report rather than
        // blocking every change until they are all fixed. Some are accessibility
        // warnings, which is the category Play's pre-launch report reads, and
        // frozen is not fixed: they are worth coming back to.
        //
        // The three "newer version available" checks are off because they cannot
        // be baselined. Lint matches a baseline entry by its exact message, and
        // theirs name whatever upstream published last, so a frozen one stops
        // matching the day a dependency releases and turns the build red with no
        // change here at all.
        //
        // Regenerate after fixing some: delete app/lint-baseline.xml, run
        // ./gradlew lintDebug (it fails once, on purpose, having written a new
        // baseline), and read the diff before committing it.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        // A lint failure should stop CI, not be buried in a report nobody opens
        abortOnError = true
        checkDependencies = false
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest, the resource table and the
            // assets directory. Without this it gets none of them, and every test
            // that inflates a layout or reads viewer/ fails on a missing resource.
            isIncludeAndroidResources = true
            // Some tests read files straight off the disk, where Gradle cannot see
            // them: ReleaseHygieneTest the changelog, the ProGuard rules, the store
            // listing and this file, VendoredLibsTest docs/VENDORED.md and the fetch
            // script, RangeParityTest the range cases. Declared as inputs, an edit to
            // any of them on its own reruns the tests instead of leaving them up to
            // date and unrun. The assets they read are inputs already.
            all { test ->
                test.inputs.files(
                    rootProject.file("CHANGELOG.md"),
                    file("proguard-rules.pro"),
                    rootProject.fileTree("fastlane/metadata/android") { include("**/*.txt") },
                    file("build.gradle.kts"),
                    rootProject.file("docs/VENDORED.md"),
                    rootProject.file("scripts/fetch-viewer-libs.sh"),
                    rootProject.file("tests/fixtures/range-cases.json"),
                ).withPropertyName("filesReadOffDisk")
                // Every test class shares one JVM, and Gradle gives it 512 MB unless told
                // otherwise. ZipReaderTest builds a 200,001-entry zip, and run near the end
                // of the suite it found that heap nearly full: out of memory in two of three
                // full runs on 2026-10-01, where on its own it takes half a second.
                test.maxHeapSize = "1g"
            }
        }
    }

    // The fixture documents live outside the module because three different test
    // tiers read the same files: unit tests as resources, instrumented tests as
    // assets in the test APK, and the Python viewer tests straight off disk.
    // One directory, generated by tests/fixtures/make_fixtures.py.
    sourceSets {
        getByName("test") { resources.srcDir("../tests/fixtures/files") }
        // The instrumented tests read the same files, but out of the test
        // APK's assets, because a device has no access to the repo.
        getByName("androidTest") { assets.srcDir("../tests/fixtures/files") }
    }
}

// Requesting nothing is the whole promise, but permissions arrive transitively: Media3 contributes
// ACCESS_NETWORK_STATE, stripped in the manifest. Naming one permission there does not stop the next
// dependency bump adding another, and that would surface in the store listing rather than the build.
// So assert the invariant on the merged manifest instead of trusting the strip.
//
// Held as suffixes on the variant's own applicationId, since a debug build carries
// one and would otherwise fail against a hardcoded package name.
val permissionAllowlistSuffixes = setOf(
    // androidx.core declares this so libraries can registerReceiver(..., RECEIVER_NOT_EXPORTED).
    // Signature level and self-granted, so it is never shown to the user as a permission.
    ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.uppercase() }
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val appId = variant.applicationId

    val checkPermissions = tasks.register("check${suffix}Permissions") {
        description = "Fails if the merged manifest requests any permission we did not sign off on."
        val manifestFile = mergedManifest
        val allowedSuffixes = permissionAllowlistSuffixes
        val applicationId = appId
        val stamp = layout.buildDirectory.file("reports/permissions/$suffix.txt")
        inputs.file(manifestFile)
        outputs.file(stamp)
        doLast {
            val requested = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
                .findAll(manifestFile.get().asFile.readText())
                .map { it.groupValues[1] }
                .toList()
            val allowed = allowedSuffixes.map { applicationId.get() + it }.toSet()
            val unexpected = requested.filterNot { it in allowed }
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Gander ships with no permissions, but $suffix requests:")
                        unexpected.forEach { appendLine("    $it") }
                        appendLine()
                        appendLine("A dependency added these. Either strip each one with")
                        appendLine("tools:node=\"remove\" in AndroidManifest.xml, or add it to")
                        append("permissionAllowlist in app/build.gradle.kts with a reason.")
                    }
                )
            }
            stamp.get().asFile.apply {
                parentFile.mkdirs()
                writeText(requested.joinToString("\n"))
            }
        }
    }

    // Variant tasks are not registered yet while onVariants runs, so match lazily.
    //
    // Both outputs, and that is the point. assemble builds the APK that goes to
    // GitHub releases; bundle builds the AAB that goes to Play, and it does not
    // depend on assemble. Guarding only the first left the store upload, the one
    // build whose permission list is read by users as a promise, unchecked.
    tasks.matching { it.name == "assemble$suffix" || it.name == "bundle$suffix" }
        .configureEach { dependsOn(checkPermissions) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.16.0")
    // Zoomable image view that tiles huge bitmaps
    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
    // EXIF orientation for photos opened via SAF content URIs
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Video and audio playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    // 4.16.1 is the newest stable; it is also the first to support SDK 36, which is
    // why the toolchain has to be JDK 21. 4.17 is still in beta.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")

    androidTestImplementation("com.google.truth:truth:1.4.5")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // The WebView matchers: the only way to assert on what a viewer page drew
    androidTestImplementation("androidx.test.espresso:espresso-web:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    // Runs Google's accessibility checks on every Espresso action, which is
    // the same scan Play's pre-launch report applies to the native views
    androidTestImplementation("androidx.test.espresso:espresso-accessibility:3.7.0")
}
