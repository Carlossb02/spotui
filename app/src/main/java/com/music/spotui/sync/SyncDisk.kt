package com.music.spotui.sync

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal class SyncDisk(private val file: File) {
    private val atomic=AtomicFile(file)
    private var lastHash:String?=null
    fun read():JSONObject = if(file.exists() || File(file.path+".bak").exists()) JSONObject(atomic.openRead().bufferedReader().use { it.readText() }.also { lastHash=SyncTree.digest(it) }) else JSONObject()
    fun write(o:JSONObject) {
        val raw=o.toString()
        val hash=SyncTree.digest(raw)
        if(hash==lastHash && file.exists())return
        val out=atomic.startWrite()
        try {out.write(raw.toByteArray(Charsets.UTF_8));atomic.finishWrite(out);lastHash=hash}catch(t:Throwable){atomic.failWrite(out);throw t}
    }
}
