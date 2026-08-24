package com.libreplayer.media.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class Media3AudioPipelineCharacterizationTest {
    @Test
    fun `default integer conversion keeps sample rate and channels but outputs pcm16`() {
        val processor = ToInt16PcmAudioProcessor()

        val outputFormat = processor.configure(
            AudioProcessor.AudioFormat(
                96_000,
                2,
                C.ENCODING_PCM_24BIT,
            ),
        )

        assertThat(outputFormat.sampleRate).isEqualTo(96_000)
        assertThat(outputFormat.channelCount).isEqualTo(2)
        assertThat(outputFormat.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
    }

    @Test
    fun `pcm24 to pcm16 conversion discards the low byte without dithering`() {
        val processor = ToInt16PcmAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_24BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        processor.queueInput(
            directBuffer(
                0x00, 0x00, 0x00, // zero
                0x01, 0x00, 0x00, // least-significant 24-bit step
                0x00, 0x01, 0x00, // one 16-bit step
                0xff, 0xff, 0x7f, // largest positive value
                0x00, 0x00, 0x80, // most negative value
            ),
        )

        assertThat(processor.getOutput().remainingBytes()).isEqualTo(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x00,
                0x01, 0x00,
                0xff.toByte(), 0x7f,
                0x00, 0x80.toByte(),
            ),
        )
    }

    @Test
    fun `pcm32 to pcm16 conversion discards the low two bytes without dithering`() {
        val processor = ToInt16PcmAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(192_000, 1, C.ENCODING_PCM_32BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        processor.queueInput(
            directBuffer(
                0x00, 0x00, 0x00, 0x00, // zero
                0x01, 0x00, 0x00, 0x00, // least-significant 32-bit step
                0x00, 0x00, 0x01, 0x00, // one 16-bit step
                0xff, 0xff, 0xff, 0x7f, // largest positive value
                0x00, 0x00, 0x00, 0x80, // most negative value
            ),
        )

        assertThat(processor.getOutput().remainingBytes()).isEqualTo(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x00,
                0x01, 0x00,
                0xff.toByte(), 0x7f,
                0x00, 0x80.toByte(),
            ),
        )
    }

    @Test
    fun `float to pcm16 conversion clamps full scale and truncates fractions`() {
        val processor = ToInt16PcmAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(6 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                putFloat(-2.0f)
                putFloat(-1.0f)
                putFloat(0.0f)
                putFloat(1.0f / 65_536.0f)
                putFloat(0.5f)
                putFloat(2.0f)
                flip()
            }

        processor.queueInput(input)

        val output = processor.getOutput().order(ByteOrder.nativeOrder())
        assertThat(List(output.remaining() / Short.SIZE_BYTES) { output.short }).isEqualTo(
            listOf(
                (-32_767).toShort(),
                (-32_767).toShort(),
                0.toShort(),
                0.toShort(),
                16_383.toShort(),
                32_767.toShort(),
            ),
        )
    }

    @Test
    fun `encoder delay and padding trimming removes exact stereo frames at stream boundary`() {
        val audioFormat = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        val processor = TrimmingAudioProcessor().apply {
            setTrimFrameCount(2, 3)
            configure(audioFormat)
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val input = ByteBuffer.allocateDirect(10 * 2 * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(10) { frame ->
            input.putShort(frame.toShort())
            input.putShort((-frame).toShort())
        }
        input.flip()

        processor.queueInput(input)
        val beforeEndOfStream = processor.getOutput().remainingBytes()
        // The sink configures the next stream before ending the current one. That pending
        // reconfiguration tells Media3 to discard the buffered encoder padding at this boundary.
        processor.configure(audioFormat)
        processor.queueEndOfStream()
        val afterEndOfStream = processor.getOutput().remainingBytes()

        assertThat(beforeEndOfStream + afterEndOfStream).isEqualTo(
            shortsToBytes(
                2, -2,
                3, -3,
                4, -4,
                5, -5,
                6, -6,
            ),
        )
        assertThat(processor.trimmedFrameCount).isEqualTo(5L)
        assertThat(processor.isEnded).isTrue()
    }

    @Test
    fun `pcm16 input needs no integer conversion`() {
        val processor = ToInt16PcmAudioProcessor()

        val outputFormat = processor.configure(
            AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
        )

        assertThat(outputFormat).isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(processor.isActive).isFalse()
    }

    private fun directBuffer(vararg unsignedBytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(unsignedBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                unsignedBytes.forEach { put(it.toByte()) }
                flip()
            }

    private fun shortsToBytes(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun ByteBuffer.remainingBytes(): ByteArray =
        ByteArray(remaining()).also(::get)
}
