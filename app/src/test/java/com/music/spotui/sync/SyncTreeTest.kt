package com.music.spotui.sync

import org.json.*
import org.junit.Test
import org.junit.Assert.*

class SyncTreeTest {
 private fun stamp(t:Long,a:String="a")=SyncStamp(t,0,a)
 private fun tree(v:Any,t:Long=1)=SyncTree.changed(emptyMap(),SyncTree.flatten(v),stamp(t))
 @Test fun concurrentPlaylistEditsConverge(){
  val original=JSONObject("""{"songs":[{"id":1,"title":"Uno"}],"name":"Lista"}""")
  val base=tree(original)
  val a=JSONObject("""{"songs":[{"id":1,"title":"Uno"},{"id":2,"title":"Dos"}],"name":"Nueva"}""")
  val b=JSONObject("""{"songs":[{"id":1,"title":"Uno"},{"id":3,"title":"Tres"}],"name":"Lista"}""")
  val left=base.merge(SyncTree.changed(SyncTree.flatten(original),SyncTree.flatten(a),stamp(2,"a")))
  val right=base.merge(SyncTree.changed(SyncTree.flatten(original),SyncTree.flatten(b),stamp(2,"b")))
  assertEquals(left.merge(right),right.merge(left));assertEquals(left.merge(right),left.merge(right).merge(left))
  val result=left.merge(right).value() as JSONObject
  assertEquals("Nueva",result.getString("name"));assertEquals(3,result.getJSONArray("songs").length())
 }
 @Test fun metadataPreservesFilledFields(){
  val a=tree(JSONObject("""{"title":"Canción","artist":"Autor","album":""}"""))
  val b=tree(JSONObject("""{"title":"Nuevo título","artist":"Unknown artist","album":"Disco"}"""),2)
  val result=a.merge(b).value() as JSONObject
  assertEquals("Nuevo título",result.getString("title"));assertEquals("Autor",result.getString("artist"));assertEquals("Disco",result.getString("album"))
 }
 @Test fun deletedMemberStaysDeletedUntilGenuineNewEdit(){
  val original=JSONArray("""[{"id":1,"title":"Uno"},{"id":2,"title":"Dos"}]""")
  val base=tree(original)
  val removed=JSONArray("""[{"id":1,"title":"Uno"}]""")
  val deleted=base.merge(SyncTree.changed(SyncTree.flatten(original),SyncTree.flatten(removed),stamp(3)))
  assertEquals(1,(deleted.merge(base).value() as JSONArray).length())
  val edited=JSONArray("""[{"id":1,"title":"Uno"},{"id":2,"title":"Nuevo"}]""")
  val edit=base.merge(SyncTree.changed(SyncTree.flatten(original),SyncTree.flatten(edited),stamp(4)))
  val revived=deleted.merge(edit).value() as JSONArray
  assertEquals(2,revived.length())
  assertTrue((0 until revived.length()).any { revived.getJSONObject(it).getInt("id")==2 })
 }
 @Test fun registerMergeAlgebra(){
  val random=java.util.Random(9)
  repeat(5000){
   fun next():SyncValue {val v=listOf("\"\"","\"Unknown artist\"","\"Autor\"","\"Dos\"")[random.nextInt(4)];return SyncValue(v,stamp(random.nextInt(5).toLong(),('a'+random.nextInt(3)).toString()),random.nextBoolean(),"fill")}
   val a=next();val b=next();val c=next()
   assertEquals(SyncValue.merge(a,b),SyncValue.merge(b,a))
   assertEquals(SyncValue.merge(SyncValue.merge(a,b),c),SyncValue.merge(a,SyncValue.merge(b,c)))
   assertEquals(a,SyncValue.merge(a,a))
  }
 }

 @Test fun readdingAnEmptyListDoesNotResurrectDeletedMembers(){
    val initial=JSONArray("""[{"id":1,"title":"Uno"}]""")
    val original=tree(initial)
    val deleted=original.merge(SyncTree.changed(SyncTree.flatten(initial),emptyMap(),stamp(3)))
    val readded=deleted.merge(SyncTree.changed(emptyMap(),SyncTree.flatten(JSONArray()),stamp(4)))
    assertEquals(0,(readded.value() as JSONArray).length())
    val oldClient=original.merge(SyncTree.changed(SyncTree.flatten(initial),SyncTree.flatten(JSONArray("""[{"id":1,"title":"Uno"},{"id":2,"title":"Dos"}]""")),stamp(5)))
    val result=readded.merge(oldClient).value() as JSONArray
    assertEquals(1,result.length());assertEquals(2,result.getJSONObject(0).getInt("id"))
 }

}
