rootProject.name = "CommandDeck"

include("core")
include("adapters:hytale")

// Test fixtures: small plugins the headless scenarios run against. Never shipped. Each fixtures/<name> with a
// build file is the project :fixture-<name>.
file("fixtures").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":fixture-${it.name}")
    project(":fixture-${it.name}").projectDir = it
}
