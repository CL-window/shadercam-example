CameraFragment.mMediaRecorder   用于录像+压缩编码，生成编码好的文件如mp4, 3gpp
private MediaPlayer mMediaPlayer; // 用于播放压缩编码后的音视频文件
打开摄像头啥的还是标准流程，
CameraManager.openCamera(),然后预览时操作的对象都是CameraCaptureSession，获取不到data[]数据了
变化的是 CameraRenderer Renderer这个类，
虽然还是不太理解OpenGL,EGL 的博大精深，但是感觉照着这样一个套路使用，是可以实现效果的
大致流程：
1.初始化view,
2.尝试打开相机CameraManager.openCamera()，
3.打开相机成功，获得CameraDevice，尝试预览
4.预览成功，获得CameraCaptureSession，设置重复预览


// 视频的处理，继续录制，删除前一段，合成全部视频
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