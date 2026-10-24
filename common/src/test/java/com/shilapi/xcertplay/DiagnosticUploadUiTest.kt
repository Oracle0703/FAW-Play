package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,33],qualifiers="zh-rCN-w1920dp-h720dp-land-mdpi",manifest=Config.NONE)
class DiagnosticUploadUiTest {
    @Test fun uploadIsAnExplicitActionWithConfirmationAndLocalExportRemainsAvailable() {
        val activity=Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        AutunnSettingsPage.show(activity,"connection")
        selectSettingsTab(activity,"诊断")
        assertNotNull(settingsButton(activity,"保存诊断报告"))
        settingsButton(activity,"上传诊断报告").performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog); assertEquals("上传",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }
}
