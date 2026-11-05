package com.music.spotui.util
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class SharedWorkTest {
 @Test fun identicalRequestsShareOneJob() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope); val gate=CompletableDeferred<Unit>(); var calls=0
   val a=async(start=CoroutineStart.UNDISPATCHED){work.result("a"){calls++;gate.await();42}}
   val b=async(start=CoroutineStart.UNDISPATCHED){work.result("a"){error("duplicate")}}
   assertEquals(1,calls);gate.complete(Unit)
   assertEquals(42,a.await().value);assertTrue(b.await().reused)
  } finally {scope.cancel()}
 }
 @Test fun cancellationOfOneWaiterPreservesOther() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope);val gate=CompletableDeferred<Unit>();var cancelled=false
   val a=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){try{gate.await();7}finally{cancelled=true}}}
   val b=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){error("duplicate")}}
   a.cancelAndJoin();assertFalse(cancelled);gate.complete(Unit);assertEquals(7,b.await())
  } finally {scope.cancel()}
 }
 @Test fun abandonedWorkIsCancelledAndCanRestart() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope);val stopped=CompletableDeferred<Unit>()
   val a=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){try{awaitCancellation()}finally{stopped.complete(Unit)}}}
   a.cancelAndJoin();withTimeout(1000){stopped.await()};assertEquals(9,work.await("a"){9})
  } finally {scope.cancel()}
 }
 @Test fun differentKeysRunIndependentlyAndCancelAllStopsBoth() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope);var calls=0
   val a=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){calls++;awaitCancellation()}}
   val b=async(start=CoroutineStart.UNDISPATCHED){work.await("b"){calls++;awaitCancellation()}}
   assertEquals(2,calls);work.cancelAll();a.join();b.join();assertTrue(a.isCancelled);assertTrue(b.isCancelled)
   assertEquals(3,work.await("a"){3})
  } finally {scope.cancel()}
 }
 @Test fun failedWorkDoesNotPoisonNextRequest() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope)
   try{work.await("a"){error("failed")};fail()}catch(expected:IllegalStateException){}
   assertEquals(6,work.await("a"){6})
  } finally {scope.cancel()}
 }
 @Test fun oldCompletionCannotRemoveReplacement() = runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  try {
   val work=SharedWork<String,Int>(scope);val oldCleanup=CompletableDeferred<Unit>();val newGate=CompletableDeferred<Unit>()
   val a=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){try{awaitCancellation()}finally{withContext(NonCancellable){oldCleanup.await()}}}}
   work.cancel("a")
   val b=async(start=CoroutineStart.UNDISPATCHED){work.await("a"){newGate.await();11}}
   oldCleanup.complete(Unit);a.join()
   val c=async(start=CoroutineStart.UNDISPATCHED){work.result("a"){error("replacement lost")}}
   newGate.complete(Unit);assertEquals(11,b.await());assertTrue(c.await().reused)
  } finally {scope.cancel()}
 }
}
