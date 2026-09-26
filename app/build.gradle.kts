import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.pitest)
    jacoco
}

android {
    namespace = "com.glyphrank.dota"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glyphrank.dota"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // ./gradlew createDebugUnitTestCoverageReport -> app/build/reports/coverage/test/debug/
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // android.util.Log etc. return defaults in JVM tests instead of throwing "Stub!".
        unitTests.isReturnDefaultValues = true
        // Robolectric tests use the real layouts, assets and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Nothing's Glyph Matrix SDK. Its licence forbids redistribution, so it is not
    // included here: download it into app/libs/ (see app/libs/README.md).
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
}

// Count Robolectric tests in the coverage report: their app classes come from Robolectric's
// class loader, which JaCoCo skips unless it includes classes without a location.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// Part of ./gradlew check: fails when coverage drops. The rules and data (data, glyph, rank)
// must stay almost fully tested; the Android classes count only towards the total.
val verifyDebugCoverage by tasks.registering(JacocoCoverageVerification::class) {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    sourceDirectories.setFrom("src/main/java")
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.75".toBigDecimal() }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.glyphrank.dota.data", "com.glyphrank.dota.glyph", "com.glyphrank.dota.rank")
            limit { counter = "LINE"; minimum = "0.93".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.78".toBigDecimal() }
        }
    }
}
tasks.named("check") { dependsOn(verifyDebugCoverage) }

// Mutation testing (./gradlew pitestDebug, a few minutes): PIT changes the code, e.g. flips a
// condition, and checks that a test fails. Report: app/build/reports/pitest/debug/index.html.
// Only the plain JVM tests run; the Robolectric ones are too slow to repeat for every mutation.
pitest {
    targetClasses.set(
        listOf(
            "com.glyphrank.dota.data.*", "com.glyphrank.dota.glyph.*", "com.glyphrank.dota.rank.*",
            "com.glyphrank.dota.toy.ToyController*", "com.glyphrank.dota.widget.JobFetch*",
            "com.glyphrank.dota.widget.WidgetContent*",
        ),
    )
    // Android glue, only reachable from Robolectric tests.
    excludedClasses.set(listOf("com.glyphrank.dota.data.LauncherIcon", "com.glyphrank.dota.data.BundledMedals"))
    excludedTestClasses.set(
        listOf(
            "com.glyphrank.dota.ui.MainActivityTest", "com.glyphrank.dota.ui.MatrixPainterTest",
            "com.glyphrank.dota.ui.RecentAccountsPopupTest", "com.glyphrank.dota.widget.RankWidgetTest",
            "com.glyphrank.dota.widget.RankRefreshJobTest", "com.glyphrank.dota.data.LauncherIconUpdateTest",
            "com.glyphrank.dota.toy.DotaRankToyServiceTest", "com.glyphrank.dota.docs.*",
        ),
    )
    threads.set(Runtime.getRuntime().availableProcessors())
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
}
