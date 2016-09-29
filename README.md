// https://developer.android.com/samples/Camera2Video/project.html
MediaRecorder 的使用，只可以录制一个文件，不可以暂停后接着录制
// 设置 Audio资源 CAMCORDER 效果更好，假如没有，会默认使用 MIC
mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
//mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
// video 资源  Using a Surface as video source. MediaRecorder#getSurface()
mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
// 设置视频图像的录入源(从摄像头)
// mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
//  设置捕获(从摄像头)视频图像的预览界面
// mMediaRecorder.setPreviewDisplay(Surface);
//set output
mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
// 输出的文件
mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
// video 相关设置 码率，帧率，大小，编码
mMediaRecorder.setVideoEncodingBitRate(10000000);
mMediaRecorder.setVideoFrameRate(30);
mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//setup audio
mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
mMediaRecorder.setAudioEncodingBitRate(44800);
// 设置 方向
int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();
switch (mSensorOrientation) {
    case SENSOR_ORIENTATION_DEFAULT_DEGREES:
        mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
        break;
    case SENSOR_ORIENTATION_INVERSE_DEGREES:
        mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
        break;
}
mMediaRecorder.prepare();

// 视频的处理
com.googlecode.mp4parser.authoring.Movie 合并视频片段，实现接续录制

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
        mCacheFiles.clear();
        mCacheFiles.add(getVideoFile());
        FileChannel fc = new RandomAccessFile(mCacheFiles.get(0), "rw").getChannel();
        out.writeContainer(fc);
        fc.close();

        playFinalVideo();