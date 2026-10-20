package workbench.dataset

import workbench.json.Codec
import java.nio.file.Files
import java.nio.file.Path

/** Training export: one de-identified example per line (the trainer's input format). */
object Export {
    fun writeJsonl(s: Snapshot, path: Path) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { w -> s.examples.forEach { w.write(Codec.example(it).toString()); w.newLine() } }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
