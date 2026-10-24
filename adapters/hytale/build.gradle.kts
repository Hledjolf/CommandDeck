// The Hytale plugin. The server jar is the API: compile-only, never bundled.
// The game-agnostic core is merged into the plugin jar, so server owners install a single file.
val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"

dependencies {
    "implementation"(project(":core"))
    "compileOnly"(files(hytaleServerJar))
    "testImplementation"(files(hytaleServerJar))
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", rootProject.version.toString())
    filesMatching("manifest.json") { expand("version" to rootProject.version.toString()) }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("CommandDeck")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    if (rootProject.file("LICENSE").exists()) from(rootProject.file("LICENSE"))
}

// Server classes such as AbstractCommand need the game's log manager before they load, as on a real server.
tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
}
