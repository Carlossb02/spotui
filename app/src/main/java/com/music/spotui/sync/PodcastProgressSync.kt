package com.music.spotui.sync

/** A resume position belongs to its listening timestamp, including backward seeks.
 * Importing an older device must never combine its position with a newer timestamp. */
internal object PodcastProgressSync {
    fun merge(key:String,left:SyncTree,right:SyncTree):SyncTree {
        if (key == "CustomMetadata:overrides_json") {
            // User edits, including an intentionally empty cover, are revisions.
            // Catalogue "fill missing fields" rules must never resurrect an old URL.
            fun revisions(tree: SyncTree) = SyncTree(tree.fields.mapValues { (_, value) -> value.copy(mode = "latest") })
            return revisions(left).merge(revisions(right))
        }
        val merged=left.merge(right)
        if(key.startsWith("release_news_seen_v1:")) {
            val values=listOfNotNull(left.fields["/value"],right.fields["/value"]).filter { !it.deleted && it.json.toLongOrNull()!=null }
            if(values.isEmpty())return merged
            val maximum=values.maxBy {it.json.toLong()}.copy(mode="max",stamp=values.maxOf {it.stamp})
            return SyncTree(merged.fields + ("/value" to maximum))
        }
        if(key!="spotui_podcast_resume_v1:episodes")return merged
        var fields:MutableMap<String,SyncValue>?=null
        (left.fields.keys+right.fields.keys).filter {it.endsWith("/updated")}.forEach { path->
            val a=left.fields[path]?.takeUnless {it.deleted}
            val b=right.fields[path]?.takeUnless {it.deleted}
            fun time(value:SyncValue?)=value?.json?.toLongOrNull()?:Long.MIN_VALUE
            val parent=path.substringBeforeLast('/')
            fun tie(tree:SyncTree)=listOf("position","duration","updated").joinToString("|") {tree.fields["$parent/$it"]?.json.orEmpty()}
            val winner=when {a==null->right;b==null->left;time(a)>time(b)->left;time(b)>time(a)->right;
                a.stamp>b.stamp->left;b.stamp>a.stamp->right;tie(left)>=tie(right)->left;else->right}
            listOf("position","duration","updated").forEach field@{ name->
                val field="$parent/$name"
                val chosen=winner.fields[field]?.takeUnless {it.deleted}?:return@field
                val next=chosen.copy(cut=merged.fields[field]?.cut)
                if(merged.fields[field]!=next){
                    val target=fields?:merged.fields.toMutableMap().also {fields=it}
                    target[field]=next
                }
            }
        }
        return fields?.let(::SyncTree)?:merged
    }
}
