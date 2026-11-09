package com.music.spotui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Collections
import java.util.IdentityHashMap

/** Localized and themed Compose contexts may wrap the Activity more than once. */
fun Context.findActivity():Activity? {
    var current:Context=this
    val visited=Collections.newSetFromMap(IdentityHashMap<Context,Boolean>())
    while(visited.add(current)) {
        if(current is Activity)return current
        current=(current as? ContextWrapper)?.baseContext?:return null
    }
    return null
}
