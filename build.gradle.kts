// La versión viene del tag en CI (-PreleaseVersion=1.0.0); en local es 0.1.0-SNAPSHOT.
val releaseVersion: String = (findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java-library")
    group = "com.economycore"
    version = releaseVersion
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
    tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(17) }
}
