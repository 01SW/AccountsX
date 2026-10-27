package accountsx.build

import java.io.File

private val NL: String = System.lineSeparator()

private fun verify(condition: Boolean, message: String) {
    if (!condition) throw IllegalStateException(message)
}

private fun expectFailure(expectedFragment: String, body: () -> Unit) {
    val message = try {
        body()
        null
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }
    val got = message ?: "<no failure>"
    verify(message != null && message.contains(expectedFragment),
        "expected a failure containing '" + expectedFragment + "', got: " + got)
}

/**
 * AdapterSourceResolver 的规则自检：纯文件系统 fixture，不依赖 Gradle/Loom，也不碰真实适配器目录。
 *
 * <p>返回失败描述；空列表表示全部通过。由根项目 {@code checkAdapterSourceResolver} 任务调用，
 * 覆盖来源链、覆盖顺序、显式删除、无效输入拒绝、生成目录同步清理这些构建期规则。</p>
 */
object AdapterSourceResolverSelfTest {

    private const val REL = "top/syshub/accountsx/adapters/mc/Demo.java"

    fun run(workDir: File): List<String> {
        workDir.deleteRecursively()
        workDir.mkdirs()

        val cases: List<Pair<String, (File) -> Unit>> = listOf(
            "nearestVersionWins" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2"))
                f.source("1.20", REL, "v1")
                f.source("1.20.2", REL, "v2")
                val r = f.resolve("1.20.2")
                verify(r.owners[REL] == "1.20.2", "newer layer must win, got " + r.owners[REL])
                verify(
                    r.effectiveFiles.getValue(REL).readText().contains("// v2"),
                    "effective content must come from the newest layer"
                )
            },
            "fallsBackThroughChain" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2", "1.20.3"))
                f.source("1.20", "a/One.java", "one")
                f.source("1.20.3", "b/Two.java", "two")
                val r = f.resolve("1.20.3")
                verify(r.lineage == listOf("1.20", "1.20.2", "1.20.3"), "lineage: " + r.lineage)
                verify(r.owners["a/One.java"] == "1.20", "inherited file keeps its owner")
                verify(r.owners["b/Two.java"] == "1.20.3", "own file keeps its owner")
            },
            "removalHidesInheritedFile" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2"))
                f.source("1.20", REL, "v1")
                f.removals("1.20.2", REL)
                val r = f.resolve("1.20.2")
                verify(!r.effectiveFiles.containsKey(REL), "removed file must not be effective")
                verify(r.deletions.contains(REL), "deletion must be reported")
            },
            "removalCanBeReintroduced" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2", "1.20.3"))
                f.source("1.20", REL, "v1")
                f.removals("1.20.2", REL)
                f.source("1.20.3", REL, "v3")
                val r = f.resolve("1.20.3")
                verify(r.owners[REL] == "1.20.3", "reintroduced file must be owned by the later version")
            },
            "unknownRemovalFails" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2"))
                f.source("1.20", REL, "v1")
                f.removals("1.20.2", "ghost/Ghost.java")
                expectFailure("ghost/Ghost.java") { f.resolve("1.20.2") }
            },
            "repeatedRemovalIsAllowed" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2", "1.20.3"))
                f.source("1.20", REL, "v1")
                f.removals("1.20.2", REL)
                f.removals("1.20.3", REL)
                val r = f.resolve("1.20.3")
                verify(!r.effectiveFiles.containsKey(REL), "file must stay removed across layers")
            },
            "caseCollisionFails" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2"))
                f.source("1.20", "pkg/Foo.java", "upper")
                f.source("1.20.2", "pkg/foo.java", "lower")
                expectFailure("only by case") { f.resolve("1.20.2") }
            },
            "packageMismatchFails" to { dir ->
                val f = Fixture(dir, listOf("1.20"))
                f.raw("1.20", REL, "package wrong.pkg;" + NL + NL + "public class Demo {" + NL + "}" + NL)
                expectFailure("Package mismatch") { f.resolve("1.20") }
            },
            "sourceBaseBranch" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2", "1.20.3"), mapOf("1.20.3" to "1.20"))
                val r = f.resolve("1.20.3")
                verify(r.lineage == listOf("1.20", "1.20.3"), "branch lineage: " + r.lineage)
            },
            "sourceBaseNoneStartsNewRoot" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2", "1.20.3"), mapOf("1.20.2" to "none"))
                val r = f.resolve("1.20.3")
                verify(r.lineage == listOf("1.20.2", "1.20.3"), "new root lineage: " + r.lineage)
            },
            "sourceBaseForwardRefFails" to { dir ->
                val f = Fixture(dir, listOf("1.20", "1.20.2"), mapOf("1.20" to "1.20.2"))
                expectFailure("earlier version") { f.resolve("1.20.2") }
            },
            "syncRemovesStaleOutput" to { dir ->
                val f = Fixture(dir, listOf("1.20"))
                f.source("1.20", "a/One.java", "one")
                f.source("1.20", "a/Two.java", "two")
                val out = File(dir, "generated")
                AdapterSourceResolver.syncTo(out, f.resolve("1.20"))
                val stale = File(out, "a/One.java")
                verify(stale.isFile, "first sync must produce the file")
                File(f.root, "adapters/mc/1.20/src/main/java/a/One.java").delete()
                AdapterSourceResolver.syncTo(out, f.resolve("1.20"))
                verify(!stale.isFile, "deleted source must not survive in the generated dir")
                verify(File(out, "a/Two.java").isFile, "remaining source must still be there")
            }
        )

        val failures = mutableListOf<String>()
        for ((name, body) in cases) {
            try {
                body(File(workDir, name))
            } catch (t: Throwable) {
                failures.add(name + ": " + (t.message ?: t.toString()))
            }
        }
        return failures
    }

    private class Fixture(
        val root: File,
        versions: List<String>,
        bases: Map<String, String> = emptyMap()
    ) {
        val matrix: AdapterMatrix = AdapterMatrix(
            versions.map { McAdapter(it, "6.0.54", "0.100.0", "1.0.0", true, null, "17", bases[it]) },
            listOf(AuthlibAdapter("6.0.54"))
        )

        init {
            for (v in versions) {
                File(root, "adapters/mc/" + v + "/src/main/java").mkdirs()
            }
        }

        fun source(version: String, rel: String, marker: String) {
            val pkg = rel.substringBeforeLast('/').replace('/', '.')
            val cls = File(rel).name.removeSuffix(".java")
            raw(version, rel, "package " + pkg + ";" + NL + NL + "public class " + cls + " {" + NL +
                "    // " + marker + NL + "}" + NL)
        }

        fun raw(version: String, rel: String, content: String) {
            val dest = File(root, "adapters/mc/" + version + "/src/main/java/" + rel)
            dest.parentFile.mkdirs()
            dest.writeText(content)
        }

        fun removals(version: String, vararg rels: String) {
            File(root, "adapters/mc/" + version + "/source-removals.txt")
                .writeText(rels.joinToString(NL) + NL)
        }

        fun resolve(target: String): ResolvedSources = AdapterSourceResolver.resolve(matrix, root, target)
    }
}
