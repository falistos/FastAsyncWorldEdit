import buildlogic.getLibrary
import buildlogic.stringyLibs
import com.sun.source.tree.NewClassTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import org.gradle.api.tasks.PathSensitivity
import org.gradle.plugins.ide.idea.model.IdeaModel
import java.net.URI
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

group = rootProject.group
version = rootProject.version

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(1, TimeUnit.DAYS)
    }
}

plugins.withId("java") {
    the<JavaPluginExtension>().toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    for (conf in listOf("implementation", "api")) {
        if (!configurations.names.contains(conf)) {
            continue
        }
        add(conf, platform(stringyLibs.getLibrary("log4j-bom")).map {
            val dep = create(it)
            dep.because("Mojang provides Log4j")
            dep
        })
        constraints {
            add(conf, stringyLibs.getLibrary("guava")) {
                version { require("33.3.1-jre") }
                because("Mojang provides Guava")
            }
            add(conf, stringyLibs.getLibrary("gson")) {
                version { require("2.11.0") }
                because("Mojang provides Gson")
            }
            add(conf, stringyLibs.getLibrary("fastutil")) {
                version { require("8.5.15") }
                because("Mojang provides FastUtil")
            }
        }
    }
}

plugins.withId("idea") {
    configure<IdeaModel> {
        module {
            isDownloadSources = true
            isDownloadJavadoc = true
        }
    }
}

val c5ProductionDirectories = when (project.path) {
    ":worldedit-core" -> listOf(file("src/main/java"), file("src/legacy/java"))
    ":worldedit-bukkit" -> listOf(file("src/main/java"))
    ":worldedit-bukkit:adapters:adapter-26.1" -> listOf(file("src/main/java"))
    ":worldedit-bukkit:folia" -> listOf(file("src/main/java"))
    else -> emptyList()
}

if (c5ProductionDirectories.isNotEmpty()) {
    val c5ProductionSources = files(c5ProductionDirectories.map { directory ->
        fileTree(directory) { include("**/*.java") }
    })
    val c5Patterns = listOf(
        "Fawe.isMainThread()" to Regex(
            """\bFawe\s*(?:\.\s*isMainThread\s*\(|::\s*isMainThread\b)"""
        ),
        "static Fawe main-thread import" to Regex(
            """\bimport\s+static\s+com\.fastasyncworldedit\.core\.Fawe\s*\.\s*(?:isMainThread|\*)\s*;"""
        ),
        "static raw platform predicate import" to Regex(
            """\bimport\s+static\s+[^;]*(?:Bukkit|TickThread|MCUtil|MinecraftServer)\s*\.\s*"""
                    + """(?:isPrimaryThread|isOwnedByCurrentRegion|isTickThread(?:For)?|"""
                    + """isGlobalTickThread|isMainThread|isSameThread|\*)\s*;"""
        ),
        "platform isPrimaryThread()" to Regex(
            """(?:\.\s*isPrimaryThread\s*\(|::\s*isPrimaryThread\b)"""
        ),
        "MinecraftServer.isSameThread equivalent" to Regex(
            """(?:\.\s*isSameThread\s*\(|::\s*isSameThread\b)"""
        ),
        "raw platform tick-thread predicate" to Regex(
            """\b(?:TickThread|MCUtil)\s*(?:\.\s*(?:isTickThread(?:For)?|isGlobalTickThread|isMainThread)\s*\("""
                    + """|::\s*(?:isTickThread(?:For)?|isGlobalTickThread|isMainThread)\b)"""
        ),
        "raw platform ownership predicate" to Regex(
            """\bBukkit\s*(?:\.\s*isOwnedByCurrentRegion\s*\(|::\s*isOwnedByCurrentRegion\b)"""
        ),
        "direct server-thread identity comparison" to Regex(
            """(?:(?:instance\s*\.\s*thread|(?:server|main|tick)Thread|get(?:Main|Running)Thread\s*\(\s*\))"""
                    + """[^;\n]{0,160}(?:==|!=)\s*Thread\s*\.\s*currentThread\s*\(\s*\)"""
                    + """|Thread\s*\.\s*currentThread\s*\(\s*\)\s*(?:==|!=)[^;\n]{0,160}"""
                    + """(?:instance\s*\.\s*thread|(?:server|main|tick)Thread|get(?:Main|Running)Thread\s*\(\s*\)))"""
        )
    )
    val c5ApprovedBackends = setOf(
        ":worldedit-bukkit:src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java",
        ":worldedit-bukkit:folia:src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaThreadContext.java"
    )
    val c5LegacyLabels = setOf(
        "Fawe.isMainThread()",
        "static Fawe main-thread import"
    )
    val c5Allowances = mapOf(
        ":worldedit-core:src/main/java/com/fastasyncworldedit/core/Fawe.java"
                + "|direct server-thread identity comparison|"
                + "return instance == null || instance.thread == Thread.currentThread();" to 1,
        ":worldedit-bukkit:src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java"
                + "|Fawe.isMainThread()|return Fawe.isMainThread();" to 1
    )
    val c5Stamp = layout.buildDirectory.file("c5/checkC5ThreadContextReferences.stamp")
    val c5ProjectDirectory = projectDir
    val c5ProjectPath = path

    val checkC5ThreadContextReferences = tasks.register("checkC5ThreadContextReferences") {
        group = "verification"
        description = "Reject new legacy or raw ownership-sensitive thread predicates."
        inputs.property("c5GuardVersion", 6)
        inputs.files(c5ProductionSources).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(c5Stamp)
        outputs.cacheIf { true }

        doLast {
            val allowanceUse = mutableMapOf<String, Int>()
            val violations = mutableListOf<String>()

            fun inspectMatch(sourceKey: String, label: String, content: String, offset: Int) {
                if (sourceKey in c5ApprovedBackends && label !in c5LegacyLabels) {
                    return
                }
                val lineNumber = content.take(offset).count { it == '\n' } + 1
                val lineText = content.lineSequence().drop(lineNumber - 1).first().trim()
                val allowanceKey = "$sourceKey|$label|$lineText"
                val used = (allowanceUse[allowanceKey] ?: 0) + 1
                allowanceUse[allowanceKey] = used
                if (used <= (c5Allowances[allowanceKey] ?: 0)) {
                    return
                }
                violations += "$sourceKey:$lineNumber: forbidden $label"
            }

            fun untargetedLazyConstructionOffsets(content: String): List<Int> {
                if (!content.contains("LazyBaseEntity")) {
                    return emptyList()
                }
                val compiler = ToolProvider.getSystemJavaCompiler()
                        ?: throw GradleException("C5 requires a JDK compiler for Java source inspection")
                val sourceObject = object : SimpleJavaFileObject(
                    URI.create("string:///C5Guard.java"),
                    JavaFileObject.Kind.SOURCE
                ) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = content
                }
                val task = compiler.getTask(
                    null,
                    null,
                    null,
                    listOf("-proc:none"),
                    null,
                    listOf(sourceObject)
                ) as JavacTask
                val offsets = mutableListOf<Int>()
                val trees = Trees.instance(task)
                task.parse().forEach { unit ->
                    object : TreeScanner<Void?, Void?>() {
                        override fun visitNewClass(node: NewClassTree, unused: Void?): Void? {
                            if (node.identifier.toString().substringAfterLast('.') == "LazyBaseEntity"
                                && node.arguments.size == 2
                            ) {
                                val offset = trees.sourcePositions.getStartPosition(unit, node)
                                if (offset >= 0) {
                                    offsets += offset.toInt()
                                }
                            }
                            return super.visitNewClass(node, unused)
                        }
                    }.scan(unit, null)
                }
                return offsets
            }

            c5ProductionSources.files.filter { it.isFile }.sortedBy { it.path }.forEach { source ->
                val sourceKey = "$c5ProjectPath:${source.relativeTo(c5ProjectDirectory).invariantSeparatorsPath}"
                val content = source.readText()
                c5Patterns.forEach { (label, pattern) ->
                    pattern.findAll(content).forEach { match ->
                        inspectMatch(sourceKey, label, content, match.range.first)
                    }
                }
                untargetedLazyConstructionOffsets(content).forEach { offset ->
                    val lineNumber = content.take(offset).count { it == '\n' } + 1
                    violations += "$sourceKey:$lineNumber: forbidden untargeted lazy entity construction"
                }
                if (sourceKey == ":worldedit-core:src/main/java/com/fastasyncworldedit/core/Fawe.java") {
                    Regex("""(?<![\w.])isMainThread\s*\(""").findAll(content).forEach { match ->
                        val lineNumber = content.take(match.range.first).count { it == '\n' } + 1
                        val lineText = content.lineSequence().drop(lineNumber - 1).first().trim()
                        if (lineText != "public static boolean isMainThread() {") {
                            violations += "$sourceKey:$lineNumber: forbidden unqualified isMainThread() call"
                        }
                    }
                }
            }

            if (violations.isNotEmpty()) {
                throw GradleException(
                    violations.joinToString(
                        separator = "\n",
                        postfix = "\nUse FaweThreadContext ownership predicates or a target-bearing dispatcher route; "
                                + "add no new legacy aliases."
                    )
                )
            }
            c5Stamp.get().asFile.apply {
                parentFile.mkdirs()
                writeText("C5 thread-context guard passed.\n")
            }
        }
    }

    plugins.withId("java") {
        tasks.named("compileJava").configure {
            dependsOn(checkC5ThreadContextReferences)
        }
    }
}
