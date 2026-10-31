package com.gathered.app

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import androidx.core.content.edit
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle

class MainActivity : FlutterActivity() {

    private var pendingPick: MethodChannel.Result? = null
    private var pendingNotifPermission: MethodChannel.Result? = null

    private var pendingGroupId: String? = null

    private companion object {
        const val PICK_REQUEST = 4711
        const val MAX_BYTES = 190_000 // the bucket cap is 204,800

        const val NOTIF_REQUEST = 4712
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChatChannel()
        readGroupExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readGroupExtra(intent)
    }

    private fun readGroupExtra(i: Intent?) {
        val id = i?.getStringExtra("group_id")
        if (!id.isNullOrEmpty()) {
            pendingGroupId = id
            i?.removeExtra("group_id")
        }
    }

    private fun createChatChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "chat", "Group chat", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "New messages in your groups" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "gathered/bridge")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "OPEN_GAME" -> {
                        startActivity(Intent(this, GameActivity::class.java))
                        result.success(null)
                    }
                    "LOAD_PROGRESS" -> {
                        val prefs = getSharedPreferences("gathered_save", MODE_PRIVATE)
                        result.success(prefs.getString("progress", null))
                    }
                    "SAVE_PROGRESS" -> {
                        val json = call.arguments as? String
                        if (json == null) {
                            result.error("BAD_ARGS", "Expected a JSON string", null)
                        } else {
                            getSharedPreferences("gathered_save", MODE_PRIVATE)
                                .edit {
                                    putString("progress", json)
                                }
                            result.success(null)
                        }
                    }
                    "PICK_IMAGE" -> {
                        if (pendingPick != null) {
                            result.error("BUSY", "Photo picker is already open", null)
                        } else {
                            pendingPick = result
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "image/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            try {
                                @Suppress("DEPRECATION")
                                startActivityForResult(intent, PICK_REQUEST)
                            } catch (_: Exception) {
                                pendingPick = null
                                result.error("NO_PICKER", "No photo picker available", null)
                            }
                        }
                    }
                    "REQUEST_NOTIFICATIONS" -> {
                        if (Build.VERSION.SDK_INT < 33 ||
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            result.success(true)
                        } else if (pendingNotifPermission != null) {
                            result.error("BUSY", "Permission prompt is already open", null)
                        } else {
                            pendingNotifPermission = result
                            requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIF_REQUEST
                            )
                        }
                    }
                    "GET_PUSH_TOKEN" -> {
                        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                            if (task.isSuccessful) result.success(task.result)
                            else result.success(null)
                        }
                    }
                    "GET_PENDING_GROUP" -> {
                        val id = pendingGroupId
                        pendingGroupId = null
                        result.success(id)
                    }
                    else -> result.notImplemented()
                }
            }
    }
    @Suppress("DEPRECATION")

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode != NOTIF_REQUEST) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        val result = pendingNotifPermission ?: return
        pendingNotifPermission = null
        result.success(
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingPick ?: return
        pendingPick = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            result.success(null) // cancelled
            return
        }
        Thread {
            try {
                val bytes = shrinkImage(uri)
                runOnUiThread { result.success(bytes) }
            } catch (e: Exception) {
                runOnUiThread { result.error("IMAGE_FAILED", e.message, null) }
            }
        }.start()
    }

    /** Center-crops to a square, scales to at most 512 px, JPEG under MAX_BYTES. */
    private fun shrinkImage(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) {
            sample *= 2
        }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Could not read the image")

        val rotation = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val side = minOf(bmp.width, bmp.height)
        val matrix = Matrix().apply {
            postRotate(rotation)
            val scale = minOf(1f, 512f / side)
            postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(
            bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, matrix, true)

        val buf = ByteArrayOutputStream()
        var quality = 90
        do {
            buf.reset()
            out.compress(Bitmap.CompressFormat.JPEG, quality, buf)
            quality -= 10
        } while (buf.size() > MAX_BYTES && quality >= 30)
        return buf.toByteArray()
    }
}