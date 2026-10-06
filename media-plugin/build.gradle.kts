import java.util.jar.JarFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kifranei.ampp.media"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xlambdas=class")
} }

val pluginVersion = "1.1.0"
val hostSdk = files(
    rootProject.file("lib/ampp-plugin-api-v1.jar"),
    rootProject.file("lib/ampp-backdrop-host.jar"),
    rootProject.file("lib/ampp-liquid-controls-host.jar"),
)
val hostRendering = listOf(
    "org.jetbrains.compose.foundation:foundation:1.12.0",
    "org.jetbrains.compose.ui:ui:1.12.0",
    "org.jetbrains.compose.ui:ui-graphics:1.12.0",
    "io.github.kyant0:shapes:1.2.1",
    "androidx.lifecycle:lifecycle-runtime:2.9.4",
    "androidx.savedstate:savedstate:1.3.3",
    "org.jetbrains:annotations:26.1.0",
)

dependencies {
    // These SDK and rendering classes are supplied by AM++; never bundle them in code.jar.
    compileOnly(hostSdk)
    hostRendering.forEach { compileOnly(it); testImplementation(it) }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    implementation("io.github.proify.lyricon:provider:0.1.70")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(hostSdk)
}

val dexDirectory = layout.buildDirectory.dir("plugin/dex")
val dex = tasks.register<JavaExec>("pluginDex") {
    dependsOn("bundleReleaseAar")
    mainClass = "com.android.tools.r8.D8"
    doFirst {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val androidJar = listOf("android-37", "android-37.0").map { sdk.resolve("platforms/$it/android.jar") }
            .first { it.isFile }
        classpath = files(sdk.resolve("build-tools/37.0.0/lib/d8.jar"))
        val destination = dexDirectory.get().asFile
        destination.mkdirs()
        // Avoid retaining old secondary DEX files between incremental packages.
        destination.listFiles()?.filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }?.forEach { it.delete() }
        val inputs = mutableListOf<File>()
        fun classes(file: File): File {
            if (file.extension != "aar") return file
            val output = layout.buildDirectory.file("plugin/jars/${file.nameWithoutExtension}.jar").get().asFile
            output.parentFile.mkdirs()
            JarFile(file).use { jar -> jar.getInputStream(jar.getJarEntry("classes.jar")).use { input ->
                output.outputStream().use(input::copyTo)
            } }
            return output
        }
        inputs += classes(layout.buildDirectory.file("outputs/aar/media-plugin-release.aar").get().asFile)
        fun jars(configuration: String) = configurations.getByName(configuration).incoming.artifactView {
            attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
        }.files.files
        inputs += jars("releaseRuntimeClasspath")
        val references = jars("releaseCompileClasspath")
        setArgs(listOf("--release", "--min-api", "28", "--lib", androidJar.path,
            "--output", destination.path) + references.flatMap { listOf("--classpath", it.path) } + inputs.map { it.path })
    }
}
val codeJar = tasks.register<Zip>("pluginCodeJar") {
    dependsOn(dex)
    from(dexDirectory) { include("classes*.dex") }
    archiveFileName = "code.jar"
    destinationDirectory = layout.buildDirectory.dir("plugin/code")
}
tasks.register<Zip>("pluginZip") {
    dependsOn(codeJar)
    from(codeJar.flatMap { it.archiveFile })
    from("plugin.json")
    from("src/main/assets") { into("assets") }
    from(rootProject.file("LICENSE")) { into("assets/licenses"); rename { "AMpp-GPL-3.0.txt" } }
    archiveFileName = "ampp-media-integrations-${pluginVersion}.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
}
