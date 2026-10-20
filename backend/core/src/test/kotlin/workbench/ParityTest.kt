package workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import workbench.model.EntityTagger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin tagger must reproduce, tag for tag, what the Python trainer computed (trainer/parity.json). */
class ParityTest {
    @Test
    fun `Kotlin inference matches the Python trainer exactly`() {
        val file = listOf("../../trainer/parity.json", "../trainer/parity.json", "trainer/parity.json").map(Path::of).first(Files::exists)
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val m = root["model"]!!.jsonObject
        val tagger = EntityTagger.fromBase64(
            "parity",
            m["labels"]!!.jsonArray.map { it.jsonPrimitive.content },
            m["buckets"]!!.jsonPrimitive.int,
            m["weights"]!!.jsonPrimitive.content,
            m["intercept"]!!.jsonPrimitive.content,
        )
        var tokens = 0
        for (c in root["cases"]!!.jsonArray) {
            val text = c.jsonObject["text"]!!.jsonPrimitive.content
            val expected = c.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, tagger.predictTokens(text).map { it.second }, text)
            tokens += expected.size
        }
        assertTrue(tokens > 50)
    }
}
