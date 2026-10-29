package cn.jiangcheng.lemon.reader

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

class AiCancellation {
    @Volatile var cancelled=false;private set
    private val connections=mutableSetOf<HttpURLConnection>()
    fun check(){if(cancelled)throw CancellationException("已停止查找")}
    @Synchronized fun attach(connection:HttpURLConnection){check();connections.add(connection)}
    @Synchronized fun detach(connection:HttpURLConnection){connections.remove(connection)}
    fun cancel(){cancelled=true;val current=synchronized(this){connections.toList()};Thread{current.forEach{runCatching{it.disconnect()}}}.apply{isDaemon=true;start()}}
}
