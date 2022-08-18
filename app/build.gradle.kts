plugins {
	id("com.android.application")
	kotlin("android")
	kotlin("kapt")
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.aboutlibraries)
}

android {
	namespace = "org.jellyfin.androidtv"
	compileSdk = gradleLocalProperties(rootDir).getProperty("COMPILE_SDK_NR", "32").toInt()
	ndkVersion = "25.0.8775105"

	defaultConfig {
		minSdk = gradleLocalProperties(rootDir).getProperty("MIN_SDK_NR", "23").toInt()
		targetSdk = gradleLocalProperties(rootDir).getProperty("TARGET_SDK_NR", "32").toInt()

		// Release version
		applicationId = namespace
		versionName = project.getVersionName()
		versionCode = getVersionCode(versionName!!)
		setProperty("archivesBaseName", "jellyfin-androidtv-v$versionName")

		ndk.abiFilters.addAll(setOf("armeabi-v7a"))
	}

	sourceSets["main"].java.srcDirs("src/main/kotlin")
	sourceSets["test"].java.srcDirs("src/test/kotlin")

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

//	bundle {
//		abi {
//			enableSplit = true
//		}
//	}

	signingConfigs {
		create("release") {
			storeFile = file(gradleLocalProperties(rootDir).getProperty("STOREFILE"))
			keyAlias = gradleLocalProperties(rootDir).getProperty("KEYALIAS")
			keyPassword = gradleLocalProperties(rootDir).getProperty("KEYPASSWORD")
			storePassword = gradleLocalProperties(rootDir).getProperty("STOREPASSWORD")
		}
	}

	buildTypes {
		getByName("release") {
			matchingFallbacks += listOf()
			signingConfig = signingConfigs.getByName("release")
		}

		val release by getting {
			isMinifyEnabled = false
			isShrinkResources = false
//			isJniDebuggable = false
			// Set package names used in various XML files
			resValue("string", "app_id", namespace!!)
			resValue("string", "app_search_suggest_authority", "${namespace}.content")
			resValue("string", "app_search_suggest_intent_data", "content://${namespace}.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")
		}

		val debug by getting {
			// Use different application id to run release and debug at the same time
			applicationIdSuffix = ".debug"

			// Set package names used in various XML files
			resValue("string", "app_id", namespace + applicationIdSuffix)
			resValue("string", "app_search_suggest_authority", "${namespace + applicationIdSuffix}.content")
			resValue("string", "app_search_suggest_intent_data", "content://${namespace + applicationIdSuffix}.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_debug")

			buildConfigField("boolean", "DEVELOPMENT", (defaultConfig.versionCode!! < 100).toString())
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
		sarifReport = true
		checkDependencies = true
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

aboutLibraries {
	// Remove the "generated" timestamp to allow for reproducible builds
	excludeFields = arrayOf("generated")
}

val versionTxt by tasks.registering {
	val path = buildDir.resolve("version.txt")

	doLast {
		val versionString = "v${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
		logger.info("Writing [$versionString] to $path")
		path.writeText("$versionString\n")
	}
}

dependencies {
	// Jellyfin
	implementation(projects.playback)
	implementation(projects.preference)
	implementation(libs.jellyfin.apiclient)
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.leanback.core)
	implementation(libs.androidx.leanback.preference)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.tvprovider)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.work.runtime)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.window)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.startup)

	// Dependency Injection
	implementation(libs.bundles.koin)

	// GSON
	implementation(libs.gson)

	// Media players
	implementation(libs.exoplayer)
	implementation(libs.jellyfin.exoplayer.ffmpegextension)
	implementation(libs.libvlc)

	// Jcifs
	implementation(libs.jcifs)

	// Markdown
	implementation(libs.bundles.markwon)

	// Image utility
	implementation(libs.glide.core)
	kapt(libs.glide.compiler)
	implementation(libs.kenburnsview)

	// Crash Reporting
	implementation(libs.bundles.acra)

	// Licenses
	implementation(libs.aboutlibraries)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Debugging
	if (getProperty("leakcanary.enable")?.toBoolean() == true)
		debugImplementation(libs.leakcanary)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
