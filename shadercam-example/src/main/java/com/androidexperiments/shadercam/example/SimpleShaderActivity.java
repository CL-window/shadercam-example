package com.androidexperiments.shadercam.example;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.androidexperiments.shadercam.example.gl.ExampleRenderer;
import com.androidexperiments.shadercam.fragments.CameraFragment;
import com.androidexperiments.shadercam.fragments.PermissionsHelper;
import com.androidexperiments.shadercam.gl.CameraRenderer;
import com.androidexperiments.shadercam.utils.ShaderUtils;
import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;

/**
 * Written by Anthony Tripaldi
 * <p/>
 * Very basic implemention of shader camera.
 */
public class SimpleShaderActivity extends FragmentActivity implements CameraRenderer.OnRendererReadyListener, PermissionsHelper.PermissionsListener {
    private static final String TAG = SimpleShaderActivity.class.getSimpleName();
    private static final String TAG_CAMERA_FRAGMENT = "tag_camera_frag";

    /**
     * filename for our test video output
     */
    private static final String TEST_VIDEO_FILE_NAME = "test_video.mp4";
    // 当前所有片段的缓存文件
    private List<File> mCacheFiles = new ArrayList<>();

    // CameraFragment.mMediaRecorder   用于录像+压缩编码，生成编码好的文件如mp4, 3gpp

    private MediaExtractor mMediaExtractor; // 用于音视频分路
    private MediaMuxer mMediaMuxer; // stop native error 只能支持一个audio track和一个video track，而且仅支持mp4输出

    private MediaPlayer mMediaPlayer; // 用于播放压缩编码后的音视频文件
    // AudioRecord用于录制PCM数据。AudioTrack用于播放PCM数据。PCM即原始音频采样数据


    /**
     * We inject our views from our layout xml here using {@link ButterKnife}
     */
    @InjectView(R.id.texture_view)
    TextureView mTextureView;
    @InjectView(R.id.btn_record)
    Button mRecordBtn;

    @InjectView(R.id.play_video)
    ImageView mImageView;

    /**
     * Custom fragment used for encapsulating all the {@link android.hardware.camera2} apis.
     */
    private CameraFragment mCameraFragment;

    /**
     * Our custom renderer for this example, which extends {@link CameraRenderer} and then adds custom
     * shaders, which turns shit green, which is easy.
     */
    private CameraRenderer mRenderer;

    /**
     * boolean for triggering restart of camera after completed rendering
     */
    private boolean mRestartCamera = false;

    private PermissionsHelper mPermissionsHelper;
    private boolean mPermissionsSatisfied = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.inject(this);

        setupCameraFragment();
        setupInteraction();

        //setup permissions for M or start normally
        if (PermissionsHelper.isMorHigher())
            setupPermissions();
    }

    private void setupPermissions() {
        mPermissionsHelper = PermissionsHelper.attach(this);
        mPermissionsHelper.setRequestedPermissions(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE

        );
    }

    /**
     * create the camera fragment responsible for handling camera state and add it to our activity
     */
    private void setupCameraFragment() {
        if (mCameraFragment != null && mCameraFragment.isAdded())
            return;

        mCameraFragment = CameraFragment.getInstance();
        mCameraFragment.setCameraToUse(CameraFragment.CAMERA_PRIMARY); //pick which camera u want to use, we default to forward
        mCameraFragment.setTextureView(mTextureView);

        //add fragment to our setup and let it work its magic
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(mCameraFragment, TAG_CAMERA_FRAGMENT);
        transaction.commit();
    }

    /**
     * add a listener for touch on our surface view that will pass raw values to our renderer for
     * use in our shader to control color channels.
     */
    private void setupInteraction() {
        mTextureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mRenderer instanceof ExampleRenderer) {
                    ((ExampleRenderer) mRenderer).setTouchPoint(event.getRawX(), event.getRawY());
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Things are good to go and we can continue on as normal. If this is called after a user
     * sees a dialog, then onResume will be called next, allowing the app to continue as normal.
     */
    @Override
    public void onPermissionsSatisfied() {
        Log.d(TAG, "onPermissionsSatisfied()");
        mPermissionsSatisfied = true;
    }

    /**
     * User did not grant the permissions needed for out app, so we show a quick toast and kill the
     * activity before it can continue onward.
     *
     * @param failedPermissions string array of which permissions were denied
     */
    @Override
    public void onPermissionsFailed(String[] failedPermissions) {
        Log.e(TAG, "onPermissionsFailed()" + Arrays.toString(failedPermissions));
        mPermissionsSatisfied = false;
        Toast.makeText(this, "shadercam needs all permissions to function, please try again.", Toast.LENGTH_LONG).show();
        this.finish();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "onResume()");

        ShaderUtils.goFullscreen(this.getWindow());
        mImageView.setVisibility(View.GONE);

        /**
         * if we're on M and not satisfied, check for permissions needed
         * {@link PermissionsHelper#checkPermissions()} will also instantly return true if we've
         * checked prior and we have all the correct permissions, allowing us to continue, but if its
         * false, we want to {@code return} here so that the popup will trigger without {@link #setReady(SurfaceTexture, int, int)}
         * being called prematurely
         */
        //
        if (PermissionsHelper.isMorHigher() && !mPermissionsSatisfied) {
            if (!mPermissionsHelper.checkPermissions())
                return;
            else
                mPermissionsSatisfied = true; //extra helper as callback sometimes isnt quick enough for future results
        }

        if (!mTextureView.isAvailable())
            mTextureView.setSurfaceTextureListener(mTextureListener); //set listener to handle when its ready
        else
            setReady(mTextureView.getSurfaceTexture(), mTextureView.getWidth(), mTextureView.getHeight());
    }

    @Override
    protected void onPause() {
        super.onPause();

        shutdownCamera(false);
        mTextureView.setSurfaceTextureListener(null);
    }

    /**
     * {@link ButterKnife} uses annotations to make setting {@link android.view.View.OnClickListener}'s
     * easier than ever with the {@link OnClick} annotation.
     */
    @OnClick(R.id.btn_record)
    public void onClickRecord() {
        if (mRenderer.isRecording())
            pauseRecording();
        else
            startRecording();
    }

    @OnClick(R.id.btn_swap_camera)
    public void onClickSwapCamera() {
        mCameraFragment.swapCamera();
    }

    /**
     * called whenever surface texture becomes initially available or whenever a camera restarts after
     * completed recording or resuming from onpause
     *
     * @param surface {@link SurfaceTexture} that we'll be drawing into
     * @param width   width of the surface texture
     * @param height  height of the surface texture
     */
    protected void setReady(SurfaceTexture surface, int width, int height) {
        mRenderer = getRenderer(surface, width, height);
        mRenderer.setCameraFragment(mCameraFragment);
        mRenderer.setOnRendererReadyListener(this);
        mRenderer.start();

        //initial config if needed
        mCameraFragment.configureTransform(width, height);
    }

    /**
     * Override this method for easy usage of stock example setup, allowing for easy
     * recording with any shader.
     */
    protected CameraRenderer getRenderer(SurfaceTexture surface, int width, int height) {
        return new ExampleRenderer(this, surface, width, height);
    }

    private void startRecording() {
        mCacheFiles.add(getVideoFile());
        mRenderer.startRecording(mCacheFiles.get(mCacheFiles.size() - 1));
        mRecordBtn.setText("Pause");
    }

    private void pauseRecording() {
        mRenderer.stopRecording();
        mRecordBtn.setText("Resume");

        //restart so surface is recreated
        shutdownCamera(true);

    }

    private void stopRecording() {
        mRenderer.stopRecording();
        mRecordBtn.setText("Record");

        //restart so surface is recreated
//        shutdownCamera(true);
        shutdownCamera(false);

        try {
            decodeVideo2();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    View.OnTouchListener playVideo = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if(event.getAction() == MotionEvent.ACTION_UP){
                playVideo();
            }
            return true;
        }
    };

    @OnClick(R.id.play_video)
    public void playVideo(){
        if(mMediaPlayer.isPlaying()){
            mMediaPlayer.pause();
            mImageView.setVisibility(View.VISIBLE);
        }else {
            mImageView.setVisibility(View.GONE);
            mMediaPlayer.start();
        }
    }

    @OnClick(R.id.btn_del)
    public void delLast(){
        if(mRenderer.isRecording()){
            showToast("请先暂停，再删除...");
            return;
        }
        if(mCacheFiles.size() > 0){
            mCacheFiles.get(mCacheFiles.size() - 1).delete();
            mCacheFiles.remove(mCacheFiles.size() - 1);
        }else{
            showToast("已无删除视频段...");
        }
    }

    @OnClick(R.id.btn_save)
    public void saveAll(){
        stopRecording();
    }

    private File getVideoFile() {

        try {
            return File.createTempFile("" + System.currentTimeMillis() ,".mp4",Environment.getExternalStorageDirectory());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new File(Environment.getExternalStorageDirectory(), TEST_VIDEO_FILE_NAME);
    }

    /**
     * kills the camera in camera fragment and shutsdown render thread
     *
     * @param restart whether or not to restart the camera after shutdown is complete
     */
    private void shutdownCamera(boolean restart) {
        //make sure we're here in a working state with proper permissions when we kill the camera
        if (PermissionsHelper.isMorHigher() && !mPermissionsSatisfied) return;

        //check to make sure we've even created the cam and renderer yet
        if (mCameraFragment == null || mRenderer == null) return;

        mCameraFragment.closeCamera();

        mRestartCamera = restart;
        mRenderer.getRenderHandler().sendShutdown();
        mRenderer = null;
    }

    /**
     * Interface overrides from our {@link com.androidexperiments.shadercam.gl.CameraRenderer.OnRendererReadyListener}
     * interface. Since these are being called from inside the CameraRenderer thread, we need to make sure
     * that we call our methods from the {@link #runOnUiThread(Runnable)} method, so that we don't
     * throw any exceptions about touching the UI from non-UI threads.
     * <p/>
     * Another way to handle this would be to create a Handler/Message system similar to how our
     * {@link com.androidexperiments.shadercam.gl.CameraRenderer.RenderHandler} works.
     */
    @Override
    public void onRendererReady() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCameraFragment.setPreviewTexture(mRenderer.getPreviewTexture());
                mCameraFragment.openCamera();
            }
        });
    }

    @Override
    public void onRendererFinished() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mRestartCamera) {
                    setReady(mTextureView.getSurfaceTexture(), mTextureView.getWidth(), mTextureView.getHeight());
                    mRestartCamera = false;
                }
            }
        });
    }


    /**
     * {@link android.view.TextureView.SurfaceTextureListener} responsible for setting up the rest of the
     * rendering and recording elements once our TextureView is good to go.
     */
    private TextureView.SurfaceTextureListener mTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, final int width, final int height) {
            //convenience method since we're calling it from two places
            setReady(surface, width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            mCameraFragment.configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(SimpleShaderActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playFinalVideo(){
        showToast("File recording complete: " + mCacheFiles.get(0).getAbsolutePath());
        // 拍摄完播放
        mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource(mCacheFiles.get(mCacheFiles.size() - 1).getAbsolutePath());
            mMediaPlayer.setSurface(new Surface(mTextureView.getSurfaceTexture()));
            mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mTextureView.setOnTouchListener(playVideo);
                    mp.start();
                }
            });
            mMediaPlayer.prepare();
            mMediaPlayer.setLooping(true);// 循环播放
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void decodeVideo() {

        if(mCacheFiles.size() == 1){
            playFinalVideo();
            return;
        }

        // 拼接视频片段
        try {


            //创建分离器
            mMediaExtractor = new MediaExtractor();
            mMediaExtractor.setDataSource(mCacheFiles.get(mCacheFiles.size() - 1).getAbsolutePath());

            mMediaMuxer = new MediaMuxer(getVideoFile().getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);


            MediaFormat mediaFormat;
            String mime;
            int width, height, videoMaxInputSize = 0, audioMaxInputSize;
            long videoDuration, audioDuration;


            //获取每个轨道的信息
            for (int i = 0; i < mMediaExtractor.getTrackCount(); i++) {
                mediaFormat = mMediaExtractor.getTrackFormat(i);
                mime = mediaFormat.getString(MediaFormat.KEY_MIME);

                if (mime.startsWith("video/")) {

                    width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                    height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    videoMaxInputSize = mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    videoDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
                    Log.i(TAG, "width and height is " + width + " " + height +
                            ";maxInputSize is " + videoMaxInputSize +
                            ";duration is " + (videoDuration/1000000));

                } else if (mime.startsWith("audio/")) {
                    int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    // 声道个数：单声道或双声道
                    int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    audioMaxInputSize = mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    audioDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
                    Log.i(TAG, "sampleRate is " + sampleRate + ";channelCount is " + channelCount +
                            ";audioMaxInputSize is " + audioMaxInputSize +
                            ";audioDuration is " + (audioDuration/1000000));

                }

            }


//            // done

            mMediaExtractor.release();
            mMediaExtractor = null;

        } catch (IOException e) {
            e.printStackTrace();
        }



    }


    // google iso
    private void decodeVideo2() throws IOException {

        if(mCacheFiles.size() == 1){
            playFinalVideo();
            return;
        }

        Movie[] inMovies = new Movie[mCacheFiles.size()];
        int index = 0;
        for (File video : mCacheFiles) {
            inMovies[index] = MovieCreator.build(video.getAbsolutePath());
            index++;
        }
        List<Track> videoTracks = new LinkedList<>();
        List<Track> audioTracks = new LinkedList<>();
        for (Movie m : inMovies) {
            for (Track t : m.getTracks()) {
                if (t.getHandler().equals("soun")) {
                    audioTracks.add(t);
                }
                if (t.getHandler().equals("vide")) {
                    videoTracks.add(t);
                }
            }
        }

        Movie result = new Movie();

        if (audioTracks.size() > 0) {
            result.addTrack(new AppendTrack(audioTracks.toArray(new Track[audioTracks.size()])));
        }
        if (videoTracks.size() > 0) {
            result.addTrack(new AppendTrack(videoTracks.toArray(new Track[videoTracks.size()])));
        }
        Container out = new DefaultMp4Builder().build(result);
        for (File file : mCacheFiles) {
            if(file.exists()){
                file.delete();
            }
        }
        mCacheFiles.clear();
        mCacheFiles.add(getVideoFile());
        FileChannel fc = new RandomAccessFile(mCacheFiles.get(0), "rw").getChannel();
        out.writeContainer(fc);
        fc.close();

        playFinalVideo();
    }
}
