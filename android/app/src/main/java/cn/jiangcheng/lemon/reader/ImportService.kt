package cn.jiangcheng.lemon.reader

import android.app.*
import android.content.Intent
import android.os.*
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.R

/** User-started reading preparation. The durable per-book checkpoint survives process loss. */
class ImportService:Service() {
    private var lock:PowerManager.WakeLock?=null
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("reader_prepare","小说智能整理",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(2010,Notification.Builder(this,"reader_prepare").setSmallIcon(R.drawable.ic_lemon).setContentTitle("小说智能整理").setContentText("正在分段整理，原文可随时阅读").setContentIntent(intent).setOngoing(true).build())
        lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lemon:reader_prepare").apply{acquire(6*60*60*1000L)}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        intent?.getStringExtra("owner")?.let{ImportTasks.resume(this,it)}
        if(!ImportTasks.isRunning())stopSelf()
        return START_REDELIVER_INTENT
    }
    override fun onTimeout(startId:Int,fgsType:Int){ImportTasks.pause(this);stopSelf()}
    override fun onDestroy(){lock?.let{if(it.isHeld)it.release()};super.onDestroy()}
    override fun onBind(intent:Intent?)=null
}
