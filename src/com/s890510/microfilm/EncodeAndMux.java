/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.s890510.microfilm;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;

import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.s890510.microfilm.MicroMovieActivity.SaveCallback;
import com.s890510.microfilm.gles.EglCore;
import com.s890510.microfilm.gles.WindowSurface;
import com.s890510.microfilm.script.Carnival;
import com.s890510.microfilm.script.City;
import com.s890510.microfilm.script.Country;
import com.s890510.microfilm.script.Kids;
import com.s890510.microfilm.script.Life;
import com.s890510.microfilm.script.Lover;
import com.s890510.microfilm.script.Memory;
import com.s890510.microfilm.script.Script;
import com.s890510.microfilm.script.Sports;

/**
 * Generate an MP4 file using OpenGL ES drawing commands.  Demonstrates the use of MediaMuxer
 * and MediaCodec with Surface input.
 * <p>
 * This uses various features first available in Android "Jellybean" 4.3 (API 18).  There is
 * no equivalent functionality in previous releases.
 * <p>
 * (This was derived from bits and pieces of CTS tests, and is packaged as such, but is not
 * currently part of CTS.)
 */
public class EncodeAndMux implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "EncodeAndMuxTest";
    private static final boolean VERBOSE = false;           // lots of logging

    // where to put the output file (note: /sdcard requires WRITE_EXTERNAL_STORAGE permission)
    //private static final String OUTPUT_DIR = "/sdcard/";
    public static final String OUTPUT_DIR = Environment.getExternalStorageDirectory().toString()+"/Gallery/MicroFilm/";

    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 50;               // 30fps
    private static final int IFRAME_INTERVAL = 5;          // 1 second between I-frames
    //public static final int TOTAL_FRAMES = 900;  //30 seconds stand for 900 frames
    public int TOTAL_FRAMES;

    // size of a frame, in pixels
    private int mWidth = -1;
    private int mHeight = -1;
    // bit rate, in bits per second
    private int mBitRate = -1;

    // encoder / muxer state
    private MediaCodec mEncoder;
    private EglCore mEglCore;
    private WindowSurface mInputSurface;
    private MediaMuxer mMuxer;
    private int mVideoTrackIndex;
    private boolean mMuxerStarted;

    // allocate one of these up front so we don't need to do it every time
    private MediaCodec.BufferInfo mBufferInfo;

    private ProcessGL mProcessGL;
    private Context mContext;
    private MicroMovieActivity mActivity;
    private String mOutputPath;
    private String mVideoPath;
    private MediaFormat mVideoFormat;
    private ArrayList<MediaInfo> mFilesList;
    private ArrayList<ElementInfo> mFileOrder = new ArrayList<ElementInfo>();
    private Script mScript;
    private int mScriptSelect;
    
    private boolean mPause = false;
    private Object mLock = new Object();
    private boolean mIsCancel = false;

    public EncodeAndMux(MicroMovieActivity activity, ArrayList<MediaInfo> fileList,
            ArrayList<ElementInfo> fileOrder, Script script, int scriptSelect) {
        mContext = activity.getApplicationContext();
        mActivity = activity;
        
        TOTAL_FRAMES = mActivity.getDuration() * FRAME_RATE / 1000;
        
        mProcessGL = new ProcessGL(mActivity, true);
        
        mFilesList = fileList;
        mProcessGL.setMediaInfo(fileList);
        mFileOrder = fileOrder;
        mScript = script;
        mScriptSelect = scriptSelect;
    } 

    /**
     * Tests encoding of AVC video from a Surface.  The output is saved as an MP4 file.
     */
    public void testEncodeVideoToMp4(SaveCallback callback, final ProgressDialog progressDialog) {
        // QVGA at 2Mbps
        mWidth = MicroMovieActivity.mVisioWidth;
        mHeight = MicroMovieActivity.mVisioHeight;
        //mBitRate = 7741440; // 1280 * 720 * 30 * 4(high motion, low motion(5fps):1) * 0.07
        //mBitRate = (int)(mWidth * mHeight * 30 * 4 * 0.15);
        mBitRate = 20000000;
        
        //callback.onSaveDone("file://"+Environment.getExternalStorageDirectory().toString()+"/test.mp4");
        //return;
        Intent intent = new Intent();
        intent.setClassName("com.asus.ephotomusicprovider", "com.asus.ephotomusicprovider.EPhotoMusicProviderService");
        intent.setAction("com.asus.ephotomusicprovider.action");
        
        File outputDir = new File(OUTPUT_DIR);
        if(!outputDir.exists()){
        	outputDir.mkdirs();
        }
        
        boolean isException = false;

        try {          
            prepareEncoder();
            Log.e(TAG, "prepareEncoder");
            OpenglPrepare();
            Log.e(TAG, "OpenglPrepare");
            
            long timer = 0;
            //long interval = (NUM_FRAMES/FRAME_RATE)*1000;
            
            ElementInfo eInfo;
            int totalFrame = 0;
            
            int processNum = mScript.geteffectsize();
            

            for(int i=0; i< processNum && totalFrame < TOTAL_FRAMES && !Thread.currentThread().isInterrupted(); i++) {  
            	int numFrame;
            	long interval;
               

            	eInfo = mFileOrder.get(i);
                
                if(eInfo.Type == MediaInfo.MEDIA_TYPE_IMAGE) {
                	mProcessGL.changeBitmap(eInfo, true);
                }

         		if(eInfo.time == 0) // special case: sleep time = 0
         			mProcessGL.setSleepZero(eInfo.effect.getDuration());
         		
         		if(i == processNum - 1){ //slogan
         			numFrame = TOTAL_FRAMES - totalFrame;
         		}else{
	            	numFrame = eInfo.time*FRAME_RATE/1000;
	            	if(totalFrame+numFrame > TOTAL_FRAMES){
	            		numFrame = TOTAL_FRAMES - totalFrame;
	            	}
            	}

            	totalFrame+=numFrame;
            	
            	interval = (numFrame*1000)/FRAME_RATE;

            	int increment = 0;
                for(int j=0; j<numFrame && !Thread.currentThread().isInterrupted(); j++){  
                	
                	// Wait when cancel dialog appears
                	synchronized(mLock){
	                	if(mPause){
	                		try{
	                			mLock.wait();
	                		}catch(InterruptedException e){
	                			if(mPause) // user cancels encoding
	                				throw e;
	                		}
	                	}
                	}
                	if(j % 30 == 0 && j != 0){
                		progressDialog.incrementProgressBy(30);
                		increment += 30;
                	}

                    // Generate a new frame of input.
                    generateSurfaceFrame(j);

                    // Feed any pending encoder output into the muxer.
                    if(j + 1 < numFrame) drainEncoder(false);
                	
                    long elapseTime = computePresentationTimeMsec(j);                 	
                    
                    if(elapseTime <= interval){
                    	long presentationTime = timer + elapseTime*1000000;
	                    //mInputSurface.setPresentationTime(computePresentationTimeNsec(frameNum));
                    	mInputSurface.setPresentationTime(presentationTime);
                    	mProcessGL.setTimerForFilter(presentationTime);

	                    mInputSurface.swapBuffers();
                    }else{
                    	break;
                    }
                    
                    //frameNum++;
                }
                
                progressDialog.incrementProgressBy(numFrame - increment);
                
                //timer = computePresentationTimeNsec(frameNum);
                timer = timer + (interval*1000000);              
            }
            
            // send end-of-stream to encoder, and drain remaining output
            drainEncoder(true);
        } catch(Exception e){
        	if(e instanceof InterruptedException){ // user cancels encoding
        		mIsCancel = true;
        	}else{
        		isException = true;
        		Log.e(TAG,"encode exception!!!!!!!!!");
        		//e.printStackTrace();
        	}
        } finally {
            // release encoder, muxer, and input Surface
            releaseEncoder();
            
            if(Thread.currentThread().isInterrupted() || isException || mIsCancel){
            	File file = new File(mOutputPath);
            	if(file!=null && file.exists()){
            		file.delete();
            	}
            	if(isException)
            		callback.onException();
            	else callback.onInterrupted();
            	return;
            }else{
            	boolean result = true;

            	AudioMux mMux = null;
    			try{				
    				mMux = new AudioMux(OUTPUT_DIR, progressDialog);
    				
    				//Here need to give audio file path to EncodeMux
    				mMux.setAudio(mContext, mScript.getMusicId(), MusicManager.getFileName(mScript.getMusicId()));
    				mMux.setVideo(mVideoPath);
    				mMux.doExport(mOutputPath);
    			} catch(Exception e) {
    				result = false;
    			} finally {
    				if(mMux != null)
    					mMux.release();
    				result = true;
    			}
                
                File oldfile = new File(mVideoPath);
                if(oldfile.exists()) {
                    oldfile.delete();
                }
                
            	// Wait when cancel dialog appears
            	synchronized(mLock){
                	if(mPause){
                		try{
                			mLock.wait();
                		}catch(InterruptedException e){
                			if(mPause) // user cancels encoding
                				mIsCancel = true;
                		}
                	}
            	}
                
                if(Thread.currentThread().isInterrupted() || mIsCancel){
                    File outputfile = new File(mOutputPath);
                    if(outputfile.exists()) {
                    	outputfile.delete();
                    }
                    callback.onInterrupted();
                }else{
	                if(result){
		                exportToGallery(mOutputPath);
		                callback.onSaveDone("file://"+ mOutputPath, getFrameTimeByScript(mScriptSelect));
	                }else{
	                	callback.onException();
	                }
                }
            }
            clearUselessOutputFiles();
        }

        // To test the result, open the file with MediaExtractor, and get the format.  Pass
        // that into the MediaCodec decoder configuration, along with a SurfaceTexture surface,
        // and examine the output with glReadPixels.
    }
    
    // return micro-second
    private int getFrameTimeByScript(int scriptSelect){
        switch(scriptSelect){
            case ThemeAdapter.TYPE_KIDS:
                return 0;
            case ThemeAdapter.TYPE_CARNIVAL:
                return 17000000;
            case ThemeAdapter.TYPE_LIFE:
                return 28500000;
            case ThemeAdapter.TYPE_MEMORY:
                return 4000000;
            case ThemeAdapter.TYPE_ROMANCE:
                return 0;
            case ThemeAdapter.TYPE_SPORTS:
                return 21200000;
            case ThemeAdapter.TYPE_VINTAGE:
                return 11000000;
            case ThemeAdapter.TYPE_CITY:
                return 0;
            default:
                Toast.makeText(mContext, "This theme is not ready", Toast.LENGTH_SHORT).show();
                return 0;
        }
    }
    
    public void pauseEncode(){
    	synchronized(mLock){
    		mPause = true;
    	}
    }
    
    public void resumeEncode(){
    	synchronized(mLock){
	    	mPause = false;
	    	mLock.notify();
    	}
    }
    
    // porting from MicroMovieActivity
    private void setMovieOrder() {
    	//mFileOrder = MicroMovieOrder.gettimeandorder(mEphotoApp, mFilesList, mScript); // do not change order again
        mFileOrder = mActivity.mMicroMovieOrder.gettimeandorderForEncode(mFileOrder, mFilesList, mScript);
        mFileOrder = mScript.setElementInfoTime(mFileOrder);
        
        //Calc. Bitmap TriangleVertices
        for(int i=0; i<mFileOrder.size(); i++) {
            if(mFileOrder.get(i).Type == MediaInfo.MEDIA_TYPE_IMAGE) {
                mFileOrder.get(i).CalcTriangleVertices(mProcessGL);
            }
        }
    }
    
    

    /**
     * Export the movie to the Gallery
     *
     * @param filename The filename
     * @return The video MediaStore URI
     */
    private Uri exportToGallery(String filename) {
        // Save the name and description of a video in a ContentValues map.
        final ContentValues values = new ContentValues(2);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATA, filename);
        // Add a new record (identified by uri)
        final Uri uri = mContext.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values);
        mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.parse("file://"+ filename))); 
        return uri;
    }
    
    
    private void clearUselessOutputFiles(){
    	File file = new File(OUTPUT_DIR);
    	if(file.exists() && file.isDirectory()){
            // create new filename filter
            FilenameFilter fileNameFilter = new FilenameFilter() {  
				@Override
				public boolean accept(File dir, String name) {
					if(name.contains(".mp4"))
						return false;
					else return true;
				}
            };
    		File[] files = file.listFiles(fileNameFilter);
    		if(files != null){
    			for(int i=0; i<files.length; i++){
    				files[i].delete();
    			}
    		}
    	}
    }
    
    
    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    private void prepareEncoder() {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mInputSurface = new WindowSurface(mEglCore, mEncoder.createInputSurface(), true);
        mInputSurface.makeCurrent();
        mEncoder.start();

        
		DateFormat formatter = new SimpleDateFormat("yyyyMMdd");
		Calendar calendar = Calendar.getInstance();
		String dateString =  formatter.format(calendar.getTime());
		
        // Output filename.  Ideally this would use Context.getFilesDir() rather than a
        // hard-coded output directory.
        String prefix = OUTPUT_DIR + "MicroFilm" + dateString;
        //mOutputPath = prefix + ".mp4";
        mOutputPath = getOutputPath(prefix) + ".mp4";
        
        mVideoPath = mOutputPath.replace("/MicroFilm"+dateString, "/.MicroFilm"+dateString);



        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        try {
            mMuxer = new MediaMuxer(mVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mVideoTrackIndex = -1;
        mMuxerStarted = false;
    }

    private String getOutputPath(String prefix){
        File file = new File(prefix+".mp4");
        if(file.exists()){
			for(int i=1; i<Integer.MAX_VALUE; i++){
				String newPath = prefix+"_"+i;
				file = new File(newPath+".mp4");
				if(!file.exists()){
					return newPath;
				}
			}
        }
        return prefix;
    }
    
    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     */
    private void releaseEncoder() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        if (mMuxer != null) {
        	try{
	            if(mMuxerStarted){
	            	mMuxer.stop();
	            }
	            mMuxer.release();
	            mMuxer = null;
        	} catch(IllegalStateException e){
        		e.printStackTrace();
        	}
        }
    }

    /**
     * Extracts all pending data from the encoder.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    private void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true  && !Thread.currentThread().isInterrupted()) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                mVideoFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mVideoTrackIndex = mMuxer.addTrack(newFormat);

                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    mMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    public void OpenglPrepare() {
        mProcessGL.prepareOpenGL();

        mProcessGL.setView(mWidth, mHeight);
        mProcessGL.setEye();
        
        mProcessGL.setScreenScale((float)mHeight/mWidth, mWidth, mHeight);
        
        //InitData();
        
        regenerateScript(mScriptSelect);
        
        setMovieOrder();
        
        mProcessGL.setScript(mScript);
        mProcessGL.setTimerElapse(0);
    }
    
    private void regenerateScript(int scriptSelect){
        switch(scriptSelect){
            case ThemeAdapter.TYPE_KIDS:
                mScript = new Kids(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_CARNIVAL:
                mScript = new Carnival(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_LIFE:
                mScript = new Life(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_MEMORY:
                mScript = new Memory(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_ROMANCE:
                mScript = new Lover(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_SPORTS:
                mScript = new Sports(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_VINTAGE:
                mScript = new Country(true, mActivity, mProcessGL);
                break;
            case ThemeAdapter.TYPE_CITY:
                mScript = new City(true, mActivity, mProcessGL);
                break;
            default:
                Toast.makeText(mContext, "This theme is not ready", Toast.LENGTH_SHORT).show();
                return;
        }
    }

    synchronized public void onFrameAvailable(SurfaceTexture surface) {
        Log.d(TAG, "onFrameAvailable");
        //updateSurface = true;
    }

    private void generateSurfaceFrame(int frameNumber) {
        mProcessGL.doDraw(computePresentationTimeMsec(frameNumber));
    }

    /**
     * Generates the presentation time for frame N, in miliseconds.
     */
    private static long computePresentationTimeMsec(int frameIndex) {
        final long ONE_THOUSAND = 1000;
        return frameIndex * ONE_THOUSAND / FRAME_RATE;
    }
}