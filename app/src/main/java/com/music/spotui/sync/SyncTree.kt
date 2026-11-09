package com.music.spotui.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

internal data class SyncStamp(val time: Long, val counter: Long, val actor: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp) = compareValuesBy(this, other, SyncStamp::time, SyncStamp::counter, SyncStamp::actor)
}
internal data class SyncValue(val json: String, val stamp: SyncStamp, val deleted: Boolean = false, val mode: String = "latest", val cut: SyncStamp? = if(deleted) stamp else null) {
    fun toJson() = JSONObject().put("v",json).put("t",stamp.time).put("c",stamp.counter).put("a",stamp.actor).put("d",deleted).put("m",mode).also { o->cut?.let { o.put("x",JSONObject().put("t",it.time).put("c",it.counter).put("a",it.actor)) } }
    companion object {
        fun parse(o: JSONObject) = SyncValue(o.getString("v"),SyncStamp(o.getLong("t"),o.getLong("c"),o.getString("a")),o.optBoolean("d"),o.optString("m","latest"),o.optJSONObject("x")?.let { SyncStamp(it.getLong("t"),it.getLong("c"),it.getString("a")) }?:if(o.optBoolean("d"))SyncStamp(o.getLong("t"),o.getLong("c"),o.getString("a"))else null)
        private fun missing(raw: String): Boolean {
            val v = JSONTokener(raw).nextValue()
            return v == JSONObject.NULL || v is Number && v.toDouble() <= 0 || v is String && v.trim().lowercase() in setOf("", "unknown", "unknown artist", "unknown album", "unknown title", "desconocido", "artista desconocido", "álbum desconocido", "título desconocido")
        }
        fun merge(a: SyncValue?, b: SyncValue): SyncValue {
            if(a==null)return b
            val cut=listOfNotNull(a.cut,b.cut).maxOrNull()
            fun keep(winner:SyncValue)=winner.copy(cut=cut)
            if(a.mode=="fill" || b.mode=="fill") {
                val am=!a.deleted && missing(a.json);val bm=!b.deleted && missing(b.json)
                if(am!=bm)return keep(if(am)b else a)
            }
            if(a.mode=="max" && b.mode=="max" && !a.deleted && !b.deleted)
                return SyncValue(maxOf(a.json.toLong(),b.json.toLong()).toString(),maxOf(a.stamp,b.stamp),mode="max",cut=cut)
            val c=a.stamp.compareTo(b.stamp)
            return keep(if(c>0 || c==0 && (a.deleted>b.deleted || a.deleted==b.deleted && a.json>=b.json))a else b)
        }
    }
}
/** Field-wise registers and stable array member identities retain independent offline edits. */
internal data class SyncTree(val fields: Map<String,SyncValue> = emptyMap()) {
    fun merge(other: SyncTree): SyncTree {
        var merged: MutableMap<String, SyncValue>? = null
        other.fields.forEach { (key, value) ->
            val next = SyncValue.merge(fields[key], value)
            if (next != fields[key]) {
                val target = merged ?: fields.toMutableMap().also { merged = it }
                target[key] = next
            }
        }
        return merged?.let(::SyncTree) ?: this
    }
    // The tree is immutable. Index direct children once instead of scanning all
    // fields for every node of a playlist. Never cache mutable JSON results.
    private val childrenByPath by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val index = mutableMapOf<String, MutableList<String>>()
        fields.keys.forEach { path ->
            if (path.isNotEmpty()) {
                index.getOrPut(path.substringBeforeLast('/')) { mutableListOf() }
                    .add(path.substringAfterLast('/'))
            }
        }
        index
    }
    fun json() = JSONObject().also { o->fields.forEach { (k,v)->o.put(k,v.toJson()) } }.toString()
    fun value(): Any? {
        fun build(path: String, inheritedCut: SyncStamp? = null): Any? {
            val f=fields[path]?.takeUnless { it.deleted }?:return null
            val cutoff=listOfNotNull(inheritedCut,f.cut).maxOrNull()
            val p=JSONTokener(f.json).nextValue()
            if(p !is JSONObject || !p.has("@node"))return p
            val prefix="$path/"
            val children=childrenByPath[path].orEmpty().filter { fields[prefix+it]?.deleted==false }.toSet()
            return if(p.optString("@node")=="array") {
                val order=p.optJSONArray("order")?:JSONArray()
                val ids=(0 until order.length()).map { order.getString(it) }
                JSONArray().also { a->(ids+children.sorted()).distinct().filter { it in children && (cutoff==null || fields.getValue(prefix+it).stamp>cutoff) }.forEach { build(prefix+it,cutoff)?.let(a::put) } }
            } else JSONObject().also { o->children.sorted().forEach { build(prefix+it,cutoff)?.let { v->o.put(unescape(it),v) } } }
        }
        return build("")
    }
    companion object {
        fun parse(raw: String): SyncTree { val o=JSONObject(raw);return SyncTree(o.keys().asSequence().associateWith { SyncValue.parse(o.getJSONObject(it)) }) }
        fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return CharArray(bytes.size * 2).also { out ->
                bytes.forEachIndexed { i, byte ->
                    val n = byte.toInt() and 0xff
                    out[i * 2] = hex[n ushr 4]; out[i * 2 + 1] = hex[n and 15]
                }
            }.concatToString()
        }
        private fun escape(v:String)=v.replace("~","~0").replace("/","~1")
        private fun unescape(v:String)=v.replace("~1","/").replace("~0","~")
        private fun valueToString(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(v)
            is Number -> JSONObject.numberToString(v)
            is Boolean -> v.toString()
            else -> JSONObject.wrap(v)?.toString() ?: "null"
        }
        fun flatten(value: Any): Map<String,String> {
            val result=linkedMapOf<String,String>()
            fun visit(path:String,v:Any) {
                when(v) {
                    is JSONObject->{ result[path]=JSONObject().put("@node","object").toString();v.keys().forEach { visit("$path/${escape(it)}",v.get(it)) } }
                    is JSONArray->{
                        val seen=mutableMapOf<String,Int>();val order=mutableListOf<String>()
                        for(i in 0 until v.length()) {
                            val item=v.get(i)
                            val identity=if(item is JSONObject)listOf("id","uri","url","key").firstNotNullOfOrNull { item.opt(it)?.takeUnless { x->x==JSONObject.NULL || x.toString().isBlank() }?.toString() }?:i.toString() else if(item is String)item else i.toString()
                            val n=seen.getOrDefault(identity,0);seen[identity]=n+1
                            val key=digest(identity).take(32)+"_$n";order+=key;visit("$path/$key",item)
                        }
                        result[path]=JSONObject().put("@node","array").put("order",JSONArray(order)).toString()
                    }
                    else->result[path]=valueToString(v)
                }
            }
            visit("",value);return result
        }
        fun changed(before:Map<String,String>, after:Map<String,String>, stamp:SyncStamp, history:Boolean=false):SyncTree {
            val out=mutableMapOf<String,SyncValue>()
            (before.keys+after.keys).forEach { path->
                if(before[path]==after[path])return@forEach
                val value=after[path];val name=unescape(path.substringAfterLast('/'))
                // A removed subtree is one tombstone. Keep its children so a genuine
                // later re-add restores the complete item, including its stable id.
                if(value==null && path.isNotEmpty() && !after.containsKey(path.substringBeforeLast('/',"")))return@forEach
                val mode=if(history && (path.startsWith("/counts/") || path.startsWith("/reset/") || path=="/ts"))"max"
                    else if(name in setOf("title","artist","singer","author","album","cover","coverUri","image","durationMs"))"fill" else "latest"
                out[path]=SyncValue(value?:"null",stamp,value==null,mode)
                if(value!=null) { var parent=path;while(parent.isNotEmpty()){parent=parent.substringBeforeLast('/',"");after[parent]?.let { out[parent]=SyncValue(it,stamp) }} }
            }
            return SyncTree(out)
        }
    }
}
