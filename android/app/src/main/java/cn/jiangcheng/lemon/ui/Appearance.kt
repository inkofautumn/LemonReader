package cn.jiangcheng.lemon.ui

import android.content.Context
import android.content.res.Configuration

object Appearance {
    val modes=listOf("默认主题","深色模式","跟随系统")
    fun mode(context:Context)=context.getSharedPreferences("app",0).getString("appearance","默认主题")!!
    fun systemNight(context:Context)=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    fun dark(context:Context)=when(mode(context)){"深色模式"->true;"跟随系统"->systemNight(context);else->false}
    private fun token(context:Context)=mode(context)+if(mode(context)=="跟随系统")systemNight(context).toString()else""
    fun readerNight(context:Context,owner:String):Boolean {
        val p=context.getSharedPreferences("reading:$owner",0)
        if(p.getString("night_override_theme",null)==token(context))return p.getBoolean("night",false)
        if(!context.getSharedPreferences("app",0).contains("appearance"))return p.getBoolean("night",false)
        return dark(context)
    }
    fun setReaderNight(context:Context,owner:String,night:Boolean){context.getSharedPreferences("reading:$owner",0).edit().putBoolean("night",night).putString("night_override_theme",token(context)).apply()}
    fun setMode(context:Context,owner:String,value:String){require(value in modes);context.getSharedPreferences("app",0).edit().putString("appearance",value).apply();context.getSharedPreferences("reading:$owner",0).edit().remove("night_override_theme").putBoolean("night",dark(context)).apply()}
}
