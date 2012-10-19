/* Copyright (c) 2012, Lambertus Gorter <l.gorter@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The names of its contributors may not be used to endorse or promote
 *     products derived from this software without specific prior written
 *     permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.github.gabriel_lg.romotive.libromo;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
/**
 * The MotorControl class controls the motors of the Romo. Instanciating this class will create
 * an "active" object, meaning it will start a thread of execution of its own. Only a single 
 * instance of this class may be started at a time.
 * MotorControl provides the following functionality
 * <ul>
 *  <li>Set the speed of the motors (obviously)</li>
 *  <li>Sense if the Romo is (dis)connected</li>
 *  <li>A callback mechanism for app when Romo gets (dis)connected</li>
 *  <li>Automatically request focus on STREAM_MUSIC (headphone) when controlling the Romo (to silence e.g. musicplayer on headphones)</li>
 *  <li>Automatically abandon focus on STREAM_MUSIC when no longer controlling the Romo</li>
 *  <li>Automatically set the correct STREAM_MUSIC (headphone) volume level when controlling the Romo</li>
 *  <li>Automatically restore the previous STREAM_MUSIC volume when no longer controlling the Romo</li>
 *  <li>Setting an auto repeat interval for your commands</li>
 *  <li>Setting an inter-command gap</li>
 *  <li>Pause Motorcontrol and halting the Romo (for when your app looses focus)</li>
 *  <li>Resume Motorcontrol and restoring the previous motor speeds (for when your app regains focus)</li>
 * </ul>
 * <b>NOTE:</b>
 * Please do not change the audio volume of STREAM_MUSIC and do not play sound effects
 * over STREAM_MUSIC while the Romo is connected. This will interfere with MotorControl (Android does
 * not provide a locking mechanism for audio, so MotorControl cannot claim the headphones).
 * 
 * @author Lambertus Gorter
 *
 */
public class MotorControl {
	private static MotorControl instance = null;
	public static final int SPEED_MAX_FORWARD = 127;
	public static final int SPEED_MAX_BACKWARD = -127;
	public static final int SPEED_STOP = 0;
	
	private static final String TAG = MotorControl.class.getName();
	private static final short HI = Short.MAX_VALUE;
	private static final short LO = Short.MIN_VALUE;
	private static final short[] ONE = { HI, HI, HI, HI, HI, HI, HI, HI, LO, HI, LO, HI, LO, HI, LO, HI };
	private static final short[] ZERO = { HI, LO, HI, LO, HI, LO, HI, LO, LO, LO, LO, LO, LO, LO, LO, LO };
	private static final int CMD_SHORT_SIZE = 192;
	private static final int CMD_DURATION_MS = 12;

	private final Activity activity;
	private final AudioManager manager;
	private final AudioTrack audioTrack;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition modified = lock.newCondition();

	private long refreshIntervalNs = 0;
	private long interCmdGapMs = 0;
	private long nextRefresh = System.nanoTime();
	private boolean connected = false;
	private boolean focus = false;
	private boolean paused = false;
	private boolean controlling = false;

	private int speedLeft = SPEED_STOP;
	private int speedRight = SPEED_STOP;
	private int speedAux = SPEED_STOP;
	private boolean updateLeft = false;
	private boolean updateRight = false;
	private boolean updateAux = false;
	private RomoConnectionListener connectionListener = null;
	
	//The thread doing the actual work...
	private Thread worker = new Thread() {
		private int lastVolume = 0;
		
		public void run() {
			lock.lock();
			while (worker != null) {
				try {
					if(controlling) {
						//state: controlling 
						if(!paused && connected && focus) {
							if (updateLeft || updateRight || updateAux) {
								//command left motor
								int speedLeftBuf = speedLeft;
								if (updateLeft) {
									updateLeft = false;
									lock.unlock();
									playCommand(1, speedLeftBuf);
									lock.lock();
								}
								//command right motor
								int speedRightBuf = speedRight;
								if (updateRight) {
									updateRight = false;
									lock.unlock();
									playCommand(2, speedRightBuf);
									lock.lock();
								}
								//command aux motor
								int speedAuxBuf = speedAux;
								if (updateAux) {
									updateAux = false;
									lock.unlock();
									playCommand(3, speedAuxBuf);
									lock.lock();
								}
							} else if (refreshIntervalNs == 0) {
								modified.await();
							} else {
								if (modified.awaitNanos(nextRefresh - System.nanoTime()) <= 0) {
									nextRefresh += refreshIntervalNs;
									if( nextRefresh < System.nanoTime()) nextRefresh = System.nanoTime() + refreshIntervalNs;
									updateLeft = true;
									updateRight = true;
									updateAux = true;
								}
							}
						//state changed...
						}else{
							if(connected) {
								lock.unlock();
								playCommand(1, SPEED_STOP);
								playCommand(2, SPEED_STOP);
								playCommand(3, SPEED_STOP);
								//give the audio subsystem 100ms to allow for playing command
								sleep(100);
								lock.lock();
							}
							manager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVolume, 0);
							manager.abandonAudioFocus(audioFocusListener);
							focus = false;
							controlling = false;
						}
					//state not controlling	
					}else{
						if(!paused && connected && focus) {
							lastVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
							manager.setStreamVolume(AudioManager.STREAM_MUSIC, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
							refresh();
							controlling = true;
						}else if(!paused && connected){
							int result = manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
							if(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
								focus = true;
							}else{
								//request focus again after 1 second
								modified.awaitNanos(1000*1000000);
							}
						}else{
							modified.await();
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
					if (!lock.isHeldByCurrentThread())
						lock.lock();
				}
			} //while(worker != null)
			if(controlling && connected) {
				playCommand(1, SPEED_STOP);
				playCommand(2, SPEED_STOP);
				playCommand(3, SPEED_STOP);
				//give the audio subsystem 100ms to allow for playing command
				try {
					sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				manager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVolume, 0);
				manager.abandonAudioFocus(audioFocusListener);
			}
		}
	};

	//the callback that will be invoked when MotorControl looses audiofocus
	private AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
		@Override
		public void onAudioFocusChange(int focusChange) {
			lock.lock();
			focus = (focusChange == AudioManager.AUDIOFOCUS_GAIN);
			modified.signalAll();
			lock.unlock();
			Log.d(TAG, "AudioFocus changed to: "+focus);
		}
	};
	
	//The broacast receiver that will be invoked whenever the headphone jack is (un)plugged
	private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equalsIgnoreCase(Intent.ACTION_HEADSET_PLUG)) {
				if (intent.getExtras().getInt("state") == 0) {
					audioTrack.pause();
					audioTrack.flush();
					lock.lock();
					connected = false;
					modified.signalAll();
					lock.unlock();
					Log.d(TAG, "Romo disconnected");
				} else {
					lock.lock();
					connected = true;
					refresh();
					modified.signalAll();
					lock.unlock();
					Log.d(TAG, "Romo connected");
				}
				if(connectionListener != null){
				activity.runOnUiThread(
					new Runnable(){
						@Override
						public void run() {
							connectionListener.onConnectionChanged(connected);
						}
					});
				}
			}
		}
	};

	/**
	 * Set the motor at the given address at the given speed.
	 * Compensation for the reversed right motor is done here.
	 * TODO find out what the actual scaling is for the motor speeds.
	 * Possibilities are:
	 * - speeds range from 1 to 255, 128 is stop (asumed now).
	 * - speeds range from 0 to 255, both 127 and 128 are stop.
	 * - speeds range from 0 to 255, 128 is stop and reverse is in units 1/128 and forward in units of 1/127 
	 * @param address
	 * @param speed
	 */
	private void playCommand(int address, int speed) {
		int cmdSpeed; 
		if(address != 2) cmdSpeed = 128 + speed;
		else cmdSpeed = 128 - speed; //right motor is reversed
		boolean parity = ((Integer.bitCount(address & 0x03) + Integer.bitCount(cmdSpeed & 0xff)) & 1) == 1;
		short[] sample = new short[CMD_SHORT_SIZE];
		System.arraycopy(ZERO, 0, sample, 0 * 16, 16);
		System.arraycopy((address & 0x02) != 0 ? ONE : ZERO, 0, sample, 1 * 16,	16);
		System.arraycopy((address & 0x01) != 0 ? ONE : ZERO, 0, sample, 2 * 16,	16);
		System.arraycopy((cmdSpeed & 0x80) != 0 ? ONE : ZERO, 0, sample, 3 * 16, 16);
		System.arraycopy((cmdSpeed & 0x40) != 0 ? ONE : ZERO, 0, sample, 4 * 16, 16);
		System.arraycopy((cmdSpeed & 0x20) != 0 ? ONE : ZERO, 0, sample, 5 * 16, 16);
		System.arraycopy((cmdSpeed & 0x10) != 0 ? ONE : ZERO, 0, sample, 6 * 16, 16);
		System.arraycopy((cmdSpeed & 0x08) != 0 ? ONE : ZERO, 0, sample, 7 * 16, 16);
		System.arraycopy((cmdSpeed & 0x04) != 0 ? ONE : ZERO, 0, sample, 8 * 16, 16);
		System.arraycopy((cmdSpeed & 0x02) != 0 ? ONE : ZERO, 0, sample, 9 * 16, 16);
		System.arraycopy((cmdSpeed & 0x01) != 0 ? ONE : ZERO, 0, sample, 10 * 16, 16);
		System.arraycopy(parity ? ONE : ZERO, 0, sample, 11 * 16, 16);
		audioTrack.play();
		audioTrack.write(sample, 0, sample.length);
		audioTrack.stop();
		try {
			Thread.sleep(CMD_DURATION_MS + interCmdGapMs);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Create a new MotorControl object. The movement object is an Active object, of which only
	 * a single instance can exist.
	 * @param activity
	 * @throws LibRomoRuntimeException if there is already an instance of MotorControl active.
	 */
	public MotorControl(Activity activity) {
		synchronized(MotorControl.class) {
			if(instance == null) instance = this;
			else throw new LibRomoRuntimeException("Destroy the previous instance of this class, before instanciating the next.");
		}
		this.activity = activity;
		manager = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
		manager.setStreamVolume(AudioManager.STREAM_MUSIC, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
		int minBuffer = AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, 
				AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
				Math.max(CMD_SHORT_SIZE * 2, minBuffer),
				AudioTrack.MODE_STREAM);
		worker.start();
		activity.registerReceiver(broadcastReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
	}
	
	/**
	 * Destroy the MotorControl object so its resources are freed and the object can be garbage-collected.
	 */
	public void destroy() {
		lock.lock();
		activity.unregisterReceiver(broadcastReceiver);
		connectionListener = null;
		instance = null;
		worker = null;
		modified.signalAll();
		lock.unlock();
	}

	/**
	 * Set the refresh interval in which the commanded motor speeds will be repeated.
	 * Set it to 0 (default) to disable repeating motor commands.
	 * @param milliseconds
	 */
	public void setRefreshInterval(long milliseconds) {
		lock.lock();
		refreshIntervalNs = milliseconds * 1000000;
		long tmp = System.nanoTime() + refreshIntervalNs;
		if (nextRefresh > tmp) nextRefresh = tmp;
		modified.signalAll();
		lock.unlock();
	}
	
	/**
	 * Set the gap (silence) time between commands. Default is 0.
	 * @param milliseconds
	 */
	public void setInterCommandGap(long milliseconds) {
		interCmdGapMs = milliseconds;
	}

	/**
	 * Sets the left motor to the given speed.
	 * Speed is clipped to a range between SPEED_MAX_BACKWARD and SPEED_MAX_FORWARD
	 * @param speed
	 */
	public void setLeftSpeed(int speed) {
		if(speed < SPEED_MAX_BACKWARD) speed = SPEED_MAX_BACKWARD;
		if(speed > SPEED_MAX_FORWARD) speed = SPEED_MAX_FORWARD;
		lock.lock();
		speedLeft = speed; 
		updateLeft = true;
		modified.signalAll();
		lock.unlock();
	}

	/**
	 * get the left motor speed.
	 * @return
	 */
	public int getLeftSpeed() {
		return speedLeft;
	}

	/**
	 * Sets the right motor to the given speed.
	 * Speed is clipped to a range between SPEED_MAX_BACKWARD and SPEED_MAX_FORWARD
	 * @param speed
	 */
	public void setRightSpeed(int speed) {
		if(speed < SPEED_MAX_BACKWARD) speed = SPEED_MAX_BACKWARD;
		if(speed > SPEED_MAX_FORWARD) speed = SPEED_MAX_FORWARD;
		lock.lock();
		speedRight = speed; 
		updateRight = true;
		modified.signalAll();
		lock.unlock();

	}

	/**
	 * get the right motor speed.
	 * @return
	 */
	public int getRightSpeed() {
		return speedRight;
	}

	/**
	 * Sets both left and right speed. Calling this method makes sure both left and right
	 * speed are set atomically, preventing quirky movement.
	 * @param left
	 * @param right
	 */
	public void setLeftRightSpeed(int left, int right)
	{
		lock.lock();
		setLeftSpeed(left);
		setRightSpeed(right);
		lock.unlock();
	}
	
	/**
	 * Sets the aux motor to the given speed.
	 * Speed is clipped to a range between SPEED_MAX_BACKWARD and SPEED_MAX_FORWARD
	 * @param speed
	 */
	public void setAuxSpeed(int speed) {
		if(speed < SPEED_MAX_BACKWARD) speed = SPEED_MAX_BACKWARD;
		if(speed > SPEED_MAX_FORWARD) speed = SPEED_MAX_FORWARD;
		lock.lock();
		speedAux = speed; 
		updateAux = true;
		modified.signalAll();
		lock.unlock();
	}

	/**
	 * get the aux motor speed.
	 * @return
	 */
	public int getAuxSpeed() {
		return speedAux;
	}


	/**
	 * Pause all motors and temporarily release control over the Romo.
	 * No commands will be sent to the Romo until resume is called.
	 */
	public void pause() {
		lock.lock();
		paused = true;
		modified.signalAll();
		lock.unlock();
	}

	/**
	 * Resume control over the Romo. The previous speeds will be commanded again. 
	 */
	public void resume() {
		lock.lock();
		paused = false;
		refresh();
		modified.signalAll();
		lock.unlock();
	}

	/**
	 * Force resending the current command. The refresh timer will be reset also.
	 * Will do nothing if suspended.
	 */
	public void refresh() {
		lock.lock();
		if (!paused) {
			updateLeft = true;
			updateRight = true;
			updateAux = true;
			nextRefresh += refreshIntervalNs;
			if (nextRefresh < System.nanoTime())
				nextRefresh = System.nanoTime() + refreshIntervalNs;
			modified.signalAll();
		}
		lock.unlock();
	}
	
	/**
	 * Check if the Romo is connected
	 * @return true if connected
	 */
	public boolean isConnected(){
		return connected;
	}
	
	/**
	 * Check if MotorControl is paused.
	 * @return
	 */
	public boolean isPaused() {
		return paused;
	}
	
	/**
	 * Check if MotorControl is in control of the headphone jack and sending commands.
	 * @return
	 */
	public boolean isControlling() {
		return controlling;
	}
	
	/**
	 * Set the connection listener that will be called when the connection status of the Romo changes.
	 * Set to null to register no listener.
	 * @param listener
	 * @return the previous listener
	 */
	public RomoConnectionListener setConnectionListener(RomoConnectionListener listener){
		RomoConnectionListener oldListener = connectionListener;
		connectionListener = listener;
		return oldListener;
	}
	
	/**
	 * Get the currently registered connectionlistener.
	 * @return the listener or null if there is none.
	 */
	public RomoConnectionListener getConnectionListener(){
		return connectionListener;
	}
	
	
	/**
	 * The listener that can be registered with the MotorControl object to notify the application
	 * when the Romo is connected or disconnected.
	 * @author Lambertus Gorter
	 *
	 */
	public interface RomoConnectionListener {

		/**
		 * Called when MotorControl connection status has changed. 
		 * This function will be executed on the UI-Thread.
		 * @param connected
		 */
		public void onConnectionChanged(boolean connected);

	}
}
