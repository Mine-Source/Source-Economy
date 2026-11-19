dependencies { api(project(":economycore-api")) }
// Las pruebas son un runner propio (sin JUnit) para no requerir dependencias.
tasks.register<JavaExec>("runTests") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.economycore.core.TestRunner")
}
tasks.named("check") { dependsOn("runTests") }
