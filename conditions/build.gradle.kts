// The shared condition library: its API (hledjolf.conditions.api) and the library itself (hledjolf.conditions).
// Plain Java with no CommandDeck classes, so it can move into a mod of its own by changing one dependency line in
// each consumer. Games answer the shared types through the GameFacts port. Compiled for Java 17, like core.
tasks.withType<JavaCompile> { options.release.set(17) }
