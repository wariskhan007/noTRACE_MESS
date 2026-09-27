package com.notrace.messenger.attachment.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a voice note to a temp file using MediaRecorder (AAC/M4A -
 * a standard, small, widely-compatible codec/container, not a custom
 * format - matches plan rule "never invent" applied broadly to codecs
 * too, not just cryptography). The temp file lives in the app's cache
 * dir; once recording stops, the caller reads it into memory and hands
 * it to AttachmentTransferManager.sendAttachmentBytes (kind=AUDIO),
 * then the temp file is deleted - it was never the "real" copy, the
 * encrypted attachment store is.
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Starts recording. Caller must already hold RECORD_AUDIO permission. */
    fun start(): Boolean {
        if (isRecording) return false
        val file = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            true
        } catch (e: Exception) {
            mediaRecorder.release()
            file.delete()
            false
        }
    }

    /** Stops recording and returns the recorded bytes, or null if nothing was recorded / recording failed. */
    fun stopAndRead(): ByteArray? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        return try {
            mediaRecorder.stop()
            file?.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            mediaRecorder.release()
            file?.delete()
            recorder = null
            outputFile = null
        }
    }

    /** Discards an in-progress recording without saving anything (e.g. user cancels). */
    fun cancel() {
        val mediaRecorder = recorder ?: return
        try { mediaRecorder.stop() } catch (e: Exception) { /* may not have recorded anything yet */ }
        mediaRecorder.release()
        outputFile?.delete()
        recorder = null
        outputFile = null
    }
}
