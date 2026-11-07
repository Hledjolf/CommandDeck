// The window model, shared by every game: what the command window shows and does, with no game API and
// no toolkit. Each game draws it with its own renderer. Same rules as core (checkAdapterBoundary).
// Compiled for Java 17, like core, so it runs on Necesse.
tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    "implementation"(project(":core"))
}
