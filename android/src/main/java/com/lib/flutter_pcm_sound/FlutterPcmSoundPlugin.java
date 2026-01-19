package com.lib.flutter_pcm_sound;

import android.os.Build;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioAttributes;
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
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * FlutterPcmSoundPlugin
 *
 * MODIFIED FOR VOICE / TELEPHONY USE-CASE:
 * - Routes audio to earpiece correctly
 * - Uses VOICE_COMMUNICATION audio attributes
 * - Works alongside WebRTC / SIP calls
 */
public class FlutterPcmSoundPlugin implements
        FlutterPlugin,
        MethodChannel.MethodCallHandler {

    private static final String CHANNEL_NAME = "flutter_pcm_sound/methods";
    private static final int MAX_FRAMES_PER_BUFFER = 200;

    private MethodChannel mMethodChannel;
    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private Thread playbackThread;
    private volatile boolean mShouldCleanup = false;

    private AudioTrack mAudioTrack;
    private int mNumChannels;
    private int mMinBufferSize;
    private boolean mDidSetup = false;

    private long mFeedThreshold = 8000;
    private long mTotalFeeds = 0;
    private long mLastLowBufferFeed = 0;
    private long mLastZeroFeed = 0;

    // Thread-safe queue for PCM buffers
    private final LinkedBlockingQueue<ByteBuffer> mSamples = new LinkedBlockingQueue<>();

    private Context applicationContext;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        BinaryMessenger messenger = binding.getBinaryMessenger();
        applicationContext = binding.getApplicationContext();
        mMethodChannel = new MethodChannel(messenger, CHANNEL_NAME);
        mMethodChannel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        mMethodChannel.setMethodCallHandler(null);
        cleanup();
    }

    @Override
    @SuppressWarnings("deprecation") // Needed for compatibility with Android < 23
    public void onMethodCall(@NonNull MethodCall call,
                             @NonNull MethodChannel.Result result) {
        try {
            switch (call.method) {

                case "setup": {
                    int sampleRate = call.argument("sample_rate");
                    mNumChannels = call.argument("num_channels");

                    // Cleanup any previous instance
                    cleanup();

                    mTotalFeeds = 0;
                    mLastLowBufferFeed = 0;
                    mLastZeroFeed = 0;


                    // ===============================
                    // IMPORTANT: TELEPHONY AUDIO MODE
                    // ===============================
                    //
                    // Without MODE_IN_COMMUNICATION,
                    // Android may still route VOICE audio to speaker.
                    //
                    AudioManager audioManager =
                            (AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(false);

                    int channelConfig = (mNumChannels == 2)
                            ? AudioFormat.CHANNEL_OUT_STEREO
                            : AudioFormat.CHANNEL_OUT_MONO;

                    mMinBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

                    if (mMinBufferSize <= 0) {
                        result.error("AudioTrackError", "Invalid buffer size", null);
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // =========================================================
                        // CRITICAL FIX:
                        // Use VOICE_COMMUNICATION instead of MEDIA
                        // This allows routing to the EARPIECE
                        // =========================================================
                        mAudioTrack = new AudioTrack.Builder()
                                .setAudioAttributes(
                                        new AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                .build()
                                )
                                .setAudioFormat(
                                        new AudioFormat.Builder()
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .setSampleRate(sampleRate)
                                                .setChannelMask(channelConfig)
                                                .build()
                                )
                                .setBufferSizeInBytes(mMinBufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();
                    } else {
                        // =========================================================
                        // LEGACY DEVICES (< API 23)
                        // STREAM_VOICE_CALL ensures earpiece routing
                        // =========================================================
                        mAudioTrack = new AudioTrack(
                                AudioManager.STREAM_VOICE_CALL,
                                sampleRate,
                                channelConfig,
                                AudioFormat.ENCODING_PCM_16BIT,
                                mMinBufferSize,
                                AudioTrack.MODE_STREAM
                        );
                    }

                    if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        result.error("AudioTrackError", "AudioTrack init failed", null);
                        return;
                    }

                    mSamples.clear();
                    mShouldCleanup = false;

                    playbackThread = new Thread(this::playbackThreadLoop, "PCMPlaybackThread");
                    playbackThread.setPriority(Thread.MAX_PRIORITY);
                    playbackThread.start();

                    mDidSetup = true;
                    result.success(true);
                    break;
                }

                case "feed": {
                    if (!mDidSetup) {
                        result.error("Setup", "Must call setup() first", null);
                        return;
                    }

                    byte[] buffer = call.argument("buffer");
                    List<ByteBuffer> chunks = split(buffer, MAX_FRAMES_PER_BUFFER);

                    synchronized (mSamples) {
                        for (ByteBuffer chunk : chunks) {
                            mSamples.add(chunk);
                        }
                        mTotalFeeds++;
                    }

                    result.success(true);
                    break;
                }

                case "setFeedThreshold": {
                    long feedThreshold =
                            ((Number) call.argument("feed_threshold")).longValue();
                    synchronized (mSamples) {
                        mFeedThreshold = feedThreshold;
                    }
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
            result.error("androidException", e.toString(), sw.toString());
        }
    }

    /**
     * Playback loop running on high-priority audio thread
     */
    private void playbackThreadLoop() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_AUDIO
        );

        mAudioTrack.play();

        while (!mShouldCleanup) {
            try {
                        ByteBuffer data = mSamples.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (data == null || data.remaining() == 0) {
                            continue;
                        }

                        mAudioTrack.write(
                                data,
                                data.remaining(),
                                AudioTrack.WRITE_BLOCKING
                        );


                long remainingFrames;
                long totalFeeds;
                long feedThreshold;

                synchronized (mSamples) {
                    long totalBytes = 0;
                    for (ByteBuffer b : mSamples) {
                        totalBytes += b.remaining();
                    }
                    remainingFrames = totalBytes / (2 * mNumChannels);
                    totalFeeds = mTotalFeeds;
                    feedThreshold = mFeedThreshold;
                }

                boolean low =
                        remainingFrames <= feedThreshold &&
                        mLastLowBufferFeed != totalFeeds;

                boolean zero =
                        remainingFrames == 0 &&
                        mLastZeroFeed != totalFeeds;

                if (low || zero) {
                    if (low) mLastLowBufferFeed = totalFeeds;
                    if (zero) mLastZeroFeed = totalFeeds;
                    long rf = remainingFrames;
                    mainThreadHandler.post(() -> invokeFeedCallback(rf));
                }

            } catch (InterruptedException ignored) {
            }
        }

        Log.w("PCM", "playback thread exiting");

    }

            private void cleanup() {
                mShouldCleanup = true;

                // unblock queue
                mSamples.offer(ByteBuffer.allocate(0));

                if (playbackThread != null) {
                    playbackThread.interrupt();
                    try {
                        playbackThread.join(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    playbackThread = null;
                }

                if (mAudioTrack != null) {
                    try {
                        mAudioTrack.pause();
                        mAudioTrack.flush();
                        mAudioTrack.stop();
                    } catch (Exception ignored) {}
                    mAudioTrack.release();
                    mAudioTrack = null;
                }

                AudioManager audioManager =
                        (AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);
                audioManager.setMode(AudioManager.MODE_NORMAL);

                mSamples.clear();
                mDidSetup = false;

                Log.w("PCM", "cleanup() called | thread=" + playbackThread);

            }



    private void invokeFeedCallback(long remainingFrames) {
        Map<String, Object> map = new HashMap<>();
        map.put("remaining_frames", remainingFrames);
        mMethodChannel.invokeMethod("OnFeedSamples", map);
    }

    private List<ByteBuffer> split(byte[] buffer, int maxSize) {
        List<ByteBuffer> out = new ArrayList<>();
        int offset = 0;
        while (offset < buffer.length) {
            int len = Math.min(buffer.length - offset, maxSize);
            out.add(ByteBuffer.wrap(buffer, offset, len));
            offset += len;
        }
        return out;
    }
}
