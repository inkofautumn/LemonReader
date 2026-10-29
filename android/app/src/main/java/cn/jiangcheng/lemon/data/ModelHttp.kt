package cn.jiangcheng.lemon.data

import org.json.JSONObject

import java.net.HttpURLConnection

import java.net.URL

object ModelHttp {
    class Failure(val status:Int, message:String):IllegalStateException(message)
    /** HTTPS client used only for user-configured AI providers. */
    fun request(base: String, path: String, body: JSONObject? = null, token: String = "", timeout:Int=90000,cancellation:cn.jiangcheng.lemon.reader.AiCancellation?=null): JSONObject {

        val method = ModelEndpoint.method(path, body != null)
        val url = URL(base.trimEnd('/') + path)

        require(url.protocol=="https") { "模型接口需使用 HTTPS" }
        val connection = url.openConnection() as HttpURLConnection

        connection.connectTimeout = minOf(12000,timeout); connection.readTimeout = timeout

        connection.instanceFollowRedirects = false

        connection.requestMethod = method

        if(token.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $token")

        cancellation?.attach(connection)
        try {

            if (body != null) {

                connection.doOutput = true

                connection.setRequestProperty("Content-Type","application/json; charset=utf-8")

                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            }

            val code = connection.responseCode

            val text = (if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()

            if(code !in 200..299) {

                val message = runCatching { JSONObject(text).let{it.optString("detail").ifBlank{it.optJSONObject("error")?.optString("message").orEmpty()}}.take(250) }.getOrDefault("")

                throw Failure(code,if(message.isNotBlank()) if(token.isNotBlank())message.replace(token,"[密钥]")else message else "服务请求失败（$code）")
            }

            return if(text.isBlank()) JSONObject() else if(text.trimStart().startsWith("["))JSONObject().put("items",org.json.JSONArray(text))else JSONObject(text)

        } catch(e:java.io.IOException) { cancellation?.check();throw Failure(0,"服务暂时无法连接，请稍后重试").apply{initCause(e)} } finally { cancellation?.detach(connection);connection.disconnect() }

    }

    fun stream(base:String,body:JSONObject,token:String,onText:(String)->Unit,cancellation:cn.jiangcheng.lemon.reader.AiCancellation?=null):JSONObject {

        val url=URL(base.trimEnd('/')+"/chat/completions");require(url.protocol=="https"){"模型接口需使用 HTTPS"}
        val connection=url.openConnection() as HttpURLConnection
        connection.connectTimeout=12000;connection.readTimeout=90000;connection.instanceFollowRedirects=false

        connection.requestMethod="POST";connection.doOutput=true

        connection.setRequestProperty("Authorization","Bearer $token")

        connection.setRequestProperty("Content-Type","application/json; charset=utf-8")

        connection.setRequestProperty("Accept","text/event-stream")

        cancellation?.attach(connection)
        try{

            connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}

            val status=connection.responseCode

            if(status !in 200..299)throw Failure(status,when(status){401,403->"密钥无效或没有模型权限（$status）";404->"接口地址或模型不存在（404）";429->"请求过于频繁或账户额度不足（429）";400,422->"模型不接受当前参数，请检查接口兼容选项（$status）";else->"模型请求失败（$status），请稍后重试"})
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use{reader->
                if(connection.contentType.orEmpty().contains("application/json")){
                    val result=JSONObject(reader.readText());val choice=result.optJSONArray("choices")?.optJSONObject(0)?:error("模型响应缺少正文")
                    val text=choice.optJSONObject("message")?.optString("content").orEmpty();check(text.isNotBlank()){"模型未返回正文"};onText(text)
                    check(choice.optString("finish_reason","stop")=="stop"){"回答尚未完成，请缩小问题范围后重试"};result
                }else ChatStream.read(reader,onText)
            }
        }catch(e:java.io.IOException){cancellation?.check();throw Failure(0,"回答连接中断，请稍后重试").apply{initCause(e)}}finally{cancellation?.detach(connection);connection.disconnect()}
    }

}
