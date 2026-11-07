// What every Java game's adapter needs beyond the core, with no game API: macro and favourite files, the runs
// log, and the deck made safe from any thread. Unlike core and window it may use files and locks.
// Compiled for Java 17, like core, so it runs on Necesse.
tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    "implementation"(project(":conditions"))
    "implementation"(project(":core"))
    "implementation"(project(":window"))
}
