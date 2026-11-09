package com.music.spotui.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

class SyncTreeMaterializationTest {
    // Reference implementation protects order, missing nodes and tombstone cuts
    // while the production implementation uses a direct-child index.
    private fun reference(tree: SyncTree): Any? {
        val fields = tree.fields
        fun build(path: String, inherited: SyncStamp? = null): Any? {
            val field = fields[path]?.takeUnless { it.deleted } ?: return null
            val cut = listOfNotNull(inherited, field.cut).maxOrNull()
            val value = JSONTokener(field.json).nextValue()
            if (value !is JSONObject || !value.has("@node")) return value
            val prefix = "$path/"
            val children = fields.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
                .filter { fields[prefix + it]?.deleted == false }
            return if (value.optString("@node") == "array") {
                val order = value.optJSONArray("order") ?: JSONArray()
                val ids = (0 until order.length()).map { order.getString(it) }
                JSONArray().also { result ->
                    (ids + children.sorted()).distinct().filter {
                        it in children && (cut == null || fields.getValue(prefix + it).stamp > cut)
                    }.forEach { build(prefix + it, cut)?.let(result::put) }
                }
            } else JSONObject().also { result ->
                children.sorted().forEach { key -> build(prefix + key, cut)?.let {
                    result.put(key.replace("~1", "/").replace("~0", "~"), it)
                } }
            }
        }
        return build("")
    }

    @Test fun indexedMaterializationMatchesReferenceAcrossOfflineEdits() {
        val random = Random(73)
        var original = JSONObject().put("songs", JSONArray()).put("name", "Lista")
        var tree = SyncTree.changed(emptyMap(), SyncTree.flatten(original), SyncStamp(1, 0, "a"))
        repeat(400) { iteration ->
            val edited = JSONObject().put("name", "Lista ${random.nextInt(5)}")
                .put("songs", JSONArray().also { songs ->
                    (0 until 12).shuffled(random).take(random.nextInt(13)).forEach { id ->
                        songs.put(JSONObject().put("id", id).put("title", "Tema $id")
                            .put("path/~", JSONArray(listOf("a", "b", iteration.toString()))))
                    }
                })
            val delta = SyncTree.changed(SyncTree.flatten(original), SyncTree.flatten(edited),
                SyncStamp(iteration + 2L, 0, if (iteration % 2 == 0) "a" else "b"))
            tree = tree.merge(delta)
            assertEquals(reference(tree)?.toString(), tree.value()?.toString())
            assertEquals(reference(tree)?.toString(), SyncTree.parse(tree.json()).value()?.toString())
            original = edited
        }
    }

    @Test fun largeLibraryKeepsEveryMemberAndOrder() {
        val songs = JSONArray().also { array -> repeat(2500) {
            array.put(JSONObject().put("id", it).put("title", "Tema $it").put("artist", "Autor $it"))
        } }
        val tree = SyncTree.changed(emptyMap(), SyncTree.flatten(songs), SyncStamp(1, 0, "a"))
        val restored = tree.value() as JSONArray
        assertEquals(2500, restored.length())
        repeat(2500) { assertEquals(it, restored.getJSONObject(it).getInt("id")) }
    }

    @Test fun returnedJsonCanBeEditedWithoutChangingTheTree() {
        val value = JSONObject("""{"songs":[{"id":1,"title":"Uno"}]}""")
        val tree = SyncTree.changed(emptyMap(), SyncTree.flatten(value), SyncStamp(1, 0, "a"))
        (tree.value() as JSONObject).getJSONArray("songs").getJSONObject(0).put("title", "Editado")
        assertEquals("Uno", (tree.value() as JSONObject).getJSONArray("songs").getJSONObject(0).getString("title"))
    }

    @Test fun unchangedRemoteEchoRetainsTreeIdentity() {
        val tree = SyncTree.changed(emptyMap(), SyncTree.flatten(JSONArray(listOf("a", "b"))), SyncStamp(1, 0, "a"))
        assertSame(tree, tree.merge(SyncTree.parse(tree.json())))
        assertSame(tree, tree.merge(SyncTree()))
    }

    @Test fun fastDigestPreservesExistingWireIdentities() {
        listOf("", "a", "música / ~ 🎧", "spotify:track:123", "asset:" + "a".repeat(64)).forEach { value ->
            val expected = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, SyncTree.digest(value))
        }
    }
}
