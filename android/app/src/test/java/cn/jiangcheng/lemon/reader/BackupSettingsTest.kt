package cn.jiangcheng.lemon.reader

import cn.jiangcheng.lemon.data.BackupSettings
import org.junit.Assert.*
import org.junit.Test

class BackupSettingsTest {
    @Test fun retainsReadingAndModelPreferences(){
        assertTrue(BackupSettings.allowed("app","models:local"))
        assertTrue(BackupSettings.allowed("app","route:local:deep"))
        assertTrue(BackupSettings.allowed("reading:local","panel_fraction:阅读资料"))
        assertTrue(BackupSettings.allowed("chronicle-progress:local","offset:book-one"))
    }
    @Test fun rejectsCredentialsAndUnknownPreferences(){
        listOf("api_key","model-key:local:one","password","session","unexpected").forEach{
            assertFalse(BackupSettings.allowed("app",it))
        }
        assertFalse(BackupSettings.allowed("secrets","models:local"))
        assertFalse(BackupSettings.allowed("reading:local","size".repeat(100)))
    }
}
