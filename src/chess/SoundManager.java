package chess;

import javax.sound.sampled.*;
import java.io.*;

/**
 * 音效管理器（使用纯Java合成音，无需外部音频文件）
 */
public class SoundManager {
    private boolean enabled = true;

    public void setEnabled(boolean e){ this.enabled=e; }
    public boolean isEnabled(){ return enabled; }

    /** 落子音：短促清脆 */
    public void playMove() { play(880, 80, 0.4f); }

    /** 吃子音：稍低沉 */
    public void playCapture() { play(440, 120, 0.6f); }

    /** 将军音：双音警示 */
    public void playCheck() {
        play(660, 80, 0.5f);
        delay(100);
        play(880, 80, 0.5f);
    }

    /** 胜利音：上升三音 */
    public void playWin() {
        play(523, 150, 0.5f); delay(160);
        play(659, 150, 0.5f); delay(160);
        play(784, 250, 0.6f);
    }

    /** 失败音：下降三音 */
    public void playLose() {
        play(784, 150, 0.5f); delay(160);
        play(659, 150, 0.5f); delay(160);
        play(523, 250, 0.5f);
    }

    /** 超时音：低沉长音 */
    public void playTimeout() { play(330, 400, 0.6f); }

    // ---- 合成正弦波 ----
    private void play(int freq, int durationMs, float volume) {
        if (!enabled) return;
        new Thread(() -> {
            try {
                AudioFormat fmt = new AudioFormat(44100, 16, 1, true, false);
                int samples = 44100 * durationMs / 1000;
                byte[] data = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    double angle = 2.0 * Math.PI * freq * i / 44100;
                    // 加包络：淡入淡出，避免爆音
                    double env = 1.0;
                    int fade = Math.min(500, samples/4);
                    if (i < fade) env = (double)i/fade;
                    else if (i > samples-fade) env = (double)(samples-i)/fade;
                    short s = (short)(Math.sin(angle)*env*volume*Short.MAX_VALUE);
                    data[i*2]   = (byte)(s & 0xFF);
                    data[i*2+1] = (byte)((s >> 8) & 0xFF);
                }
                SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, data.length);
                line.start();
                line.write(data, 0, data.length);
                line.drain();
                line.close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void delay(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
