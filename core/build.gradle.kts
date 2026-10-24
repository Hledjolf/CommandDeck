// Game-agnostic plain Java: the command model, sidecar merge, macro validation and runner, placeholders,
// the line builder algorithm and the integration API. No game API is ever on this project's classpath.
// Compiled for Java 17 so the same jar can run on Necesse.
tasks.withType<JavaCompile> { options.release.set(17) }
