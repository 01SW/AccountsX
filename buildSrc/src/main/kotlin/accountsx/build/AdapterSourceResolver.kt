package accountsx.build

import java.io.File

data class ResolvedSources(
    val targetVersion: String,
    val effectiveFiles: Map<String, File>,
    val owners: Map<String, String>,
    val deletions: List<String>,
    val lineage: List<String>
)

object AdapterSourceResolver {

    private const val JAVA_ROOT = "src/main/java"
    private const val REMOVALS_FILE = "source-removals.txt"

    fun resolve(
        matrix: AdapterMatrix,
        rootDir: File,
        targetVersion: String,
        adapterRoot: (String) -> File = { v -> File(rootDir, "adapters/mc/$v") }
    ): ResolvedSources {
        val lineage = buildLineage(matrix, targetVersion)

        for (v in lineage) {
            val dir = adapterRoot(v)
            require(dir.isDirectory) { "Adapter directory missing: ${dir.absolutePath}" }
        }

        val effective = LinkedHashMap<String, File>()
        val owners = LinkedHashMap<String, String>()
        val allDeletions = mutableListOf<String>()

        for (version in lineage) {
            val adapterDir = adapterRoot(version)

            val removals = readRemovals(File(adapterDir, REMOVALS_FILE))
            for (rel in removals) {
                val normalized = normalizeRelPath(rel)
                if (!effective.containsKey(normalized)) {
                    continue
                }
                effective.remove(normalized)
                owners.remove(normalized)
                allDeletions.add(normalized)
            }

            val javaDir = File(adapterDir, JAVA_ROOT)
            if (javaDir.isDirectory) {
                javaDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".java") }
                    .forEach { file ->
                        val rel = file.relativeTo(javaDir).invariantSeparatorsPath
                        validatePackage(file, rel)
                        effective[rel] = file
                        owners[rel] = version
                    }
            }
        }

        validateFqcnUniqueness(effective)

        return ResolvedSources(
            targetVersion = targetVersion,
            effectiveFiles = effective,
            owners = owners,
            deletions = allDeletions,
            lineage = lineage
        )
    }

    private fun buildLineage(matrix: AdapterMatrix, targetVersion: String): List<String> {
        val mcVersions = matrix.mc.map { it.version }
        val targetIndex = mcVersions.indexOf(targetVersion)
        require(targetIndex >= 0) { "Target version '$targetVersion' not in matrix" }
        return mcVersions.subList(0, targetIndex + 1).toList()
    }

    private fun readRemovals(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeRelPath(rel: String): String {
        require(!rel.startsWith("/") && !rel.startsWith("\\"))
        require(!rel.contains(".."))
        require(!rel.contains("\\"))
        val normalized = rel.replace('\\', '/')
        require(!normalized.contains("//"))
        require(normalized.endsWith(".java"))
        return normalized
    }

    private fun validatePackage(file: File, relPath: String) {
        val expectedPackage = relPath.substringBeforeLast('/').replace('/', '.')
        val declared = file.readLines()
            .firstOrNull { it.trim().startsWith("package ") }
            ?.trim()
            ?.removePrefix("package ")
            ?.removeSuffix(";")
            ?.trim()
        if (declared != null && declared != expectedPackage) {
            throw IllegalArgumentException(
                "Package mismatch in ${file.absolutePath}: declared '$declared', expected '$expectedPackage'"
            )
        }
    }

    private fun validateFqcnUniqueness(effective: Map<String, File>) {
        val seen = mutableMapOf<String, String>()
        for ((rel, file) in effective) {
            val pkg = rel.substringBeforeLast('/').replace('/', '.')
            val className = file.name.removeSuffix(".java")
            val fqcn = if (pkg == rel) className else "$pkg.$className"
            val prev = seen[fqcn]
            if (prev != null && prev != rel) {
                throw IllegalArgumentException(
                    "Duplicate FQCN '$fqcn' from '$prev' and '$rel'"
                )
            }
            seen[fqcn] = rel
        }
    }
}
