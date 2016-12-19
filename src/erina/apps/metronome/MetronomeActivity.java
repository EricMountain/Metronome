package erina.apps.metronome;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Spinner;
import android.widget.Toast;


/*
 * Done: less irritating input
 * Todo: proper sounds, including bell
 * Done: Make volume control button control steam_music volume.
 * Done: nicer input options
 * Todo: refactor: make the activity do all the UI stuff, and it then simply sets metronome properties
 * Todo: save and restore last state
 * Todo: measure accuracy
 * Todo: visual beat, option to be quiet
 * Todo: Beats per bar, option to emphasise 1st beat
 * Todo: option to run quarter, half 3/4 speed
 * Todo; keep phone unlocked, but allow screen to turn off if poss. (but I guess it will lock itself? make it an option?)
 */
public class MetronomeActivity extends Activity {
	
	private static final String TAG = "MetronomeActivity";
	
	private static int MILLIS_PER_MINUTE = 60 * 1000;
	
	private static final int MIN_BEATS_PER_MINUTE = 1;
	private static final int MAX_BEATS_PER_MINUTE = 300;
	
	private int beatsPerMinute;
	private int volumePct = 80;
	
	private Metronome metronome;
	private Thread metronomeThread;
	
	private Spinner beatsPerBarSpinner;
	
	public void adjustBeatsPerMinute(View view) {
		Button button = (Button) view;

		metronome.adjustBeatsPerMinute(button.getText());
	}
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Want hardware volume control buttons to control the stream we use.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        
        CheckBox beatsPerBarCheckBox = (CheckBox) findViewById(R.id.BeatsPerBarCheckBox);
        beatsPerBarCheckBox.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				CheckBox cb = (CheckBox) v;
				
				beatsPerBarSpinner.setEnabled(cb.isChecked());
				metronome.setBeatsPerBarEnabled(cb.isChecked());
			}
		});
        
        beatsPerBarSpinner = (Spinner) findViewById(R.id.beatsPerBarSpinner);
        beatsPerBarSpinner.setEnabled(beatsPerBarCheckBox.isChecked());
        beatsPerBarSpinner.setOnItemSelectedListener(new BeatsPerBarSpinnerOnItemSelectedListener());
        beatsPerBarSpinner.setSelection(0);
        
    }
    
    @Override
    public void onResume() {
    	super.onResume();
        
    	// Note metronome initialisation depends on layout setup.
        metronome = new Metronome(this);
        metronomeThread = new Thread(metronome);
        metronomeThread.start();
    }
    
    @Override
    public void onPause() {
    	super.onPause();
    	metronome.isTimeToStop = true;
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	
    	metronome.isTimeToStop = true;
    	metronomeThread = null;
    	metronome = null;
    }
    
    class BeatsPerBarSpinnerOnItemSelectedListener implements OnItemSelectedListener {

		@Override
		public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
			int beats = Integer.parseInt(parent.getItemAtPosition(pos).toString());
			metronome.setBeatsPerBar(beats);
					
		}

		@Override
		public void onNothingSelected(AdapterView<?> parent) {
			// No change
			
		}
    	
    }
    
    class Metronome implements Runnable, TextWatcher, OnSeekBarChangeListener, SoundPool.OnLoadCompleteListener {
    	private final MetronomeActivity parent;
    	
    	private EditText beatsPerMinuteText;
    	private SeekBar beatsPerMinuteSeekBar;
    	
    	private SoundPool soundPool;
    	
    	private static final int MAX_STREAMS = 2;
    	
    	/* Hardcode as suggested in SoundPool documentation. */
    	private static final int SRC_QUALITY = 0;
    	
    	/* Hardcode as suggested in SoundPool documentation. */
    	private static final int PRIORITY = 1;
    	
    	boolean isTimeToStop = false;
    		
    	private int majorClickStream;
    	private int minorClickStream;
    	
    	private boolean majorClickLoaded = false;
    	private boolean minorClickLoaded = false;
    	
    	private AudioManager audioManager;
    	
    	private boolean beatsPerBarEnabled = false;
    	private int beatsPerBar = -1;
    	
    	
    	Metronome(MetronomeActivity parent) {
    		this.parent = parent;
    		
    		audioManager = (AudioManager) parent.getSystemService(Context.AUDIO_SERVICE);
    		
    		soundPool = new SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, SRC_QUALITY);
    		soundPool.setOnLoadCompleteListener(this);
    		//int majorClickRes = getResId("majorclick", R.raw.class);
    		//Log.e(TAG, "majorClickRes = " + majorClickRes);
    		//majorClickStream = soundPool.load(parent.getApplicationContext(), majorClickRes, PRIORITY);
    		majorClickStream = soundPool.load(parent.getApplicationContext(), R.raw.majorclick, PRIORITY);
    		minorClickStream = soundPool.load(parent.getApplicationContext(), R.raw.minorclick, PRIORITY);
    		
            beatsPerMinuteText = (EditText) findViewById(R.id.beatsPerMinuteText);
            beatsPerMinuteText.addTextChangedListener(this);
            
            // Todo: handle invalid value
            beatsPerMinute = Integer.parseInt(beatsPerMinuteText.getText().toString());
            
            beatsPerMinuteSeekBar = (SeekBar) findViewById(R.id.beatsPerMinuteSeekBar);
            beatsPerMinuteSeekBar.setOnSeekBarChangeListener(this);
            beatsPerMinuteSeekBar.setMax(MAX_BEATS_PER_MINUTE - MIN_BEATS_PER_MINUTE);
            setSeekBarProgressFromBeatsPerMinute(beatsPerMinute);
            
           
    	}
    	
    	private void setBeatsPerBarEnabled(boolean enabled) {
    		beatsPerBarEnabled = enabled;
    	}
    	
    	private void setBeatsPerBar(int beats) {
    		beatsPerBar = beats;
    		Log.e(TAG, "Beats per bar: " + beats);
    	}
    	
    	public void run() {
    		int beatCount = 0;
            ToneGenerator toneManager = new ToneGenerator(AudioManager.STREAM_MUSIC, volumePct);
            
            while (true) {
            	//long now1 = SystemClock.uptimeMillis();
            	//toneManager.startTone(ToneGenerator.TONE_PROP_BEEP);
            	if (majorClickLoaded) {
            		final float currentVolume = ((float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) /
            			((float) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            		//currentVolume = .99f;
            		Log.e(TAG, "currentVolume: " + currentVolume);
            		//soundPool.autoResume();
            		
            		if ((beatsPerBarEnabled) && beatsPerBar > 0 && beatCount % beatsPerBar == 0) {
            			int result = soundPool.play(majorClickStream, currentVolume, currentVolume, 0, 0, 1.0f);
            			Log.e(TAG, "play = " + result);
            			
            			beatCount = 1;
            		} else {
            			int result = soundPool.play(minorClickStream, currentVolume, currentVolume, 0, 0, 1.0f);
            			Log.e(TAG, "play = " + result);
            			
            			++beatCount;
            		}
            	} else {
            		Log.e(TAG, "major click not yet loaded");
            	}
            	
            	//long now2 = SystemClock.uptimeMillis();
            	//Log.e(TAG, "tone duration ms: " + (now2 - now1));
            	
            	int lastBeatsPerMinute = beatsPerMinute;
            	long beatIntervalMillis = MILLIS_PER_MINUTE / beatsPerMinute;
                	
    	        long now = SystemClock.uptimeMillis();
    	        long nextBeatTimeMillis = now + beatIntervalMillis;
    	        
    	        do {
    	        	Log.e(TAG, "interval ms: " + Long.toString(beatIntervalMillis));
    	        	
    	        	try {
    	        		Thread.sleep(beatIntervalMillis);
    	        	} catch (InterruptedException interrupt) {
    	        		if (lastBeatsPerMinute != beatsPerMinute) {
    	        			// User setting has been changed
    	        			break;
    	        		}
    	        	}
    	        	
    	        	now = SystemClock.uptimeMillis();
    	        	beatIntervalMillis = nextBeatTimeMillis - now;
    	        	
    	        } while (beatIntervalMillis > 0);
    	        
    	        if (isTimeToStop) {
    	        	isTimeToStop = false;
    	        	break;
    	        }
            }
    	}
    	
    	private void handleInvalidFrequency(Editable newFrequency) {
   		
    		Toast toast = Toast.makeText(parent.getApplicationContext(), 
    						"Please enter a frequency between " +
    						MIN_BEATS_PER_MINUTE + " and " + MAX_BEATS_PER_MINUTE +
    						" beats per minute.",
    						Toast.LENGTH_LONG);
    		toast.show();
    	}
    	
    	private boolean isInRange(int value) {
    		return value <= MAX_BEATS_PER_MINUTE && value >= MIN_BEATS_PER_MINUTE;
    	}
    	
        public void afterTextChanged(Editable s) {
        	try {
	        	int newBeatsPerMinute = Integer.parseInt(s.toString());
	        
	        	if (!isInRange(newBeatsPerMinute)) {
	        		throw new NumberFormatException("Frequency outside acceptable range.");
	        	}
	        	
	        	parent.beatsPerMinute = newBeatsPerMinute;
            	parent.metronomeThread.interrupt();
            	
            	setSeekBarProgressFromBeatsPerMinute(newBeatsPerMinute);
                Log.e(TAG, "bpm: " + Integer.toString(parent.beatsPerMinute));
        	
        	} catch (NumberFormatException badNumber) {
        		handleInvalidFrequency(s);
        	}
        }
        
        public void beforeTextChanged(CharSequence s, int start, int count, int after){}
        
        public void onTextChanged(CharSequence s, int start, int before, int count){}

        private int beatsPerMinuteToSeekBar(int bpm) {
        	return bpm - MIN_BEATS_PER_MINUTE;
        }
        
        private int seekBarToBeatsPerMinute(int progress) {
        	return progress + MIN_BEATS_PER_MINUTE;
        }

        private void setSeekBarProgressFromBeatsPerMinute(int bpm) {
        	beatsPerMinuteSeekBar.setProgress(beatsPerMinuteToSeekBar(bpm));
        }
        
        private void setBeatsPerMinuteTextFromSeekBar(int progress) {
        	beatsPerMinuteText.setText(Integer.toString(seekBarToBeatsPerMinute(progress)));
        }
        
		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser) {
				setBeatsPerMinuteTextFromSeekBar(progress);
			}
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {}

		void adjustBeatsPerMinute(CharSequence buttonText) {
			int addOrSubtract = (buttonText.charAt(0) == '-' ? -1 : +1);
			
			int value = 1;
			int len = buttonText.length(); 
			if (len > 1) {
				value = Integer.parseInt(buttonText.subSequence(1, len).toString());
			}
			
			setBeatsPerMinuteWidgets(parent.beatsPerMinute + addOrSubtract * value);
		}
		
		private void setBeatsPerMinuteWidgets(int value) {
			// FIXME check in range
			if (isInRange(value)) {
				beatsPerMinuteText.setText(Integer.toString(value));
			}
			
		}


		@Override
		public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
			Log.e(TAG, "Sound loaded: " + sampleId + ", " + status);
			if (status == 0) {
				if (sampleId == majorClickStream) {
					majorClickLoaded = true;
				} else if (sampleId == minorClickStream) {
					minorClickLoaded = true;
				} else {
					Log.e(TAG, "Unknown sound loaded, id=" + sampleId + ", status=" + status);
				}
			}
		}
    }
}