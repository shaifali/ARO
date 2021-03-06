/*
 * Copyright 2012 AT&T
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

package com.att.android.arodatacollector.main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.http.client.ClientProtocolException;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.util.Properties;

import com.att.android.arodatacollector.R;
import com.att.android.arodatacollector.activities.AROCollectorCompletedActivity;
import com.att.android.arodatacollector.activities.AROCollectorMainActivity;
import com.att.android.arodatacollector.utils.AROCollectorUtils;
import com.flurry.android.FlurryAgent;

/**
 * Contains methods for managing the tcpdump and video capture processes while
 * recording a trace during the application life-cycle.
 * 
 */

public class AROCollectorService extends Service {

	/** A string for logging an ARO Data Collector service. */
	public static final String TAG = "AROCollectorService";

	/** The tcpdump executable name */
	private static final String TCPDUMPFILENAME = "tcpdump";

	/**
	 * The value to check SD Card minimum space during the trace cycle. Trace
	 * will stop if any point the SD card is less than 2 MB
	 */
	private static final int AROSDCARD_MIN_SPACEKBYTES = 2048; // 2 MB
	/**
	 * The Application name file in the trace folder.
	 */
	private static final String APP_NAME_FILE = "appname";

	/**
	 * The boolean value to enable logs depending on if production build or
	 * debug build
	 */
	private static boolean mIsProduction = true;

	/**
	 * A boolean value that indicates whether or not to enable logging for this
	 * class in a debug build of the ARO Data Collector.
	 */
	public static boolean DEBUG = !mIsProduction;

	/**
	 * A boolean value that indicates whether flurry events will be logged to flurry.  False means
	 * logging is disabled
	 */
	public static boolean isFlurryLogEventsEnabled = true;

	/** The AROCollectorService object to collect peripherals trace data */
	private static AROCollectorService mDataCollectorService;

	/**
	 * The Application context of the ARO-Data Collector to gets and sets the
	 * application data
	 **/
	private static ARODataCollector mApp;

	/** The Screen Timeout value in milliseconds **/
	private int mScreenTimeout;

	/** ARO Data Collector utilities class object */
	private AROCollectorUtils mAroUtils;

	/** ARO Data Collector full trace folder path */
	private String TRACE_FOLDERNAME;

	/** To holds value ARO Data Collector video recording trace ON/OFF */
	private boolean mVideoRecording;

	/** Intent to launch ARO Data Collector Completed screen */
	private Intent tcpdumpStoppedIntent;

	/** Intent to launch ARO Data Collector Completed screen */
	private Intent traceCompletedIntent;

	/** Timed event used to log the duration of trace for use by Flurry */
	public Map<String, String> flurryTimedEvent = new HashMap<String, String>();

	/**
	 * Start Time of trace used for Flurry Analytics
	 */
	public Calendar startCalTime = null;
	
	/**
	 * Max Timeout time for Data Collector STOP to exit tcpdump capture from the
	 * shell
	 */
	private static final int ARO_STOP_WATCH_TIME = 35000;
	
	/** Watch dog timer to set STOP timeout for Data Collector */
	private Timer aroDCStopWatchTimer = new Timer();
	
	/**
	 * Gets the valid instance of AROCollectorService.
	 * 
	 * @return An AROCollectorService object
	 */
	public static AROCollectorService getServiceObj() {
		return mDataCollectorService;
	}

	/**
	 * s processing when an AROCollectorService object is created.
	 * Overrides the android.app.Service#onCreate method.
	 * 
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		// Initializes the data controls and starts the Data Collector trace
		// (i.e tcpdump,VideoCapture)
		mDataCollectorService = this;
		mApp = (ARODataCollector) getApplication();
		mAroUtils = new AROCollectorUtils();
			
		//flurry start session if set to is true.  set to false to disable FlurryAgent logging call.
		FlurryAgent.setLogEvents(isFlurryLogEventsEnabled); 
		
		//check for flurry api key override from default-incorrect key will cause session to not log to correct Flurry app.
		setFlurryApiKey();
		FlurryAgent.setContinueSessionMillis(5000);  // Set session timeout to 5 seconds (minimum specified by flurry)
 
		FlurryAgent.onStartSession(this, mApp.app_flurry_api_key); //don't use mAroCollectorService as context
		if (DEBUG) {
			Log.d(TAG, "flurry-called onStartSession");
		}
		
		//set device id as flurry's userid within a session
		final TelephonyManager mAROtelManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		FlurryAgent.setUserId( mAROtelManager.getDeviceId());
		if (DEBUG) {
			Log.d(TAG, "flurry-TelephonyManager deviceId: " + mAROtelManager.getDeviceId());
		}

		try {
			// Record the screen timeout
			getScreenTimeOut();
			// Disable screen timeout
			setScreenTimeOut(-1);
		} catch (SettingNotFoundException e) {
			Log.e(TAG, "exception in getting device settings. Failed to get screen timeout", e);
		}
		TRACE_FOLDERNAME = mApp.getDumpTraceFolderName();
		mVideoRecording = mApp.getCollectVideoOption();
		startDataCollectorVideoCapture();
		statDataCollectortcpdumpCapture();
	}

	/**
	 * s processing when an AROCollectorService object is destroyed.
	 * Overrides the android.app.Service#onDestroy method.
	 * 
	 * @see android.app.Service#onDestroy()
	 */
	@Override
	public void onDestroy() {
		//flurry end session
		FlurryAgent.onEndSession(this);
		if (DEBUG) {
			Log.d(TAG, "flurry-called onEndSession");
		}
		
		super.onDestroy();
		// Sets the screen timeout to previous value
		setScreenTimeOut(mScreenTimeout);
		mDataCollectorService = null;
		mApp.cancleAROAlertNotification();
	}

	/**
	 * Starts the dedicated thread for tcpdump network traffic capture in the
	 * native shell
	 */
	private void statDataCollectortcpdumpCapture() {
		// Starting the tcpdump on separate thread
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					startTcpDump();
					writAppVersions();
				} catch (IOException e) {
					Log.e(TAG, "IOException in startTcpDump ", e);
				} catch (InterruptedException e) {
					Log.e(TAG, "InterruptedException in startTcpDump ", e);
				}
			}
		}).start();
	}

	/**
	 * Initializes the video capture flag and starts the video capture on
	 * separate thread
	 */
	private void startDataCollectorVideoCapture() {
		// Wait for the tcpdump to start
		if (mVideoRecording) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					mApp.setAROVideoCaptureRunningFlag(true);
					try {
						mApp.initVideoTraceTime();
						startScreenVideoCapture();
					} catch (FileNotFoundException e) {
						Log.e(TAG, "exception in initVideoTraceTime. Failed to start Video", e);
					}
				}
			}).start();
		}
	}

	/**
	 * This method creates a SU enabled shell Sets the execute permission for
	 * tcpdump and key.db Starts the tcpdump on Completion or abnormal
	 * termination of tcpdump Shell is destroyed
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */

	private void startTcpDump() throws IOException, InterruptedException {
		Process sh = null;
		DataOutputStream os = null;
		try {
			sh = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(sh.getOutputStream());
			String Command = "chmod 777 " + ARODataCollector.INTERNAL_DATA_PATH + TCPDUMPFILENAME
					+ "\n";
			os.writeBytes(Command);
			Command = "chmod 777 " + ARODataCollector.INTERNAL_DATA_PATH + "key.db" + "\n";
			os.writeBytes(Command);
			
			//flurry timed event duration
			startCalTime = Calendar.getInstance();
			mApp.writeToFlurryAndLogEvent(flurryTimedEvent, "Flurry trace start", startCalTime.getTime().toString(), "Trace Duration", true);
			
			Command = "." + ARODataCollector.INTERNAL_DATA_PATH + TCPDUMPFILENAME + " -w "
					+ TRACE_FOLDERNAME + "\n";
			os.writeBytes(Command);
			Command = "exit\n";
			os.writeBytes(Command);
			os.flush();
			final int shExitValue = sh.waitFor();
			final AROCollectorTaskManagerProcessInfo mAROTaskManagerProcessInfo = new AROCollectorTaskManagerProcessInfo();
			//We will continue and block the thread untill we see valid instance of tcpdump running in shell
			//waitFor() does not seems to be working on ICS firmware 
			while (mAROTaskManagerProcessInfo.pstcpdump()) {
				continue;
			}
			if (DEBUG) {
				Log.d(TAG, "tcpdump process exit value: " + shExitValue);
				Log.i(TAG, "Coming out of startTcpDump");
			}
			// Stopping the Video capture right after tcpdump coming out of
			// shell
			new Thread(new Runnable() {
				@Override
				public void run() {
					if (mVideoRecording && mApp.getAROVideoCaptureRunningFlag()) {
						stopScreenVideoCapture();
					}
				}
			}).start();
			
			final Calendar endCalTime = Calendar.getInstance();
			
			FlurryAgent.endTimedEvent("Trace Duration");
			mApp.writeToFlurry(flurryTimedEvent, "Flurry trace end", endCalTime.getTime().toString(), "flurryTimedEvent", AROCollectorUtils.NOT_APPLICABLE, AROCollectorUtils.EMPTY_STRING);
			mApp.writeToFlurry(flurryTimedEvent, "calculated Flurry trace duration", getUpTime(endCalTime), "flurryTimedEvent", AROCollectorUtils.NOT_APPLICABLE, AROCollectorUtils.EMPTY_STRING);
			logFlurryEvents();
			DataCollectorTraceStop();
		} finally {
			try {
				mApp.setTcpDumpStartFlag(false);
				os.close();
			} catch (IOException e) {
				Log.e(TAG, "exception in startTcpDump DataOutputStream close", e);
			}
			sh.destroy();
		}
	}

	/**
	 * Sample file content: FLURRY_API_KEY=YKN7M4TDXRKXH97PX565
	 * Each Flurry API Key corresponds to an Application on Flurry site.  It is absolutely 
     * necessary that the Flurry API Key-value from user's device is correct in order to log to the Flurry application.
     * 
     * No validation on the API key allows creation of a new Flurry application by client at any time
     * The API key is communicated to the user group who would put the API key name-value pair into 
     * properties file specified by variable flurryFileName below.
     * 
     * If no key-value is found, the default API key is used below.  Default is intended for users of
     * ATT Developer Program.
	 */
	private void setFlurryApiKey() {
		if (DEBUG) {
			Log.d(TAG, "entered setFlurryApiKey");
		}
			final String flurryFileName = ARODataCollector.ARO_TRACE_ROOTDIR + ARODataCollector.FLURRY_API_KEY_REL_PATH;

			InputStream flurryFileReaderStream = null;
			try {
				final ClassLoader loader = ClassLoader.getSystemClassLoader ();

				flurryFileReaderStream = loader.getResourceAsStream(flurryFileName);

				Properties prop = new Properties();
				try {
					if (flurryFileReaderStream != null) {
						prop.load(flurryFileReaderStream);
						mApp.app_flurry_api_key = prop.containsKey(ARODataCollector.FLURRY_API_KEY_NAME) && 
								!prop.getProperty(ARODataCollector.FLURRY_API_KEY_NAME).equals(AROCollectorUtils.EMPTY_STRING) ? 
								prop.getProperty(ARODataCollector.FLURRY_API_KEY_NAME).trim() : 
									mApp.app_flurry_api_key;
						if (DEBUG) {
							Log.d(TAG, "flurry Property String: " + prop.toString());
							Log.d(TAG, "flurry app_flurry_api_key: " + mApp.app_flurry_api_key);
						}
					} else {
						if (DEBUG) {
							Log.d(TAG, "flurryFileReader stream is null.  Using default: " + mApp.app_flurry_api_key);
						}
					}
				} 
				catch (IOException e) {
					Log.d(TAG, e.getClass().getName() + " thrown trying to load file ");
				}
			} finally {
				try {
					if (flurryFileReaderStream != null) {
						flurryFileReaderStream.close();
					}
				} catch (IOException e) {
					//log and exit method-nothing else to do.
					if (DEBUG) {
						Log.d(TAG, "setFlurryApiKey method reached catch in finally method, trying to close flurryFileReader"  );
					}
				}
				Log.d(TAG, "exiting setFlurryApiKey");
			}
	}
	private void logFlurryEvents() {
		
		if (AROCollectorTraceService.makeModelEvent != null) {
			FlurryAgent.logEvent(AROCollectorTraceService.makeModelEvent.getEventName(),
					AROCollectorTraceService.makeModelEvent.getMapToWrite());
		}
		if (AROCollectorTraceService.backgroundAppsFlurryEvent != null) {
			FlurryAgent.logEvent(AROCollectorTraceService.backgroundAppsFlurryEvent.getEventName(),
					AROCollectorTraceService.backgroundAppsFlurryEvent.getMapToWrite());
		}
		//trace video y/n
		if ((mApp.getCollectVideoOption() && !mApp.isVideoFileExisting())
				|| mApp.getVideoCaptureFailed()) {		
			mApp.writeToFlurryAndLogEvent(mApp.flurryVideoTaken, getResources().getText(R.string.flurry_param_traceVideoTaken).toString(), 
					getResources().getText(R.string.aro_failedvideo).toString(), 
					getResources().getText(R.string.flurry_param_traceVideoTaken).toString(), false);						
		} else if (mApp.getCollectVideoOption()) {			
			mApp.writeToFlurryAndLogEvent(mApp.flurryVideoTaken, getResources().getText(R.string.flurry_param_traceVideoTaken).toString(),
					getResources().getText(R.string.aro_yestext).toString(), 
					getResources().getText(R.string.flurry_param_traceVideoTaken).toString(), false);
		} else {			
			mApp.writeToFlurryAndLogEvent(mApp.flurryVideoTaken, getResources().getText(R.string.flurry_param_traceVideoTaken).toString(),
					getResources().getText(R.string.aro_notext).toString(), 
					getResources().getText(R.string.flurry_param_traceVideoTaken).toString(), false);
		}
		
		if (DEBUG) {
			Log.d(TAG, "exiting logFlurryEvents");
		}
	}

	private String getUpTime(Calendar endCalTime) {
		if (DEBUG) {
			Log.d("calculate duration-flurry start time: ", AROCollectorUtils.EMPTY_STRING + startCalTime.getTime());
			Log.d("calculate duration-flurry end time: ", AROCollectorUtils.EMPTY_STRING + endCalTime.getTime());
		}
		final long appUpTime = (endCalTime.getTimeInMillis() - startCalTime.getTimeInMillis()) / 1000;
		long appTimeR, appUpHours, appUpMinutes, appUpSeconds;
		appTimeR = appUpTime % 3600;
		appUpHours = appUpTime / 3600;
		appUpMinutes = (appTimeR / 60);
		appUpSeconds = appTimeR % 60;

		final String upTime = (appUpHours < 10 ? "0" : AROCollectorUtils.EMPTY_STRING) + appUpHours + ":"
				+ (appUpMinutes < 10 ? "0" : AROCollectorUtils.EMPTY_STRING) + appUpMinutes + ":"
				+ (appUpSeconds < 10 ? "0" : AROCollectorUtils.EMPTY_STRING) + appUpSeconds;

		if (DEBUG) {
			Log.d("flurry Trace Duration: ", upTime);
		}
		
		return upTime;
	}

	/**
	 * Stops the ARO Data Collector trace by stopping the tcpdump process.
	 * 
	 * @throws java.io.IOException
	 * @throws java.net.UnknownHostException
	 */
	public void requestDataCollectorStop() {
		dataCollectorStopWatchTimer();
		try {
			if (DEBUG) {
				Log.i(TAG, "stopTcpDump In....");
			}
			final Socket tcpdumpsocket = new Socket(InetAddress.getByName("localhost"), 50999);
			final OutputStream out = tcpdumpsocket.getOutputStream();
			out.write("STOP".getBytes("ASCII"));
			out.flush();
			out.close();
			tcpdumpsocket.close();
			if (DEBUG) {
				Log.i(TAG, "stopTcpDump Out....");
			}
		} catch (UnknownHostException e) {
			Log.e(TAG, "exception in stopTcpDump", e);
		} catch (IOException e) {
			Log.e(TAG, "exception in stopTcpDump", e);
		}
	}

	/**
	 * s processing when an AROCollectorService object is binded to
	 * content. Overrides the android.app.Service#onBind method.
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	/**
	 *  the tcpdump stops reasons while coming out of tcpdump shell and
	 * navigate to respective screen or shows error dialog
	 */
	private void DataCollectorTraceStop() {
		aroDCStopWatchTimer.cancel();
		
		if (DEBUG) {
			Log.i(TAG, "DataCollectorTraceStop");
			Log.i(TAG, "mApp.getDataCollectorBearerChange()=" + mApp.getDataCollectorBearerChange());
			Log.i(TAG, "mApp.getDataCollectorInProgressFlag()=" + mApp.getDataCollectorInProgressFlag());
			Log.i(TAG, "mApp.getARODataCollectorStopFlag()=" + mApp.getARODataCollectorStopFlag());
		}
		if (!mApp.getDataCollectorInProgressFlag()) {
			mApp.cancleAROAlertNotification();
			if (!mApp.getARODataCollectorStopFlag()) {
				// Stopping the peripherals collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorTraceService.class));
				// Stopping the tcpdump/screen capture collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorService.class));

				try {
					// Motorola Atrix2 -waiting to get SD card refresh state
					if (Build.MODEL.equalsIgnoreCase("MB865") && !mApp.getAirplaneModeEnabledMidAROTrace()) {
						// thread sleep for 16 sec
						Thread.sleep(16000);
					}
				} catch (InterruptedException e) {
					Log.e(TAG, "InterruptedException while sleep SD card mount" + e);
				}
				mApp.setTcpDumpStartFlag(false);
				tcpdumpStoppedIntent = new Intent(getBaseContext(), AROCollectorMainActivity.class);
				if (DEBUG) {
					Log.i(TAG, "SD card space left =" + mAroUtils.checkSDCardMemoryAvailable());
				}
				if (mAroUtils.checkSDCardMemoryAvailable() == 0.0) {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.SDCARDMOUNTED_MIDTRACE);
				} else if (mAroUtils.checkSDCardMemoryAvailable() < AROSDCARD_MIN_SPACEKBYTES) {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.SDCARDERROR);
				} else if (mApp.getAirplaneModeEnabledMidAROTrace()){
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.AIRPLANEMODEENABLED_MIDTRACE);
				} else {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.TCPDUMPSTOPPED);
					mApp.writeToFlurryAndLogEvent(mApp.flurryError, "tcpdump stopped", Calendar.getInstance().getTime().toString(), "Error", false);
				}
				tcpdumpStoppedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getApplication().startActivity(tcpdumpStoppedIntent);
			} else if (mApp.getARODataCollectorStopFlag()) {
				if (DEBUG) {
					Log.i(TAG, "Trace Summary Screen to Start");
				}
				traceCompletedIntent = new Intent(getBaseContext(),
				AROCollectorCompletedActivity.class);
				traceCompletedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getApplication().startActivity(traceCompletedIntent);
				mDataCollectorService = null;
				// Stopping the peripherals collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorTraceService.class));
				// Stopping the tcpdump/screen capture collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorService.class));
			}
		}
	}

	/**
	 * Sets the Screen Timeout value
	 * 
	 * @param timeout
	 *            value to be set -1 infinite
	 */
	private void setScreenTimeOut(int val) {
		Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, val);
	}

	/**
	 * Gets the screen timeout value from system.settings
	 * 
	 * @throws SettingNotFoundException
	 */
	private void getScreenTimeOut() throws SettingNotFoundException {
		mScreenTimeout = Settings.System.getInt(getContentResolver(),
				Settings.System.SCREEN_OFF_TIMEOUT);

	}

	/**
	 * Starts the video capture of the device desktop by reading frame buffer
	 * using ffmpeg command
	 */
	private void startScreenVideoCapture() {
		Process sh = null;
		DataOutputStream os = null;
		try {
			if (DEBUG) {
				Log.e(TAG, "Starting Video Capture");
			}
			sh = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(sh.getOutputStream());
			String Command = "cd " + ARODataCollector.INTERNAL_DATA_PATH + " \n";
			os.writeBytes(Command);
			Command = "chmod 777 ffmpeg \n";
			os.writeBytes(Command);
			Command = "./ffmpeg -f fbdev -vsync 2 -r 3 -i /dev/graphics/fb0 /sdcard/ARO/"
					+ TRACE_FOLDERNAME + "/video.mp4 2> /data/ffmpegout.txt \n";
			os.writeBytes(Command);
			Command = "exit\n";
			os.writeBytes(Command);
			os.flush();
			sh.waitFor();
		} catch (IOException e) {
			Log.e(TAG, "exception in startScreenVideoCapture", e);
		} catch (InterruptedException e) {
			Log.e(TAG, "exception in startScreenVideoCapture", e);
		} finally {
			try {
				if (DEBUG) {
					Log.e(TAG, "Stopped Video Capture in startScreenVideoCapture");
				}
				os.close();
				// Reading start time of Video from ffmpegout file
				mApp.readffmpegStartTimefromFile();
			} catch (IOException e) {
				Log.e(TAG, "IOException in reading video start time", e);
			} catch (NumberFormatException e) {
				Log.e(TAG, "NumberFormatException in reading video start time", e);
			}
			try {
				// Recording start time of video
				mApp.writeVideoTraceTime(Double.toString(mApp.getAROVideoCaptureStartTime()));
				mApp.closeVideoTraceTimeFile();
			} catch (IOException e) {
				Log.e(TAG, "IOException in writing video start time", e);
			}
			if (mApp.getTcpDumpStartFlag() && !mApp.getARODataCollectorStopFlag()) {
				mApp.setVideoCaptureFailed(true);
			}
			try {
				mApp.setAROVideoCaptureRunningFlag(false);
				sh.destroy();
			} catch (Exception e) {
				Log.e(TAG, "Failed to destroy shell during Video Capture termination");
			}
		}
	}

	/**
	 * Stops the Screen video capture
	 */
	private void stopScreenVideoCapture() {
		Process sh = null;
		DataOutputStream os = null;
		int pid = 0, exitValue = -1;
		try {
			pid = mAroUtils.getProcessID("ffmpeg");
		} catch (IOException e1) {
			Log.e(TAG, "IOException in stopScreenVideoCapture", e1);
		} catch (InterruptedException e1) {
			Log.e(TAG, "exception in stopScreenVideoCapture", e1);
		}
		if (DEBUG) {
			Log.i(TAG, "stopScreenVideoCapture=" + pid);
		}
		if (pid != 0) {
			try {
				sh = Runtime.getRuntime().exec("su");
				os = new DataOutputStream(sh.getOutputStream());
				String command = "kill -15 " + pid + "\n";
				os.writeBytes(command);
				
				command = "exit\n";
				os.writeBytes(command);
				os.flush();
				
				//clear the streams so that it doesnt block the process
				//sh.inputStream is actually the output from the process
				StreamClearer stdoutClearer = new StreamClearer(sh.getInputStream(), "stdout", false);
				new Thread(stdoutClearer).start();
				StreamClearer stderrClearer = new StreamClearer(sh.getErrorStream(), "stderr", true);
				new Thread(stderrClearer).start();
				
				exitValue = sh.waitFor();
				if (exitValue == 0){
					mVideoRecording = false;
				}
				
				if (DEBUG){
					Log.i(TAG, "successfully returned from kill -15; exitValue= " + exitValue);
				}
			} catch (IOException e) {
				Log.e(TAG, "exception in stopScreenVideoCapture", e);
			} catch (InterruptedException e) {
				Log.e(TAG, "exception in stopScreenVideoCapture", e);
			} finally {
				try {
					kill9Ffmpeg();
					
					if (os != null){
						os.close();
					}
					if (sh != null){
						sh.destroy();
					}
				} catch (IOException e) {
					Log.e(TAG, "exception in stopScreenVideoCapture DataOutputStream close", e);
				}
				if (DEBUG) {
					Log.i(TAG, "Stopped Video Capture in stopScreenVideoCapture()");
				}
			}
		}
		
		if (DEBUG){
			Log.i(TAG, "exit stopScreenVideoCapture");
		}
	}
	
	/**
	 * issue the kill -9 command if ffmpeg couldn't be stopped with kill -15
	 */
	private void kill9Ffmpeg(){
		
		Process sh = null;
		DataOutputStream os = null;
		
		int pid = 0, exitValue = -1;
		try {
			//have a 1 sec delay since it takes some time for the kill -15 to end ffmpeg
			Thread.sleep(1000);
			pid = mAroUtils.getProcessID("ffmpeg");
		
			if (pid != 0){
				//ffmpeg still running
				if (DEBUG){
					Log.i(TAG, "ffmpeg still running after kill -15. Will issue kill -9 " + pid);
				}
	
				sh = Runtime.getRuntime().exec("su");
				os = new DataOutputStream(sh.getOutputStream());
				String Command = "kill -9 " + pid + "\n";
				os.writeBytes(Command);
					
				Command = "exit\n";
				os.writeBytes(Command);
				os.flush();
					
				//clear the streams so that it doesnt block the process
				//sh.inputStream is actually the output from the process
				StreamClearer stdoutClearer = new StreamClearer(sh.getInputStream(), "stdout", false);
				new Thread(stdoutClearer).start();
				StreamClearer stderrClearer = new StreamClearer(sh.getErrorStream(), "stderr", true);
				new Thread(stderrClearer).start();
					
				exitValue = sh.waitFor();
				if (exitValue == 0){
					mVideoRecording = false;
				}
				else {
					Log.e(TAG, "could not kill ffmpeg in kill9Ffmpeg, exitValue=" + exitValue);
				}

			} 
			else {
				mVideoRecording = false;
				if (DEBUG){
					Log.i(TAG, "ffmpeg had been ended successfully by kill -15");
				}
			}
		} catch (IOException e1) {
			Log.e(TAG, "IOException in kill9Ffmpeg", e1);
		} catch (InterruptedException e1) {
			Log.e(TAG, "exception in kill9Ffmpeg", e1);
		} finally {
			try {
				if (os != null){
					os.close();
				}
				
				if (sh != null){
					sh.destroy();
				}
			} catch (IOException e) {
				Log.e(TAG, "exception in kill9Ffmpeg DataOutputStream close", e);
			}
		}
	}

	/**
	 * Reads the appname file generated from tcpdump and appends the application
	 * version next to each application .
	 * 
	 * @throws IOException
	 */
	private void writAppVersions() throws IOException {
		BufferedReader appNamesFileReader = null;
		BufferedWriter appNmesFileWriter = null;
		try {
			final String strTraceFolderName = mApp.getTcpDumpTraceFolderName();
			Log.i(TAG, "Trace folder name is: " + strTraceFolderName);
			final File appNameFile = new File(mApp.getTcpDumpTraceFolderName() + APP_NAME_FILE);
			appNamesFileReader = new BufferedReader(new InputStreamReader(new FileInputStream(
					appNameFile)));
			String processName = null;
			final List<String> appNamesWithVersions = new ArrayList<String>();
			while ((processName = appNamesFileReader.readLine()) != null) {

				String versionNum = null;
				try {
					versionNum = getPackageManager().getPackageInfo(processName, 0).versionName;
					appNamesWithVersions.add(processName + " " + versionNum);
				} catch (NameNotFoundException e) {
					appNamesWithVersions.add(processName);
					Log.e(TAG, "Package name can not be found; unable to get version number.");
				} catch (Exception e) {
					Log.e(TAG, "Unable to get version number ");
				}
			}
			appNmesFileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
					appNameFile)));
			final String eol = System.getProperty("line.separator");
			for (String appNemeVersion : appNamesWithVersions) {
				appNmesFileWriter.append(appNemeVersion + eol);

			}
		} catch (IOException e) {
			Log.e(TAG, "Error occured while writing the version number for the applications");
		} finally {
			if (appNamesFileReader != null) {
				appNamesFileReader.close();
			}
			if (appNmesFileWriter != null) {
				appNmesFileWriter.close();
			}
		}
	}
	
	/**
	 * Watch Dog to check abnormal termination of Data Collector
	 */
	private void dataCollectorStopWatchTimer() {
		if (DEBUG) {
			Log.i(TAG, "Inside dataCollectorStopWatchTimer....");
		}
		aroDCStopWatchTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (DEBUG) {
					Log.i(TAG,
							"Inside dataCollectorStopWatchTimer....mApp.getTcpDumpStartFlag"
									+ mApp.getTcpDumpStartFlag()
									+ "mApp.getARODataCollectorStopFlag(true);"
									+ mApp.getARODataCollectorStopFlag());
				}
				if (mApp.getTcpDumpStartFlag()) {
					aroDCStopWatchTimer.cancel();
					if (AROCollectorTraceService.getServiceObj() != null) {
						if (DEBUG) {
							Log.i(TAG, "Inside Ping Connection....hideProgressDialog");
						}
						if (DEBUG) {
							Log.i(TAG, "Setting Data Collector stop flag");
						}
						mApp.setARODataCollectorStopFlag(true);
						try {
							// Going to ping google to break out of tcpdump
							// while loop to come out of native shell and stop
							// ARO-Data Collector
							// for htc hardware
							mAroUtils.OpenHttpConnection();
						} catch (ClientProtocolException e) {
							Log.e(TAG, "exception in OpenHttpConnection ", e);
						} catch (IOException e) {
							// TODO : To display error message for failed stop
							// of data collector
							Log.e(TAG, "exception in OpenHttpConnection ", e);
						}
					}
				}
			}
		}, ARO_STOP_WATCH_TIME);
	}
	
	
	class StreamClearer implements Runnable {
		InputStream streamToClear = null;
		boolean logStream = false;
		String name = null;
		
		public StreamClearer(InputStream is, String name, boolean logStream){
			streamToClear = is;
			this.logStream = logStream;
			this.name = name;
		}
		@Override
		public void run() {
			
			final BufferedReader reader = new BufferedReader(new InputStreamReader(streamToClear));
			String buf = null;
			if (DEBUG && logStream){
				Log.i(TAG, "StreamClearer logging content from shell's " + name);
			}
			
			try {
				while ((buf = reader.readLine()) != null) {
					buf = buf.trim();
					if (logStream && buf.length() > 0){
						Log.e(TAG, name + ">" + buf + "\n");
					}
				}
			} catch (IOException e) {
				Log.e(TAG, "StreamClearer IOException in StreamClearer", e);
			}
			
			if (DEBUG && logStream){
				Log.i(TAG, "StreamClearer done logging content from shell's " + name);
			}
		}
	}
}

