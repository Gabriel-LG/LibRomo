package com.github.gabriel_lg.romotive.libromodemo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Toast;

import com.github.gabriel_lg.romotive.libromo.MotorControl;
import com.github.gabriel_lg.romotive.libromo.MotorControl.RomoConnectionListener;
import com.github.gabriel_lg.romotive.libromodemo.Joystick.JoystickPositionChangedListener;

public class MainActivity extends Activity {
    private MotorControl control;
    private Joystick joystick;
    private CheckBox checkbox;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        control = new MotorControl(this);
        control.setRefreshInterval(1000);
        control.setInterCommandGap(12);
        control.setConnectionListener(new RomoConnectionListener() {
			public void onConnectionChanged(boolean connected) {
				Toast.makeText(MainActivity.this, "Romo "+(connected?"connected":"disconnected"), Toast.LENGTH_SHORT).show();
			}
		});
        joystick = (Joystick)findViewById(R.id.joystick1);
        joystick.setPositionChangedListener(new JoystickPositionChangedListener() {
			public void onPositionChanged(int leftSpeed, int rightSpeed) {
				control.setLeftRightSpeed(leftSpeed, rightSpeed);
				control.setAuxSpeed(MotorControl.SPEED_STOP);
			}
		});
        joystick.setCruiseControl(true);
        checkbox = (CheckBox)findViewById(R.id.checkBox1);
        checkbox.setChecked(false);
        checkbox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				joystick.setCruiseControl(!isChecked);
				control.setLeftRightSpeed(MotorControl.SPEED_STOP, MotorControl.SPEED_STOP);
			}
		});
    }

    @Override
    public void onPause() {
    	super.onPause();
    	control.pause();
    }
    
    @Override
    public void onResume() {
    	super.onResume();
    	control.resume();
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	control.destroy();
    }
}
