package com.github.gabriel_lg.romotive.libromodemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.github.gabriel_lg.romotive.libromo.MotorControl;

public class Joystick extends View {
	private int leftSpeed = MotorControl.SPEED_STOP;
	private int rightSpeed = MotorControl.SPEED_STOP;
	private boolean cruiseControl = false;
	private JoystickPositionChangedListener listener = null;
	private float posX = 0;
	private float posY = 0;
	
	public Joystick(Context context) {
		super(context);
	}
	
	public Joystick(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public Joystick(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec));
	}
	
	private Paint paint = new Paint();
	private Path base = new Path();
	
	@Override
	public void onDraw(Canvas canvas) {
		long width = getWidth();
		long height = getHeight();
		//set paint properties
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeCap(Cap.ROUND);
		paint.setAntiAlias(true);
		paint.setStrokeWidth(width/50);
		//draw base
		base.moveTo(0, 0);
		base.lineTo(width, 0);
		base.lineTo(width, height);
		base.lineTo(0, height);
		base.lineTo(0, 0);
		base.moveTo(width/2, 0);
		base.lineTo(width/2, height);
		base.moveTo(0, height/2);
		base.lineTo(width, height/2);
		paint.setColor(0xFFFFFFFF);
		canvas.drawPath(base, paint);
		base.rewind();
		//draw knob
		paint.setColor(0xFFFF0000);
		paint.setStyle(Paint.Style.FILL);
		canvas.drawCircle(posX/2*width+width/2, posY/2*height+height/2, width/10, paint);
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if(!cruiseControl && event.getAction() == MotionEvent.ACTION_UP) {
			posX = 0;
			posY = 0;
		}else{
			posX = 2*event.getX() / getWidth() -1;
			posY = 2*event.getY() / getHeight() - 1;
			posX = Math.max(-1, posX);
			posY = Math.max(-1, posY);
			posX = Math.min(1, posX);
			posY = Math.min(1, posY);
		}

		double power = -posY;
		double leftRatio = posX<0 ? 2*posX + 1 : 1;
		double rightRatio = posX>0 ? -2*posX + 1 : 1;

		
		leftSpeed = (int)(leftRatio * power * 127);
		rightSpeed = (int)(rightRatio * power * 127);
		if(listener != null) {
			listener.onPositionChanged( leftSpeed, rightSpeed);
		}

		invalidate();
		return true;
	}
	
	
	public JoystickPositionChangedListener setPositionChangedListener(JoystickPositionChangedListener listener) {
		JoystickPositionChangedListener retval = listener;
		this.listener = listener;
		return retval;
	}
	
	public interface JoystickPositionChangedListener {
		public void onPositionChanged(int leftSpeed, int rightSpeed);
	}

	public void setCruiseControl(boolean b) {
		cruiseControl = b;
		posX = 0;
		posY = 0;
		invalidate();
	}
	
}
