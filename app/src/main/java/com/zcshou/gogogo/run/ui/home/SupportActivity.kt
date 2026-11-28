package com.zcshou.gogogo.run.ui.home

import android.Manifest
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zcshou.gogogo.R
import com.zcshou.gogogo.mascot.MascotDialogueEngine
import com.zcshou.gogogo.mascot.MascotEgg
import com.zcshou.gogogo.mascot.MascotEggCardView
import com.zcshou.gogogo.mascot.MascotEggStore
import com.zcshou.gogogo.mascot.MascotMode
import com.zcshou.gogogo.mascot.MascotPeekView
import com.zcshou.gogogo.mascot.MascotPrefs
import com.zcshou.gogogo.mascot.MascotState
import com.zcshou.gogogo.mascot.MascotTouchProxy
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 支持云迹：微信赞赏码，纯静态，不做支付集成。
 *
 * 交互对齐 BiliPai 的捐赠弹窗：浅/深两套赞赏码、点击放大、长按保存前确认、
 * 保存后引导打开微信。赞赏码是本地图片，不接支付 SDK、不发网络请求；
 * Android 10+ 写 MediaStore 不需要权限，9 及以下按需申请一次存储权限。
 */
class SupportActivity : AppCompatActivity() {

    companion object {
        /** 浅色赞赏码；未放入图片时页面给出可读的空状态，不影响构建。 */
        private const val QR_BASE = "support_wechat_qr"

        /** 可选：微信生成的深色卡面赞赏码；缺失时回退到浅色版。 */
        private const val QR_DARK = "support_wechat_qr_dark"

        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val STATE_SAVED = "state_saved"

        fun intent(context: Context): Intent = Intent(context, SupportActivity::class.java)
    }

    private var qrResId = 0
    private var saving = false
    private var saved = false

    private lateinit var qrImage: ImageView
    private lateinit var qrEmpty: TextView
    private lateinit var qrHint: TextView
    private lateinit var enlargeButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var openWechatButton: TextView
    private lateinit var saveAgainButton: TextView
    private lateinit var savedText: TextView
    private lateinit var errorText: TextView
    private lateinit var mascot: MascotPeekView
    private lateinit var supportEggCard: MascotEggCardView

    /** Android 9 及以下保存到公共相册需要一次存储权限；10+ 走 MediaStore，不申请。 */
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) performSave() else showError(getString(R.string.support_permission_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        saved = savedInstanceState?.getBoolean(STATE_SAVED, false) ?: false
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_support)
        ThemeHelper.padForSystemBars(findViewById(R.id.supportRoot))

        findViewById<View>(R.id.btnSupportBack).setOnClickListener { finish() }

        qrImage = findViewById(R.id.supportQrImage)
        qrEmpty = findViewById(R.id.supportQrEmpty)
        qrHint = findViewById(R.id.supportQrHint)
        enlargeButton = findViewById(R.id.btnSupportEnlarge)
        saveButton = findViewById(R.id.btnSupportSave)
        openWechatButton = findViewById(R.id.btnSupportOpenWechat)
        saveAgainButton = findViewById(R.id.btnSupportSaveAgain)
        savedText = findViewById(R.id.supportSavedText)
        errorText = findViewById(R.id.supportErrorText)
        mascot = findViewById(R.id.supportMascot)
        MascotTouchProxy.install(findViewById(R.id.supportScroll), mascot)
        supportEggCard = findViewById(R.id.supportEggCard)
        if (MascotPrefs.mode(this) != MascotMode.OFF) {
            supportEggCard.bind(MascotEgg.RICE_BOWL)
        }
        bindMascot()

        qrResId = resolveQrRes()
        if (qrResId == 0) {
            qrImage.visibility = View.GONE
            enlargeButton.visibility = View.GONE
            qrHint.visibility = View.GONE
            qrEmpty.visibility = View.VISIBLE
            saveButton.visibility = View.GONE
            return
        }

        qrImage.setImageResource(qrResId)
        qrEmpty.visibility = View.GONE
        qrImage.setOnClickListener { showPreview() }
        qrImage.setOnLongClickListener {
            confirmSave()
            true
        }
        enlargeButton.setOnClickListener { showPreview() }
        saveButton.setOnClickListener { confirmSave() }
        saveAgainButton.setOnClickListener { confirmSave() }
        openWechatButton.setOnClickListener { openWechat() }
        updateActionState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SAVED, saved)
    }

    override fun onResume() {
        super.onResume()
        if (::mascot.isInitialized) bindMascot()
    }

    override fun onPause() {
        if (::mascot.isInitialized) mascot.pause()
        super.onPause()
    }

    private fun bindMascot() {
        val mode = MascotPrefs.mode(this)
        if (mode == MascotMode.OFF) {
            mascot.bind(MascotMode.OFF, MascotState.PEEK_IDLE)
            return
        }
        mascot.bind(mode, MascotState.CHEER, MascotDialogueEngine.State.SUPPORT)
    }

    /** 彩蛋资源缺失时卡片会自动隐藏，不会留下空占位。 */
    private fun showSupportEgg(egg: MascotEgg) {
        if (MascotPrefs.mode(this) == MascotMode.OFF) return
        // 访问支持页本身即解锁「白米饭」；保存/预览时再解锁对应彩蛋
        MascotEggStore.unlock(this, MascotEgg.RICE_BOWL)
        MascotEggStore.unlock(this, egg)
        supportEggCard.showFor(egg, 3500L)
    }

    /** 深色主题优先用微信生成的深色卡面，缺失时回退浅色版。 */
    private fun resolveQrRes(): Int {
        if (ThemeHelper.isDark(this)) {
            val dark = resources.getIdentifier(QR_DARK, "drawable", packageName)
            if (dark != 0) return dark
        }
        return resources.getIdentifier(QR_BASE, "drawable", packageName)
    }

    // ---------------- 保存 ----------------

    /** 长按、按钮都先确认一次，避免误触把图片写进相册。 */
    private fun confirmSave() {
        if (qrResId == 0 || saving) return
        AlertDialog.Builder(this)
            .setTitle(R.string.support_save_confirm_title)
            .setMessage(R.string.support_save_confirm_message)
            .setPositiveButton(R.string.support_save) { _, _ -> requestSave() }
            .setNegativeButton(R.string.support_cancel, null)
            .show()
    }

    private fun requestSave() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        performSave()
    }

    private fun performSave() {
        if (qrResId == 0 || saving) return
        saving = true
        errorText.visibility = View.GONE
        updateActionState()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeQrToGallery() }.getOrDefault(false)
            }
            saving = false
            if (ok) {
                saved = true
                showSupportEgg(MascotEgg.TOAST)
            } else {
                showError(getString(R.string.support_save_failed))
            }
            updateActionState()
        }
    }

    private fun writeQrToGallery(): Boolean {
        val bitmap = BitmapFactory.decodeResource(resources, qrResId) ?: return false
        val name = "CloudTrace_support_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/CloudTrace"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "CloudTrace"
                )
                if (!dir.isDirectory && !dir.mkdirs()) {
                    bitmap.recycle()
                    return false
                }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                bitmap.recycle()
                return false
            }
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun updateActionState() {
        saveButton.visibility = if (saved) View.GONE else View.VISIBLE
        saveButton.text = getString(if (saving) R.string.support_saving else R.string.support_save)
        openWechatButton.visibility = if (saved) View.VISIBLE else View.GONE
        saveAgainButton.visibility = if (saved) View.VISIBLE else View.GONE
        savedText.visibility = if (saved) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    // ---------------- 放大 / 打开微信 ----------------

    /** 全屏预览：点任意位置关闭，长按保存；不额外申请权限或读写文件。 */
    private fun showPreview() {
        if (qrResId == 0) return
        showSupportEgg(MascotEgg.TOAST)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val image = ImageView(this).apply {
            setImageResource(qrResId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.support_qr_label)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val hint = TextView(this).apply {
            text = getString(R.string.support_preview_hint)
            setTextColor(0xFFB3B3B3.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        root.addView(image)
        root.addView(hint)
        root.setOnClickListener { dialog.dismiss() }
        image.setOnClickListener { dialog.dismiss() }
        image.setOnLongClickListener {
            confirmSave()
            true
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }

    private fun openWechat() {
        val intent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
        if (intent == null) {
            showError(getString(R.string.support_wechat_unavailable))
            return
        }
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showError(getString(R.string.support_wechat_unavailable)) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
