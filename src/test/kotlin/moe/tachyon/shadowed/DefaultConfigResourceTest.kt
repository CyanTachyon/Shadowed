package moe.tachyon.shadowed

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultConfigResourceTest {
    @Test
    fun defaultConfigIsAvailableOnClasspath() {
        val stream = Loader.getResource("default_config.yaml")
        assertNotNull(stream, "default_config.yaml must be packaged on the classpath")
        stream.use { assertTrue(it.readBytes().isNotEmpty(), "default_config.yaml must be non-empty") }
    }

    @Test
    fun defaultConfigParsesAsYamlMapWithExpectedSections() {
        val text = Loader.getResource("default_config.yaml")?.bufferedReader()?.readText()
            ?: error("default_config.yaml missing from classpath")
        val node = Yaml.default.parseToYamlNode(text)
        assertTrue(node is YamlMap, "default_config.yaml root must be a YAML map")

        val keys = node.entries.keys.map { it.content }.toSet()
        assertTrue("ktor" in keys, "default_config.yaml must contain a 'ktor' section; got: $keys")
        assertTrue("database" in keys, "default_config.yaml must contain a 'database' section; got: $keys")
    }
}
