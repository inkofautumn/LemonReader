package cn.jiangcheng.lemon



import android.app.Activity

import android.app.Instrumentation

import android.content.Intent

import android.os.Bundle



/** Focused runner for the local-only public application. */

class SmokeInstrumentation:Instrumentation() {

    private var arguments:Bundle?=null



    override fun onCreate(arguments:Bundle?) {

        super.onCreate(arguments)

        this.arguments=arguments

        start()

    }



    override fun onStart() {

        val result=Bundle()

        try {

            val key=listOf(

                "scroll026","revision026","revision025","ui024","revision024","revision023","revision022","lemon011","ui_audit","revision0183","revision0182","revision0181","revision0180","revision0179",

                "revision0178","revision0177","revision0176","revision0175","revision0174","revision0173",

                "reader_chapter","import_regression","home_settings","local_backup","local_reading","shimo"

            ).firstOrNull{arguments?.getString(it)=="true"}

            val value=when(key) {

                "ui024"->RetrievalUi024.run(this)
                "scroll026"->Scroll026Test.run(this)
                "revision026"->Revision026Test.run(this)
                "revision025"->Clarification025Test.run(this)
                "revision024"->Revision024Test.run(this)
                "shimo"->ShimoTest.run(this)

                "local_backup"->LocalBackupTest.run(this)
                "local_reading"->LocalReadingTest.run(this)

                "home_settings"->HomeSettingsTest.run(this)

                "import_regression"->ImportRegression.run(this)

                else->{

                    val app=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

                    when(key) {

                        "revision023"->Revision023Test.run(this,app)

                        "revision022"->Revision022Test.run(this,app)

                        "lemon011"->Lemon011Test.run(this,app)

                        "ui_audit"->AuditScreens.run(this,app)

                        "reader_chapter"->ReaderChapterTransitionRegression.run(this,app)

                        "revision0173"->Revision0173Test.run(this,app)

                        "revision0174"->Revision0174Test.run(this,app)

                        "revision0175"->Revision0175Test.run(this,app)

                        "revision0176"->Revision0176Test.run(this,app)

                        "revision0177"->Revision0177Test.run(this,app)

                        "revision0178"->Revision0178Test.run(this,app)

                        "revision0179"->Revision0179Test.run(this,app)

                        "revision0180"->Revision0180Test.run(this,app)

                        "revision0181"->Revision0181Test.run(this,app)

                        "revision0182"->Revision0182Test.run(this,app)

                        "revision0183"->Revision0183Test.run(this,app)

                        else->{check(app.title.toString().contains("灵墨阅读"));"灵墨阅读启动检查通过"}

                    }

                }

            }

            result.putString("result",value)

            finish(Activity.RESULT_OK,result)

        } catch(error:Throwable) {

            result.putString("error",error.stackTraceToString())

            finish(Activity.RESULT_CANCELED,result)

        }

    }

}

