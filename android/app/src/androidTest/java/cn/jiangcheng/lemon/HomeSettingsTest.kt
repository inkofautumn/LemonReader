package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.data.LocalProfile
import cn.jiangcheng.lemon.reader.Chapter

/** Regression checks for the account-scoped home start page and shelf back paths. */
object HomeSettingsTest {
    fun run(i:Instrumentation):String {
        val context=i.targetContext
        val owner=LocalProfile.ID
        val title="首页测试书-${System.nanoTime()}"
        val prefs=context.getSharedPreferences("app",0)
        val hideKey="home_hide_novel_name:$owner";val startKey="home_start_page:$owner"
        val hadHide=prefs.contains(hideKey);val previousHide=prefs.getBoolean(hideKey,true)
        val hadStart=prefs.contains(startKey);val previousStart=prefs.getString(startKey,"default")
        prefs.edit().putString(startKey,"default").commit()
        var app:MainActivity?=null;var checks=0
        var created:Book?=null
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
        fun views():List<View>{var result=emptyList<View>();i.runOnMainSync{result=descendants(app!!.window.decorView)};return result}
        fun main(action:()->Unit){i.runOnMainSync{action()};i.waitForIdleSync()}
        fun launch():MainActivity{
            app=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            i.waitForIdleSync();Thread.sleep(250);return app!!
        }
        try {
            app=launch()
            val book=Book(title=title,chapters=listOf(Chapter("第一章","测试正文")),sourceName="首页测试.txt");created=book
            app!!.library.save(owner,book)
            app!!.prefs.edit().putBoolean("home_hide_novel_name:$owner",true).putString("home_start_page:$owner","shelf").commit()
            main{app!!.showHome()}
            verify(views().filterIsInstance<TextView>().none{it.text.toString()==title},"默认隐藏置顶小说名称")
            main{app!!.showSettings()}
            verify(views().filterIsInstance<Switch>().any{it.text.toString()=="隐藏小说名称"&&it.isChecked},"设置中隐藏开关默认开启")
            app!!.prefs.edit().putBoolean("home_hide_novel_name:$owner",false).commit();main{app!!.showHome()}
            verify(views().filterIsInstance<TextView>().any{it.text.toString()==title},"关闭隐藏后显示置顶小说名称")
            app!!.prefs.edit().putBoolean("home_hide_novel_name:$owner",true).commit();app!!.finish();i.waitForIdleSync();app=launch()
            verify(views().filterIsInstance<TextView>().any{it.text.toString()=="书架"},"书架启动落在根目录")
            main{app!!.onBackPressed()}
            verify(views().filterIsInstance<TextView>().any{it.text.toString()=="书架"},"书架根页系统返回沿用首页连续返回逻辑")
            main{app!!.showShelf(folder="")}
            main{descendants(app!!.window.decorView).firstOrNull{it.contentDescription=="返回首页"}?.performClick()}
            verify(views().filterIsInstance<TextView>().any{it.text.toString()=="灵墨阅读"},"书架左上角返回默认主页")
            app!!.prefs.edit().putString("home_start_page:$owner","default").commit();app!!.finish();i.waitForIdleSync();app=launch()
            verify(views().filterIsInstance<TextView>().any{it.text.toString()=="灵墨阅读"},"默认主页启动路由")
            app!!.prefs.edit().putString("home_start_page:$owner","shimo").commit();app!!.finish();i.waitForIdleSync()
            val monitor=i.addMonitor("cn.jiangcheng.lemon.shimo.ShimoActivity",null,false)
            app=launch();val shimo=i.waitForMonitorWithTimeout(monitor,4000);i.removeMonitor(monitor)
            verify(shimo!=null,"拾墨启动路由")
            shimo?.finish();i.waitForIdleSync()
            return "$checks home start and back checks passed"
        } finally {
            app?.finish()
            created?.let{runCatching{app?.library?.deleteBook(owner,it)}}
            prefs.edit().also{edit->if(hadHide)edit.putBoolean(hideKey,previousHide)else edit.remove(hideKey);if(hadStart)edit.putString(startKey,previousStart)else edit.remove(startKey)}.commit()
        }
    }
}
