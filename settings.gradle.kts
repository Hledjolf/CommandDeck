rootProject.name = "CommandDeck"

include("conditions")
include("core")
include("window")
include("jvm")
include("adapters:hytale")
// Necesse ships from 1.3; the public export doesn't carry its adapter yet.
if (file("adapters/necesse").isDirectory) include("adapters:necesse")

// Test fixtures: small plugins the headless scenarios run against. Never shipped. Each fixtures/<name> with a
// build file is the project :fixture-<name>.
file("fixtures").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":fixture-${it.name}")
    project(":fixture-${it.name}").projectDir = it
}
