package com.lvoice.aiime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 录音器：记录 PCM 数据并保存为 WAV 格式。
 */
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File) {
        if (isRecording) return
        
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("AudioRecorder", "Invalid buffer size")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecorder", "AudioRecord initialization failed")
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        Thread {
            writeAudioDataToFile(outputFile)
        }.start()
    }

    private fun writeAudioDataToFile(outputFile: File) {
        val data = ByteArray(bufferSize)
        val os = FileOutputStream(outputFile)
        
        // Write placeholder for WAV header
        writeWavHeader(os, channelConfig, sampleRate, audioFormat, 0)

        var totalAudioLen: Long = 0
        try {
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                    totalAudioLen += read
                }
            }
            os.close()
            // Fix WAV header with actual length
            updateWavHeader(outputFile, totalAudioLen)
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recording failed", e)
        }
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun writeWavHeader(os: FileOutputStream, channels: Int, rate: Int, format: Int, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = 16 * rate * 1 / 8
        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF/WAVE header
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // 'fmt ' chunk
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = 1.toByte()
        header[23] = 0
        header[24] = (rate and 0xff).toByte()
        header[25] = (rate shr 8 and 0xff).toByte()
        header[26] = (rate shr 16 and 0xff).toByte()
        header[27] = (rate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2.toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        os.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val raf = RandomAccessFile(file, "rw")
        val totalDataLen = totalAudioLen + 36
        
        raf.seek(4) // RIFF size
        raf.write((totalDataLen and 0xff).toInt())
        raf.write((totalDataLen shr 8 and 0xff).toInt())
        raf.write((totalDataLen shr 16 and 0xff).toInt())
        raf.write((totalDataLen shr 24 and 0xff).toInt())

        raf.seek(40) // data size
        raf.write((totalAudioLen and 0xff).toInt())
        raf.write((totalAudioLen shr 8 and 0xff).toInt())
        raf.write((totalAudioLen shr 16 and 0xff).toInt())
        raf.write((totalAudioLen shr 24 and 0xff).toInt())
        raf.close()
    }
}
