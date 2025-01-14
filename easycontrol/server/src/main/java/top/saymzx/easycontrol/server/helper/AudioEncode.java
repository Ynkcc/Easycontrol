/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.helper;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.system.ErrnoException;

import java.io.IOException;
import java.nio.ByteBuffer;

import top.saymzx.easycontrol.server.Server;
import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.Options;

public final class AudioEncode {
  private static MediaCodec encedec;
  private static AudioRecord audioCapture;
  private static boolean isOpusEncoderSupport;

  public static boolean init() throws IOException, ErrnoException {
    byte[] bytes = new byte[]{0};
    try {
      // 从安卓12开始支持音频
      if (!Options.isAudio || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new Exception("版本低");
      isOpusEncoderSupport = Options.useOpus && Device.isEncoderSupport("opus");
      setAudioEncodec();
      encedec.start();
      audioCapture = AudioCapture.init();
    } catch (Exception ignored) {
      Server.writeMain(bytes);
      return false;
    }
    bytes[0] = 1;
    Server.writeMain(bytes);
    bytes[0] = (byte) (isOpusEncoderSupport ? 1 : 0);
    Server.writeMain(bytes);
    encodeIn();
    encodeOut();
    return true;
  }

  private static void setAudioEncodec() throws IOException {
    String codecMime = isOpusEncoderSupport ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
    encedec = MediaCodec.createEncoderByType(codecMime);
    MediaFormat encodecFormat = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
    encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
    if (!isOpusEncoderSupport) encodecFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
  }

  private static final int frameSize = AudioCapture.millisToBytes(20);

  public static void encodeIn() {
    try {
      int inIndex;
      do inIndex = encedec.dequeueInputBuffer(-1); while (inIndex < 0);
      audioCapture.read(encedec.getInputBuffer(inIndex), frameSize);
      encedec.queueInputBuffer(inIndex, 0, frameSize, 0, 0);
    } catch (IllegalStateException ignored) {
    }
  }

  private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

  public static void encodeOut() throws IOException {
    try {
      // 找到已完成的输出缓冲区
      int outIndex;
      do outIndex = encedec.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
      ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
      if (isOpusEncoderSupport) {
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
          buffer.getLong();
          int size = (int) buffer.getLong();
          buffer.limit(buffer.position() + size);
        }
        // 当无声音时不发送
        if (buffer.remaining() < 5) {
          encedec.releaseOutputBuffer(outIndex, false);
          return;
        }
      }
      int frameSize = buffer.remaining();
      ByteBuffer byteBuffer = ByteBuffer.allocate(5 + frameSize);
      byteBuffer.put((byte) 1);
      byteBuffer.putInt(frameSize);
      byteBuffer.put(buffer);
      byteBuffer.flip();
      Server.writeMain(byteBuffer.array());
      encedec.releaseOutputBuffer(outIndex, false);
    } catch (IllegalStateException ignored) {
    }
  }

  public static void release() {
    try {
      audioCapture.stop();
      audioCapture.release();
      encedec.stop();
      encedec.release();
    } catch (Exception ignored) {
    }
  }
}

