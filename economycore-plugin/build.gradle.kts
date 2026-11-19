import org.gradle.api.tasks.SourceSetContainer

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT") // solo API Bukkit => compatible con Spigot
    implementation(project(":economycore-core"))
}

// JAR único e instalable: clases del plugin + api + core (sin dependencias externas que empaquetar).
val embedded = listOf(":economycore-api", ":economycore-core")
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("plugin.yml") { expand("version" to project.version.toString()) }
}
tasks.jar {
    archiveBaseName.set("EconomyCore")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    embedded.forEach { dependsOn("$it:classes") }
    from({ embedded.map { project(it).the<SourceSetContainer>()["main"].output } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
