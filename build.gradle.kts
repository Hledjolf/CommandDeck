// CommandDeck: a game-agnostic core plus one adapter per game.
// core/ is plain Java compiled for Java 17 (shareable with Necesse); adapters/hytale/ is the Hytale plugin on Java 25.
plugins { base }

// The dev build is <releaseVersion>-dev. `gradlew release` builds <releaseVersion> itself (see "release" below).
val releaseVersion = "1.2.0"
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
// plain (no reflection), so it translates to the C#, C++ and Lua cores. Its main code also keeps to the
// allow-list (CD-D018): commanddeck.*, java.lang, java.util and java.util.function, but not java.util's
// concurrent, regex or Random; and no regular expressions through String (matches, split, replaceAll).
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if any file under core/ or window/ uses a game API, reflection or a class off the allow-list."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") } +
        fileTree("window") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val banned = listOf("com.hypixel", "java.lang.reflect", "getDeclaredField", "getDeclaredMethod", "Class.forName")
        val offenders = sources.files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        val main = (fileTree("core/src/main") { include("**/*.java") } + fileTree("window/src/main") { include("**/*.java") }).files
        val outside = Regex("""\bjava\.(?!util\.|lang\.)[a-z]+|\bjavax?\.util\.(concurrent|regex)\b|\bjava\.util\.(Random|Scanner)\b""")
        val importLine = Regex("""^import\s+(static\s+)?([\w.]+)""")
        val regexCalls = Regex("""\.(matches|split|replaceAll|replaceFirst)\(|\bPattern\.""")
        val allowed = main.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val imp = importLine.find(l.trim())?.groupValues?.get(2)
                (imp != null && !imp.startsWith("commanddeck.") && !imp.startsWith("java.util.") && !imp.startsWith("java.lang."))
                    || outside.containsMatchIn(l) || regexCalls.containsMatchIn(l)
            }.map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        val all = offenders + allowed
        if (all.isNotEmpty()) throw GradleException("Game API, reflection or a class off the allow-list in core:\n" + all.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkNoPrivateRefs: the published files (the allow-list in .gitignore) must not name the private design
// material. The words are assembled from parts so this file does not match itself.
// ---------------------------------------------------------------------------------------------------------
val checkNoPrivateRefs = tasks.register("checkNoPrivateRefs") {
    group = "verification"
    description = "Fails if a published file names the private specs, tools or design notes."
    val roots = listOf("conditions/src/main", "core/src/main", "window/src/main", "jvm/src/main", "adapters/hytale/src/main", "adapters/necesse/src/main", "gradle", "template")
    val singles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "conditions/build.gradle.kts", "core/build.gradle.kts",
        "window/build.gradle.kts", "jvm/build.gradle.kts", "adapters/hytale/build.gradle.kts", "adapters/necesse/build.gradle.kts", ".gitignore", ".gitattributes", "LICENSE", "CHANGELOG.md", "README.md")
    doLast {
        val words = listOf("spe" + "cs/", "spe" + "cs ", "spe" + "c:", "SPEC" + "-", "Q-" + "0", "confor" + "mance", "pro" + "be",
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
// documents, the settings sidecar, and the shared condition library (hledjolf.conditions). No Hytale classes,
// no sources, no test or fixture classes.
// ---------------------------------------------------------------------------------------------------------
val hytaleJar = project(":adapters:hytale").tasks.named<Jar>("jar")
val checkJarContents = tasks.register("checkJarContents") {
    group = "verification"
    description = "Fails if the plugin jar contains Hytale classes, source files or anything outside the allow list."
    dependsOn(hytaleJar)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    doLast {
        val allowed = Regex("""META-INF/.*|manifest\.json|LICENSE|commanddeck/(api|command|deck|json|macro|run|window|jvm|hytale|hytale/window)/[A-Za-z0-9_$]+\.class|hledjolf/conditions/(api/)?[A-Za-z0-9_$]+\.class|hledjolf/conditions/shared-types\.json|commanddeck/commands\.json|Common/UI/Custom/CommandDeck/[A-Za-z]+\.ui|Settings/Hledjolf_CommandDeck\.settings\.json""")
        val names = java.util.zip.ZipFile(jarFile.get().asFile).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        // A local build setup may allow more (for example files only its own builds carry), as a regex in extra.
        val extraAllowed = (rootProject.extra.properties["jarExtraAllowed"] as String?)?.let { Regex(it) }
        val bad = names.filter { !allowed.matches(it) && extraAllowed?.matches(it) != true }
        if (bad.isNotEmpty()) throw GradleException("Unexpected files in the jar:\n" + bad.joinToString("\n") { " - $it" })
        val required = listOf("manifest.json", "commanddeck/hytale/CommandDeckPlugin.class", "commanddeck/api/Deck.class",
            "commanddeck/deck/DeckService.class", "hledjolf/conditions/api/Conditions.class", "hledjolf/conditions/shared-types.json")
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
                "commanddeck/macro/", "commanddeck/run/", "commanddeck/window/", "commanddeck/jvm/", "hledjolf/conditions/")
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

// ---------------------------------------------------------------------------------------------------------
// releaseRecords: release.json (what the release is, implements and where each file goes) and catalog.json
// (every id CommandDeck defines for others), generated from the build, never by hand. In a release build they go
// into release/<version>/; in a dev build into build/release-records/, to check them. The tests fields start as
// "pending" and are filled in when the candidate's tests and playtest have passed.
// ---------------------------------------------------------------------------------------------------------
val releaseRecords = tasks.register("releaseRecords") {
    group = "distribution"
    description = "Writes release.json and catalog.json for this version."
    dependsOn(hytaleJar, releaseZip)
    val jarFile = hytaleJar.flatMap { it.archiveFile }
    val zipFile = releaseZip.flatMap { it.archiveFile }
    val isRelease = !version.toString().endsWith("-dev")
    val outDir = if (isRelease) releaseDir.asFile else layout.buildDirectory.dir("release-records").get().asFile
    val v = version.toString()
    doLast {
        fun sha256(f: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun list(items: Collection<String>) = items.joinToString(", ", "[", "]") { q(it) }
        val commit = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
        val built = java.time.OffsetDateTime.now().withNano(0).toString()
        val jar = jarFile.get().asFile
        val zip = zipFile.get().asFile
        outDir.mkdirs()
        File(outDir, "release.json").writeText("""{
  "mod": "CommandDeck",
  "version": ${q(v)},
  "game": "hytale",
  "games": ["hytale"],
  "game_versions": ["0.7.6"],
  "kind": "feature",
  "built": ${q(built)},
  "commit": ${q(commit)},
  "tests": {"unit": "pending", "scenarios": "pending", "playtest": "pending", "linkcheck": "pending"},
  "changelog": "CHANGES.md",
  "implements": {"commanddeck.integration": "1.2", "commanddeck.sidecar": "1.1", "commanddeck.macro": "1.2",
                 "commanddeck.conditions": "1.0", "commanddeck.conditions-api": "1.0"},
  "artifacts": {
    "github": [${q(jar.name)}, ${q(zip.name)}],
    "curseforge": ${q(jar.name)},
    "nexus": ${q(zip.name)}
  },
  "sha256": {${q(jar.name)}: ${q(sha256(jar))}, ${q(zip.name)}: ${q(sha256(zip))}}
}
""")
        // The ids, from what the jar carries: /deck's subcommands, the permission nodes, the presets of CommandDeck's
        // own sidecar, and the shared condition types.
        val deckSrc = file("adapters/hytale/src/main/java/commanddeck/hytale/DeckCommand.java").readText()
        val subs = Regex("""case "([a-z]+)":""").findAll(deckSrc).map { "deck " + it.groupValues[1] }.toSortedSet()
        val gameSrc = file("adapters/hytale/src/main/java/commanddeck/hytale/HytaleGame.java").readText()
        val perms = Regex("""static final String [A-Z_]+ = "(commanddeck\.[a-z_.]+)";""").findAll(gameSrc).map { it.groupValues[1] }.toSortedSet()
        @Suppress("UNCHECKED_CAST")
        val sidecar = groovy.json.JsonSlurper().parse(file("adapters/hytale/src/main/resources/commanddeck/commands.json")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val presets = (sidecar["presets"] as List<Map<String, Any?>>).map { "Hledjolf:CommandDeck/" + it["id"] }.sorted()
        @Suppress("UNCHECKED_CAST")
        val shared = groovy.json.JsonSlurper().parse(file("conditions/src/main/resources/hledjolf/conditions/shared-types.json")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val conditions = (shared["\$defs"] as Map<String, Any?>).keys.filter { it != "condition" && it != "namespaced" }
        File(outDir, "catalog.json").writeText("""{ "mod": "CommandDeck", "version": ${q(v)},
  "ids": {
    "command": ${list(listOf("deck") + subs)},
    "preset": ${list(presets)},
    "permission": ${list(perms)},
    "condition": ${list(conditions)} } }
""")
        println("Wrote release.json and catalog.json to $outDir")
    }
}

tasks.register<Copy>("releaseFiles") {
    group = "distribution"
    description = "Copies the checked release jar to release/<version>/ and makes the zip. Run through gradlew release."
    dependsOn(checkReleaseJar, releaseZip, releaseRecords)
    mustRunAfter("check")
    from(hytaleJar)
    into(releaseDir)
}
