package cn.jiangcheng.lemon.data

/** The portable settings schema is shared by export and restore. */
object BackupSettings {
    private val appKeys=setOf("appearance","home_start_page","home_hide_novel_name","shelf-folders","shelf-list",
        "import_encoding","import_chapters","import_titles","import_tidy","import_ads",
        "ai_directory","ai_budget","auto_chronicles","auto_chronicles_budget","chronicle_sort","volume_turn",
        "models","route","route-model","thinking")
    private val readingKeys=setOf("size","line","paragraph","margin","paper","ink","night","family","mode","warmth",
        "top_gap","bottom_gap","auto_page_mode","auto_page_seconds","brightness","last_manual_brightness",
        "swipe_sensitivity","swipe_rebound_threshold","page_animation_speed","background","night_override_theme",
        "toc_display","toc_drawer","toc_numbered","toc_reverse","panels_full","panel_fraction")
    private val excerptKeys=setOf("auto","view","book","source","tags","any","trash","query","sort","folder","scroll")
    fun allowed(file:String,key:String):Boolean {
        if(key.length !in 1..200)return false
        return when(file.substringBefore(':')) {
            "app"->key.substringBefore(':') in appKeys
            "reading"->key in readingKeys||key.startsWith("panel_full:")||key.startsWith("panel_fraction:")
            "shimo-ui"->key in excerptKeys
            "chronicle-progress"->key.substringBefore(':') in setOf("algorithm","anchor","attempt","chapter","offset","revision","status")
            else->false
        }
    }
}
