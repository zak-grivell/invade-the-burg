import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// Auto-increment version on each build (only once, at task execution time)
val baseProps = Properties().apply {
    File("gradle.properties").inputStream().use { load(it) }
}
val baseVersion = baseProps.getProperty("version", "1.0.0")
val parts = baseVersion.split(".")
val computedNewVersion = "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}"

// Increment version as the very first step of any build
tasks.register("incrementVersion") {
    doLast {
        val vf = File("gradle.properties")
        val p = Properties().apply { vf.inputStream().use { load(it) } }
        val current = p.getProperty("version", baseVersion)
        if (current == baseVersion) {
            p.setProperty("version", computedNewVersion)
            vf.outputStream().use { p.store(it, null) }
        }
    }
}
tasks.named("initializeIntellijPlatformPlugin").configure { dependsOn("incrementVersion") }
tasks.named("build").configure { dependsOn("buildPlugin") }

val mediaSmokeRuntime = configurations.create("mediaSmokeRuntime")
dependencies {
    add(mediaSmokeRuntime.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("ca.weblite:webview:1.13.0")
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        val localPlatform = providers.gradleProperty("localPlatformPath").orNull
        if (localPlatform != null) local(localPlatform) else intellijIdea("2026.3")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = baseVersion
        ideaVersion { sinceBuild = "263"; untilBuild = "263.*" }
    }
}

kotlin { jvmToolchain(25) }

// Generate reelbreak.properties with the version for the overlay to read
tasks.processResources {
    doLast {
        val propsFile = File(destinationDirectory.get().asFile, "reelbreak.properties")
        propsFile.parentFile?.mkdirs()
        val p = Properties()
        p.setProperty("version", baseVersion)
        propsFile.outputStream().use { p.store(it, "Auto-generated — do not edit") }
    }
}

// Opt-in integration check of the actual OS browser and decoder, outside the IDE process.
tasks.register<JavaExec>("smokeTestMedia") {
    group = "verification"
    description = "Verify native H.264 playback and page retention using a local test video"
    dependsOn(tasks.testClasses)
    // Browser focus integration uses platform classes supplied by the IDE in production.
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath + mediaSmokeRuntime
    mainClass = "com.github.zakgrivell.invadetheburg.NativeMediaSmoke"
    systemProperty("java.awt.headless", "false")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("smokeTestRecapVideo") {
    group = "verification"
    description = "Verify bundled footage and native narration autoplay, visibility and completion"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath + mediaSmokeRuntime
    mainClass = "com.github.zakgrivell.invadetheburg.RecapVideoSmoke"
    systemProperty("java.awt.headless", "false")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("evaluateEta") {
    group = "verification"
    description = "Evaluate ETA baselines on session JSONL logs in -Peta.logDir, plus an optional seed file -Peta.seed"
    dependsOn(tasks.testClasses)
    classpath = tasks.named<Test>("test").get().classpath
    mainClass = "com.github.zakgrivell.invadetheburg.eta.EtaEvaluationMain"
    args(providers.gradleProperty("eta.logDir").orElse("src/test/resources/eta").get())
    providers.gradleProperty("eta.seed").orNull?.let { args(it) }
}
