import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val publishing = Properties().apply {
    rootProject.file("publishing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun publishingText(name: String) = publishing.getProperty(name, "").trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
val signingProperties = Properties().apply {
    rootProject.file(providers.gradleProperty("stbplay.signingPropertiesFile").getOrElse("keystore.properties")).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}


android {
    namespace = "com.stbplay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stbplay.app"
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 22
        versionName = "0.6.8"
        buildConfigField("String", "PUBLISHER_NAME", quoted(publishingText("publisherName")))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(publishingText("supportEmail")))
        buildConfigField("String", "PRIVACY_POLICY_URL", quoted(publishingText("privacyPolicyUrl")))
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            if (signingProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true; checkReleaseBuilds = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

ksp {
    // Generate Kotlin implementations to preserve suspend function signatures.
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    
    // Room
    // Keep the database compiler and runtime aligned.
    val room_version = "2.7.2"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
    
    // TV system media controls and provider HTTP transport.
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}

val verifyReleaseMetadata = tasks.register("verifyReleaseMetadata") {
    group = "publishing"
    doLast {
        require(publishingText("publisherName").isNotBlank()) { "Set publisherName in publishing.properties." }
        require(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(publishingText("supportEmail"))) { "Set a valid supportEmail in publishing.properties." }
        val inAppUrl = publishingText("privacyPolicyUrl")
        if (inAppUrl.isNotBlank()) {
            val uri = URI(inAppUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Optional in-app privacy URL must be HTTPS." }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseMetadata) }

val verifyPlayPublishing = tasks.register("verifyPlayPublishing") {
    group = "publishing"
    description = "Pre-submission checks; the public policy URL is separate from the offline in-app policy."
    dependsOn(verifyReleaseMetadata)
    doLast {
        val uri = runCatching { URI(publishingText("publicPrivacyPolicyUrl")) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Host publishing/privacy-site on GitHub Pages, then set publicPrivacyPolicyUrl for this check. Leave privacyPolicyUrl empty to keep the app offline-only." }
        logger.warn("Verify that the public URL is live and that Data safety matches the submitted artifact. URL syntax alone does not verify hosting.")
        logger.warn("HTTP compatibility remains enabled. Sensitive provider data sent over HTTP is an unresolved Play secure-transmission concern; disclosure alone does not fix it.")
    }
}

tasks.register("exportPrivacyPolicy") {
    group = "publishing"
    doLast {
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val text = file("src/main/assets/privacy_policy.txt").readText()
            .replace("{{publisherName}}", publishingText("publisherName"))
            .replace("{{supportEmail}}", publishingText("supportEmail"))
        val template = rootProject.file("tools/privacy-template.html").readText()
        val rendered = template.replace("{{policyBody}}", escape(text.substringAfter("\n")))
        rootProject.file("publishing/privacy-template.html").writeText(rendered)
        listOf("index.html", "privacy-policy.html").forEach { name ->
            val out = rootProject.file("publishing/privacy-site/$name")
            out.parentFile.mkdirs(); out.writeText(rendered)
        }
    }
}
