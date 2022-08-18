import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
	id("com.android.library")
	kotlin("android")
}

android {
	compileSdk = gradleLocalProperties(rootDir).getProperty("COMPILE_SDK_NR", "32").toInt()
	defaultConfig {
		minSdk = gradleLocalProperties(rootDir).getProperty("MIN_SDK_NR", "23").toInt()
        targetSdk = gradleLocalProperties(rootDir).getProperty("TARGET_SDK_NR", "32").toInt()
    }

    buildFeatures {
		viewBinding = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.valueOf(gradleLocalProperties(rootDir).getProperty("JAVA_VERSION", "VERSION_1_8"))
		targetCompatibility = JavaVersion.valueOf(gradleLocalProperties(rootDir).getProperty("JAVA_VERSION", "VERSION_1_8"))
	}

	kotlinOptions {
		jvmTarget = gradleLocalProperties(rootDir).getProperty("KOTLIN_JVM_TARGET", "1.8")
	}

	kotlin {
		jvmToolchain {
			(this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(gradleLocalProperties(rootDir).getProperty("JAVA_VERSION_NR", "8")))
		}
	}

	sourceSets["main"].java.srcDirs("src/main/kotlin")
	sourceSets["test"].java.srcDirs("src/test/kotlin")

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

dependencies {
	// Kotlin
	implementation(libs.kotlinx.coroutines)

	// Logging
	implementation(libs.timber)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
