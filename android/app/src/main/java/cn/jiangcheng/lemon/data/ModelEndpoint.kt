package cn.jiangcheng.lemon.data

/** Accepted operations for a user-configured model connection. */
internal object ModelEndpoint {
    fun method(path: String, hasBody: Boolean): String = when {
        path == "/models" && !hasBody -> "GET"
        path == "/chat/completions" && hasBody -> "POST"
        else -> throw IllegalArgumentException("模型请求路径或内容不受支持")
    }
}
