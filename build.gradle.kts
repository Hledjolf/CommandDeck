// CommandDeck: a game-agnostic core plus one adapter per game.
// core/ is plain Java compiled for Java 17 (shareable with Necesse); adapters/hytale/ is the Hytale plugin on Java 25.
plugins { base }

// The dev build is <releaseVersion>-dev. `gradlew release` builds <releaseVersion> itself (see "release" below).
val releaseVersion = "1.0.0"
version = if (hasProperty("releaseBuild")) releaseVersion else "$releaseVersion-dev"

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// Local development setup (test data and tooling) lives outside the published files.
if (file("dev.gradle.kts").exists()) apply(from = "dev.gradle.kts")

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: core/ is game-agnostic. No file under core/ may name a game API, and core/ stays
// plain (no reflection), so it translates to the C#, C++ and Lua cores.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if any file under core/ uses a game API or reflection."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val banned = listOf("com.hypixel", "java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "Class.forName")
        val offenders = sources.files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        if (offenders.isNotEmpty()) throw GradleException("Game API or reflection in core:\n" + offenders.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkNoPrivateRefs: the published files (the allow-list in .gitignore) must not name the private design
// material. The words are assembled from parts so this file does not match itself.
// ---------------------------------------------------------------------------------------------------------
val checkNoPrivateRefs = tasks.register("checkNoPrivateRefs") {
    group = "verification"
    description = "Fails if a published file names the private specs, tools or design notes."
    val roots = listOf("core/src/main", "adapters/hytale/src/main", "gradle", "template")
    val singles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "core/build.gradle.kts",
        "adapters/hytale/build.gradle.kts", ".gitignore", ".gitattributes", "LICENSE", "CHANGELOG.md", "README.md")
    doLast {
        val words = listOf("spe" + "cs/", "spe" + "c:", "SPEC" + "-", "Q-" + "0", "confor" + "mance", "pro" + "be",
            "registry-" + "findings", "macro" + ".md", "integration" + ".md", "command-sidecar" + ".md",
            "conditions" + ".md", "test-plan" + ".md", "macro" + ".schema", "conditions" + ".schema", "test-plan" + ".schema",
            "integration" + ".schema")
        val textExt = setOf("java", "json", "md", "txt", "kts", "properties", "ui", "")
        val files = singles.map { File(projectDir, it) }.filter { it.isFile && it.name != ".gitignore" } +
            roots.flatMap { r -> File(projectDir, r).walkTopDown().filter { it.isFile && it.extension.lowercase() in textExt }.toList() }
        val hits = files.flatMap { f ->
            f.readLines(Charsets.UTF_8).withIndex().filter { (_, l) -> words.any { l.contains(it, ignoreCase = true) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir).invariantSeparatorsPath}:${i + 1}: ${l.trim()}" }
        }
        if (hits.isNotEmpty()) throw GradleException("Published files name private material:\n" + hits.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkJarContents: the plugin jar holds only CommandDeck's own classes, the manifest, the window's .ui
// documents and the settings sidecar. No Hytale classes,
// no sources, no test or fixture classes.
// ---------------------------------------------------------------------------------------------------------
val hytaleJar = project(":adapters:hytale").tasks.named<Jar>("jar")
val checkJarContents = tasks.register("checkJarContents") {
    group = "verification"
    description = "Fails if the plugin jar contains Hytale classes, source files or anything outside the allow list."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|commanddeck/(api|command|deck|json|macro|run|hytale|hytale/window)/[A-Za-z0-9_$]+\.class|Common/UI/Custom/CommandDeck/[A-Za-z]+\.ui|Settings/Hledjolf_CommandDeck\.settings\.json""")
        val names = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        // A local build setup may allow more (for example files only its own builds carry), as a regex in extra.
        val extraAllowed = (rootProject.extra.properties["jarExtraAllowed"] as String?)?.let { Regex(it) }
        val bad = names.filter { !allowed.matches(it) && extraAllowed?.matches(it) != true }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
        val required = listOf("manifest.json", "commanddeck/hytale/CommandDeckPlugin.class", "commanddeck/api/Deck.class",
            "commanddeck/deck/DeckService.class")
        required.filter { it !in names }.forEach { throw GradleException("The jar is missing $it") }
    }
}

tasks.named("check") {
    dependsOn(subprojects.filter { !it.name.startsWith("fixture-") }.map { it.tasks.named("check") })
    dependsOn(checkJarContents)
    dependsOn(checkAdapterBoundary)
    dependsOn(checkNoPrivateRefs)
}

// ---------------------------------------------------------------------------------------------------------
// release: `gradlew release` runs the whole build again as version <releaseVersion> (no -dev), runs check on
// it, verifies the jar, and copies the upload files into release/<version>/: the jar (CurseForge) and a zip
// with the jar, README.md, LICENSE and CHANGELOG.md (Nexus). The normal build stays <releaseVersion>-dev.
// ---------------------------------------------------------------------------------------------------------
tasks.register<GradleBuild>("release") {
    group = "distribution"
    description = "Builds and checks version $releaseVersion and copies the jar and the zip to release/$releaseVersion/."
    dir = projectDir
    tasks = listOf("check", "releaseFiles")
    startParameter.projectProperties = gradle.startParameter.projectProperties + mapOf("releaseBuild" to "true")
}

val releaseDir = layout.projectDirectory.dir("release/$releaseVersion")

// checkReleaseJar: the jar says the release version, carries the license, and has nothing beyond the
// published allow list. The core's classes are Java 17 (major 61); the adapter's are the game's Java 25 (69).
val checkReleaseJar = tasks.register("checkReleaseJar") {
    group = "verification"
    description = "Fails if the release jar has the wrong version, no LICENSE, extra entries or the wrong bytecode level."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val expected = version.toString()
    doLast {
        if (expected.endsWith("-dev")) throw GradleException("checkReleaseJar runs only in a release build (gradlew release)")
        java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            val names = zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            if ("LICENSE" !in names) throw GradleException("The release jar has no LICENSE")
            val extra = names.filter { it.startsWith("playtest/") || it.startsWith("fixture") }
            if (extra.isNotEmpty()) throw GradleException("The release jar has development files:\n" + extra.joinToString("\n") { " - $it" })
            val manifest = zf.getInputStream(zf.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            if (!Regex(""""Version"\s*:\s*"${Regex.escape(expected)}"""").containsMatchIn(manifest)) {
                throw GradleException("manifest.json does not say Version $expected")
            }
            val coreDirs = listOf("commanddeck/api/", "commanddeck/command/", "commanddeck/deck/", "commanddeck/json/",
                "commanddeck/macro/", "commanddeck/run/")
            names.filter { it.endsWith(".class") }.forEach { n ->
                val b = zf.getInputStream(zf.getEntry(n)).use { it.readNBytes(8) }
                val major = ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
                val want = if (coreDirs.any { n.startsWith(it) }) 61 else 69
                if (major != want) throw GradleException("$n is class file version $major, expected $want")
            }
        }
    }
}

val releaseZip = tasks.register<Zip>("releaseZip") {
    dependsOn(checkReleaseJar)
    archiveFileName.set("CommandDeck-$version.zip")
    destinationDirectory.set(releaseDir)
    from(hytaleJar)
    from(layout.projectDirectory) { include("README.md", "LICENSE", "CHANGELOG.md") }
}

tasks.register<Copy>("releaseFiles") {
    group = "distribution"
    description = "Copies the checked release jar to release/<version>/ and makes the zip. Run through gradlew release."
    dependsOn(checkReleaseJar, releaseZip)
    mustRunAfter("check")
    from(hytaleJar)
    into(releaseDir)
}
