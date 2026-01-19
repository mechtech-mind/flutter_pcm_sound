package com.lib.flutter_pcm_sound;

import android.os.Build;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * FlutterPcmSoundPlugin
 *
 * FINAL FIX:
 * - Handles WebRTC route changes (earpiece/speaker/Bluetooth)
 * - Rebinds AudioTrack dynamically
 * - Prevents silent second call
 * - Does NOT fight WebRTC AudioManager ownership
 */
public class FlutterPcmSoundPlugin
        implements FlutterPlugin, MethodChannel.MethodCallHandler {

    private static final String CHANNEL_NAME = "flutter_pcm_sound/methods";
    private static final int MAX_FRAMES_PER_BUFFER = 200;

    private MethodChannel channel;
    private Context context;
    private AudioManager audioManager;

    private AudioTrack audioTrack;
    private Thread playbackThread;
    private volatile boolean shouldStop = false;

    private int numChannels;
    private boolean didSetup = false;

    private long feedThreshold = 8000;
    private long totalFeeds = 0;
    private long lastLowFeed = 0;
    private long lastZeroFeed = 0;

    private final LinkedBlockingQueue<ByteBuffer> queue =
            new LinkedBlockingQueue<>();

    private AudioDeviceCallback deviceCallback;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        channel = new MethodChannel(
                binding.getBinaryMessenger(),
                CHANNEL_NAME
        );
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        cleanup();
    }

    // ─────────────────────────────────────────────

    @Override
    public void onMethodCall(
            @NonNull MethodCall call,
            @NonNull MethodChannel.Result result
    ) {
        try {
            switch (call.method) {

                case "setup": {
                    int sampleRate = call.argument("sample_rate");
                    numChannels = call.argument("num_channels");

                    if (didSetup) cleanup();

                    totalFeeds = 0;
                    lastLowFeed = 0;
                    lastZeroFeed = 0;

                    int channelMask =
                            (numChannels == 2)
                                    ? AudioFormat.CHANNEL_OUT_STEREO
                                    : AudioFormat.CHANNEL_OUT_MONO;

                    int minBuffer =
                            AudioTrack.getMinBufferSize(
                                    sampleRate,
                                    channelMask,
                                    AudioFormat.ENCODING_PCM_16BIT
                            );

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioTrack =
                                new AudioTrack.Builder()
                                        .setAudioAttributes(
                                                new AudioAttributes.Builder()
                                                        .setUsage(
                                                                AudioAttributes.USAGE_VOICE_COMMUNICATION
                                                        )
                                                        .setContentType(
                                                                AudioAttributes.CONTENT_TYPE_SPEECH
                                                        )
                                                        .build()
                                        )
                                        .setAudioFormat(
                                                new AudioFormat.Builder()
                                                        .setSampleRate(sampleRate)
                                                        .setEncoding(
                                                                AudioFormat.ENCODING_PCM_16BIT
                                                        )
                                                        .setChannelMask(channelMask)
                                                        .build()
                                        )
                                        .setBufferSizeInBytes(minBuffer)
                                        .setTransferMode(AudioTrack.MODE_STREAM)
                                        .build();
                    } else {
                        audioTrack =
                                new AudioTrack(
                                        AudioManager.STREAM_VOICE_CALL,
                                        sampleRate,
                                        channelMask,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        minBuffer,
                                        AudioTrack.MODE_STREAM
                                );
                    }

                    if (audioTrack.getState()
                            != AudioTrack.STATE_INITIALIZED) {
                        result.error(
                                "PCM",
                                "AudioTrack init failed",
                                null
                        );
                        return;
                    }

                    queue.clear();
                    shouldStop = false;

                    registerDeviceCallback();
                    rebindAudioRoute();

                    playbackThread =
                            new Thread(
                                    this::playbackLoop,
                                    "PCMPlaybackThread"
                            );
                    playbackThread.start();

                    didSetup = true;
                    result.success(true);
                    break;
                }

                case "feed": {
                    if (!didSetup) {
                        result.error(
                                "PCM",
                                "setup() not called",
                                null
                        );
                        return;
                    }

                    byte[] buf = call.argument("buffer");
                    for (ByteBuffer b : split(buf, MAX_FRAMES_PER_BUFFER)) {
                        queue.offer(b);
                    }
                    totalFeeds++;
                    result.success(true);
                    break;
                }

                case "setFeedThreshold": {
                    feedThreshold =
                            ((Number) call.argument("feed_threshold"))
                                    .longValue();
                    result.success(true);
                    break;
                }

                case "release": {
                    cleanup();
                    result.success(true);
                    break;
                }

                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            result.error("PCM", e.toString(), sw.toString());
        }
    }

    // ─────────────────────────────────────────────

    private void playbackLoop() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_AUDIO
        );

        audioTrack.play();

        while (!shouldStop) {
            try {
                ByteBuffer data =
                        queue.poll(200, TimeUnit.MILLISECONDS);
                if (data == null || data.remaining() == 0) continue;

                audioTrack.write(
                        data,
                        data.remaining(),
                        AudioTrack.WRITE_BLOCKING
                );

                long remaining =
                        queue.stream()
                                .mapToLong(ByteBuffer::remaining)
                                .sum() / (2 * numChannels);

                boolean low =
                        remaining <= feedThreshold
                                && lastLowFeed != totalFeeds;
                boolean zero =
                        remaining == 0
                                && lastZeroFeed != totalFeeds;

                if (low || zero) {
                    if (low) lastLowFeed = totalFeeds;
                    if (zero) lastZeroFeed = totalFeeds;
                    long rf = remaining;
                    mainHandler.post(
                            () -> invokeFeedCallback(rf)
                    );
                }

            } catch (InterruptedException ignored) {
            }
        }

        Log.w("PCM", "Playback thread exited");
    }

    // ─────────────────────────────────────────────

    private void registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < 23) return;

        deviceCallback =
                new AudioDeviceCallback() {
                    @Override
                    public void onAudioDevicesAdded(
                            AudioDeviceInfo[] added
                    ) {
                        logDevices("ADDED", added);
                        rebindAudioRoute();
                    }

                    @Override
                    public void onAudioDevicesRemoved(
                            AudioDeviceInfo[] removed
                    ) {
                        logDevices("REMOVED", removed);
                        rebindAudioRoute();
                    }
                };

        audioManager.registerAudioDeviceCallback(
                deviceCallback,
                mainHandler
        );
    }

    private void rebindAudioRoute() {
        if (Build.VERSION.SDK_INT < 23 || audioTrack == null) return;

        for (AudioDeviceInfo d :
                audioManager.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                )) {
            if (d.getType()
                    == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    || d.getType()
                    == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    || d.getType()
                    == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {

                boolean ok =
                        audioTrack.setPreferredDevice(d);
                Log.w(
                        "PCM-ROUTE",
                        "Rebind → " + d.getProductName()
                                + " success=" + ok
                );
                return;
            }
        }
    }

    private void logDevices(String tag, AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo d : devices) {
            Log.w(
                    "PCM-ROUTE",
                    tag + " " + d.getProductName()
                            + " type=" + d.getType()
            );
        }
    }

    // ─────────────────────────────────────────────

    private void cleanup() {
        shouldStop = true;

        queue.offer(ByteBuffer.allocate(0));

        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(300);
            } catch (InterruptedException ignored) {}
            playbackThread = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
            } catch (Exception ignored) {}
            audioTrack.release();
            audioTrack = null;
        }

        if (deviceCallback != null
                && Build.VERSION.SDK_INT >= 23) {
            audioManager.unregisterAudioDeviceCallback(
                    deviceCallback
            );
            deviceCallback = null;
        }

        queue.clear();
        didSetup = false;

        Log.w("PCM", "Cleanup completed");
    }

    // ─────────────────────────────────────────────

    private void invokeFeedCallback(long frames) {
        Map<String, Object> map = new HashMap<>();
        map.put("remaining_frames", frames);
        channel.invokeMethod("OnFeedSamples", map);
    }

    private List<ByteBuffer> split(byte[] buf, int max) {
        List<ByteBuffer> out = new ArrayList<>();
        for (int i = 0; i < buf.length; i += max) {
            out.add(
                    ByteBuffer.wrap(
                            buf,
                            i,
                            Math.min(max, buf.length - i)
                    )
            );
        }
        return out;
    }
}
