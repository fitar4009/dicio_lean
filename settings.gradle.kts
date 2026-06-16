import me.champeau.gradle.igp.gitRepositories
import org.eclipse.jgit.api.Git
import java.io.FileInputStream
import java.util.Properties

rootProject.name = "Dicio"
include(":app")
include(":skill")
// we use includeBuild here since the plugin is a compile-time dependency
includeBuild("sentences-compiler-plugin")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    fun findInVersionCatalog(versionIdentifier: String): String {
        val regex = "^.*$versionIdentifier *= *\"([^\"]+)\".*$".toRegex()
        return File("gradle/libs.versions.toml")
            .readLines()
            .firstNotNullOf { regex.find(it)?.groupValues?.get(1) }
    }
    id("me.champeau.includegit") version findInVersionCatalog("includegitPlugin")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

data class IncludeGitRepo(
    val name: String,
    val uri: String,
    val projectPath: String,
    val commit: String,
)

fun findInVersionCatalog(versionIdentifier: String): String {
    val regex = "^.*$versionIdentifier *= *\"([^\"]+)\".*$".toRegex()
    return File("gradle/libs.versions.toml")
        .readLines()
        .firstNotNullOf { regex.find(it)?.groupValues?.get(1) }
}

val includeGitRepos: List<IncludeGitRepo> = listOf(
    IncludeGitRepo(
        name = "dicio-numbers",
        uri = "https://github.com/Stypox/dicio-numbers",
        projectPath = ":numbers",
        commit = findInVersionCatalog("dicioNumbers"),
    ),
    IncludeGitRepo(
        name = "dicio-sentences-compiler",
        uri = "https://github.com/Stypox/dicio-sentences-compiler",
        projectPath = ":sentences_compiler",
        commit = findInVersionCatalog("dicioSentencesCompiler"),
    ),
)

private fun parseKeyValuePairs(input: String): Map<String, String> {
    if (input.isBlank()) return mapOf()
    val map = mutableMapOf<String, String>()
    val validKeys = includeGitRepos.map { it.name }.toMutableSet()
    for (pair in input.split(',')) {
        val parts = pair.split(":", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid library specification: $pair")
        else if (parts[0] !in validKeys) throw IllegalArgumentException("Invalid or duplicate library name: ${parts[0]}")
        validKeys.remove(parts[0])
        map[parts[0]] = parts[1]
    }
    return map
}

val localProperties: Properties = Properties().apply {
    try { load(FileInputStream(File(rootDir, "local.properties"))) }
    catch (e: Throwable) { println("Warning: can't read local.properties: $e") }
}
val libsToUseLocally = parseKeyValuePairs(localProperties.getOrDefault("useLocalDicioLibraries", "").toString())

for (repo in includeGitRepos) {
    if (repo.name in libsToUseLocally) {
        includeBuild(libsToUseLocally[repo.name]!!) {
            dependencySubstitution {
                substitute(module("git.included.build:${repo.name}"))
                    .using(project(repo.projectPath))
            }
        }
    } else {
        val file = File("$rootDir/checkouts/${repo.name}")
        if (file.isDirectory) {
            val git = Git.open(file)
            val sameRemote = git.remoteList().call()
                .any { rem -> rem.urIs.any { uri -> uri.toString() == repo.uri } }
            if (sameRemote) git.fetch().call()
            else { println("Git: remote for ${repo.name} changed, deleting"); file.deleteRecursively() }
        }
    }
}

if (libsToUseLocally.size < includeGitRepos.size) {
    gitRepositories {
        for (repo in includeGitRepos) {
            if (repo.name !in libsToUseLocally) {
                include(repo.name) {
                    uri.set(repo.uri)
                    commit.set(repo.commit)
                    autoInclude.set(false)
                    includeBuild("") {
                        dependencySubstitution {
                            substitute(module("git.included.build:${repo.name}"))
                                .using(project(repo.projectPath))
                        }
                    }
                }
            }
        }
    }
}
