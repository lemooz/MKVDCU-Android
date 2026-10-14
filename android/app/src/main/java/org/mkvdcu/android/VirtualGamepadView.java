package org.mkvdcu.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/** Complete Xbox 360 touch surface; drawing and hit areas share one model. */
final class VirtualGamepadView extends View {
    interface StateSink {void send(int buttons,int lx,int ly,int rx,int ry,int lt,int rt);}
    interface SelectionListener {void selected(GamepadLayout.Control control);}
    private static final int NONE=-1,TOGGLE=-2,EDIT=-3,MENU=-4;
    private static final class Touch {
        final int control;float x,y;
        Touch(int control,float x,float y){this.control=control;this.x=x;this.y=y;}
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final SparseArray<Touch> touches=new SparseArray<>();
    private final StateSink sink;
    private final Runnable customize;
    private final Runnable onMenu;
    private final boolean editor;
    private GamepadLayout layout;
    private boolean visible=true;
    private int selected=-1,dragPointer=-1;
    private float dragDx,dragDy,drawAlpha=1;
    private SelectionListener listener;
    VirtualGamepadView(Context context,StateSink sink,Runnable customize){this(context,GamepadLayout.load(context),sink,customize,null,false);}
    VirtualGamepadView(Context context,StateSink sink,Runnable customize,Runnable onMenu){this(context,GamepadLayout.load(context),sink,customize,onMenu,false);}
    VirtualGamepadView(Context context,GamepadLayout layout,StateSink sink,Runnable customize,boolean editor){this(context,layout,sink,customize,null,editor);}
    VirtualGamepadView(Context context,GamepadLayout layout,StateSink sink,Runnable customize,Runnable onMenu,boolean editor){
        super(context);this.layout=layout;this.sink=sink;this.customize=customize;this.onMenu=onMenu;this.editor=editor;
        setWillNotDraw(false);setLayerType(View.LAYER_TYPE_HARDWARE,null);
        setContentDescription(editor ? "Editor do controle Xbox 360. Arraste os controles." : "Controle virtual Xbox 360");
    }
    boolean isPadVisible(){return visible;}
    void setPadVisible(boolean v){if(visible!=v){releaseAll();visible=v;invalidate();}}
    GamepadLayout layout(){return layout;}
    void setSelectionListener(SelectionListener l){listener=l;}
    GamepadLayout.Control selection(){return layout.find(selected);}
    void select(int id){selected=id;if(listener!=null)listener.selected(selection());invalidate();}
    void replaceLayout(GamepadLayout value){releaseAll();layout=value;select(GamepadLayout.LEFT_STICK);constrain();}
    void reloadLayout(){if(!editor){releaseAll();layout=GamepadLayout.load(getContext());constrain();invalidate();}}
    void changed(){constrain();invalidate();}
    void releaseAll(){touches.clear();dragPointer=-1;sink.send(0,0,0,0,0,0,0);invalidate();}
    private void constrain(){for(GamepadLayout.Control c:layout.controls)layout.constrain(c,getWidth(),getHeight());}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);releaseAll();constrain();}
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}
    private void ink(int color){paint.setColor((Math.round(Color.alpha(color)*drawAlpha)<<24)|(color&0xFFFFFF));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);drawAlpha=1;
        if(editor){
            canvas.drawColor(0xFF101820);ink(0xFF1E2B38);paint.setStrokeWidth(1);
            float step=getHeight()*.10f;
            for(float x=0;x<getWidth();x+=step)canvas.drawLine(x,0,x,getHeight(),paint);
            for(float y=0;y<getHeight();y+=step)canvas.drawLine(0,y,getWidth(),y,paint);
        }
        // Per-primitive alpha avoids an offscreen framebuffer for every button.
        if(visible || editor)for(GamepadLayout.Control c:layout.controls){
            if(!c.visible && !editor)continue;drawAlpha=c.visible ? layout.opacity : .22f;
            drawControl(canvas,c);drawAlpha=1;
            if(editor && c.id==selected){bounds(c,rect);rect.inset(-6,-6);paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3);ink(0xFF6DE6B1);canvas.drawRoundRect(rect,18,18,paint);paint.setStyle(Paint.Style.FILL);}
        }
        if(!editor){drawToolbar(canvas);}
    }
    private void bounds(GamepadLayout.Control c,RectF out){
        float x=c.x*getWidth(),y=c.y*getHeight(),w=c.halfWidth(GamepadLayout.unit(getWidth(),getHeight())),h=c.halfHeight(GamepadLayout.unit(getWidth(),getHeight()));out.set(x-w,y-h,x+w,y+h);
    }
    private void toolbarBounds(int slot,RectF out){
        float unit=Math.max(34,getHeight()*.054f),width=unit*2.35f,gap=10;
        float right=getWidth()-12-(2-slot)*(width+gap);
        out.set(right-width,12,right,12+unit);
    }
    private void drawToolbarButton(Canvas canvas,int slot,String label,int bg,int strokeColor){
        toolbarBounds(slot,rect);ink(bg);canvas.drawRoundRect(rect,12,12,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);ink(strokeColor);canvas.drawRoundRect(rect,12,12,paint);
        paint.setStyle(Paint.Style.FILL);text(canvas,label,rect.centerX(),rect.centerY(),rect.height()*.33f,Color.WHITE);
    }
    private void drawToolbar(Canvas canvas){
        drawToolbarButton(canvas,0,"MENU",0xD9121622,0xFFD4A853);
        drawToolbarButton(canvas,1,visible ? "PAD −" : "PAD +",0xD9121622,0xFF94A3B8);
        drawToolbarButton(canvas,2,"AJUSTAR",0xD9121622,0xFF3B82F6);
    }
    private void drawControl(Canvas canvas,GamepadLayout.Control c){
        float x=c.x*getWidth(),y=c.y*getHeight(),r=c.radius(GamepadLayout.unit(getWidth(),getHeight()));boolean down=pressed(c.id);
        if(c.kind==GamepadLayout.STICK){
            ink(0xC41B2531);canvas.drawCircle(x,y,r,paint);ring(canvas,x,y,r,0xFF6C7F92,2);ring(canvas,x,y,r*.83f,0x606C7F92,1);
            float[] a=axes(c,false);float kx=x+a[0]*r*.47f,ky=y-a[1]*r*.47f;
            ink(down ? 0xFF677C90 : 0xFF34485A);canvas.drawCircle(kx,ky,r*.46f,paint);ring(canvas,kx,ky,r*.46f,0xFFBAC7D4,2);
            text(canvas,c.label,kx,ky,r*.33f,0xFFE4EBF2);return;
        }
        if(c.kind==GamepadLayout.DPAD){
            ink(0x85202D3A);canvas.drawCircle(x,y,r,paint);ring(canvas,x,y,r,0x806C7F92,1);
            float arm=r*.35f;Path path=new Path();path.moveTo(x-arm,y-r);path.lineTo(x+arm,y-r);
            path.lineTo(x+arm,y-arm);path.lineTo(x+r,y-arm);path.lineTo(x+r,y+arm);path.lineTo(x+arm,y+arm);
            path.lineTo(x+arm,y+r);path.lineTo(x-arm,y+r);path.lineTo(x-arm,y+arm);path.lineTo(x-r,y+arm);
            path.lineTo(x-r,y-arm);path.lineTo(x-arm,y-arm);path.close();ink(0xF12B3947);canvas.drawPath(path,paint);
            paint.setStyle(Paint.Style.STROKE);ink(0xFFC0CBD6);paint.setStrokeWidth(2);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            int bits=dpad(c);String[] glyphs={"▲","▼","◀","▶"};float[] xx={x,x,x-r*.66f,x+r*.66f},yy={y-r*.66f,y+r*.66f,y,y};
            for(int i=0;i<4;i++)text(canvas,glyphs[i],xx[i],yy[i],r*.28f,(bits&(1<<i))!=0 ? 0xFF8FE09C : 0xFFBFCBD7);return;
        }
        if(c.round()){
            ink(c.bit==0x0400 ? 0xFFD0D7DC : c.color);canvas.drawCircle(x,y,r,paint);ring(canvas,x,y,r,down ? Color.WHITE : 0xBBFFFFFF,down ? 4 : 2);
            ink(down ? 0x55FFFFFF : 0x22FFFFFF);canvas.drawCircle(x-r*.22f,y-r*.30f,r*.32f,paint);
            if(c.bit==0x0400)ring(canvas,x,y,r*.81f,0xFF67BE43,4);
            text(canvas,c.label,x,y,r*.88f,(c.bit==0x8000 || c.bit==0x0400) ? 0xFF243124 : Color.WHITE);
        }else{
            bounds(c,rect);ink(down ? 0xFF597684 : 0xEA243341);canvas.drawRoundRect(rect,rect.height()*.33f,rect.height()*.33f,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(down ? 3 : 1.5f);ink(down ? Color.WHITE : 0xFFB7C4D1);
            canvas.drawRoundRect(rect,rect.height()*.33f,rect.height()*.33f,paint);paint.setStyle(Paint.Style.FILL);
            text(canvas,c.label,x,y,rect.height()*.40f,Color.WHITE);
            if(c.kind==GamepadLayout.TRIGGER){ink(down ? 0xFF83D785 : 0xFF637689);canvas.drawRoundRect(rect.left+10,rect.bottom-7,rect.right-10,rect.bottom-4,2,2,paint);}
        }
    }
    private void ring(Canvas c,float x,float y,float r,int color,float stroke){ink(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);c.drawCircle(x,y,r,paint);paint.setStyle(Paint.Style.FILL);}
    private void text(Canvas c,String text,float x,float y,float size,int color){ink(color);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize(size);c.drawText(text,x,y-(paint.ascent()+paint.descent())*.5f,paint);}
    private boolean pressed(int id){for(int i=0;i<touches.size();i++)if(touches.valueAt(i).control==id)return true;return false;}
    private float[] axes(GamepadLayout.Control c,boolean deadzone){
        for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.control!=c.id)continue;
            float x=(t.x-c.x*getWidth())/c.radius(GamepadLayout.unit(getWidth(),getHeight())),y=(c.y*getHeight()-t.y)/c.radius(GamepadLayout.unit(getWidth(),getHeight()));
            float length=(float)Math.hypot(x,y);if(length==0)return new float[]{0,0};float magnitude=Math.min(1,length);
            if(deadzone)magnitude=Math.max(0,(magnitude-layout.deadzone)/(1-layout.deadzone));return new float[]{x/length*magnitude,y/length*magnitude};
        }return new float[]{0,0};
    }
    private int dpad(GamepadLayout.Control c){
        int bits=0;for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.control!=c.id)continue;
            float x=(t.x-c.x*getWidth())/c.radius(GamepadLayout.unit(getWidth(),getHeight())),y=(t.y-c.y*getHeight())/c.radius(GamepadLayout.unit(getWidth(),getHeight()));if(Math.hypot(x,y)<.20f)continue;
            if(y<-.27f)bits|=1;if(y>.27f)bits|=2;if(x<-.27f)bits|=4;if(x>.27f)bits|=8;
        }return bits;
    }
    private int target(float x,float y){
        if(!editor){
            toolbarBounds(0,rect);if(rect.contains(x,y))return MENU;
            toolbarBounds(1,rect);if(rect.contains(x,y))return TOGGLE;
            toolbarBounds(2,rect);if(rect.contains(x,y))return EDIT;
            if(!visible)return NONE;
        }
        for(int i=layout.controls.size()-1;i>=0;i--){GamepadLayout.Control c=layout.controls.get(i);if(!c.visible && !editor)continue;bounds(c,rect);
            if(c.round()){if(Math.hypot(x-rect.centerX(),y-rect.centerY())<=c.radius(GamepadLayout.unit(getWidth(),getHeight())))return c.id;}else if(rect.contains(x,y))return c.id;
        }return NONE;
    }
    private void sendState(){
        int buttons=0,lt=0,rt=0;float lx=0,ly=0,rx=0,ry=0;
        for(GamepadLayout.Control c:layout.controls){if(!c.visible)continue;
            if(c.kind==GamepadLayout.DPAD)buttons|=dpad(c);
            else if(c.kind==GamepadLayout.STICK){float[] a=axes(c,true);if(c.id==GamepadLayout.LEFT_STICK){lx=a[0];ly=a[1];}else{rx=a[0];ry=a[1];}}
            else if(pressed(c.id)){if(c.id==GamepadLayout.LEFT_TRIGGER)lt=255;else if(c.id==GamepadLayout.RIGHT_TRIGGER)rt=255;else buttons|=c.bit;}
        }
        if(layout.mirrorLeftDpad){if(lx<-.35f)buttons|=4;if(lx>.35f)buttons|=8;if(ly>.35f)buttons|=1;if(ly<-.35f)buttons|=2;}
        sink.send(buttons,Math.round(lx*32767),Math.round(ly*32767),Math.round(rx*32767),Math.round(ry*32767),lt,rt);invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getPointerCount()==0){releaseAll();return false;}
        int action=event.getActionMasked(),index=event.getActionIndex(),id=event.getPointerId(index);
        if(action==MotionEvent.ACTION_CANCEL){releaseAll();return true;}
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN){
            int control=target(event.getX(index),event.getY(index));
            if(control==MENU){releaseAll();if(onMenu!=null)onMenu.run();performClick();return true;}
            if(control==TOGGLE){releaseAll();visible=!visible;invalidate();performClick();return true;}
            if(control==EDIT){releaseAll();if(customize!=null)customize.run();performClick();return true;}
            if(control==NONE)return editor || touches.size()>0;
            if(editor){if(dragPointer!=-1)return true;select(control);dragPointer=id;GamepadLayout.Control c=selection();dragDx=event.getX(index)-c.x*getWidth();dragDy=event.getY(index)-c.y*getHeight();return true;}
            GamepadLayout.Control c=layout.find(control);if(c.kind==GamepadLayout.STICK && pressed(control))return true;
            touches.put(id,new Touch(control,event.getX(index),event.getY(index)));sendState();return true;
        }
        if(action==MotionEvent.ACTION_MOVE){
            if(editor){int slot=event.findPointerIndex(dragPointer);GamepadLayout.Control c=selection();if(slot>=0 && c!=null){c.x=(event.getX(slot)-dragDx)/getWidth();c.y=(event.getY(slot)-dragDy)/getHeight();layout.constrain(c,getWidth(),getHeight());invalidate();}return true;}
            for(int i=0;i<event.getPointerCount();i++){Touch t=touches.get(event.getPointerId(i));if(t!=null){t.x=event.getX(i);t.y=event.getY(i);}}
            if(touches.size()>0)sendState();return touches.size()>0;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP){if(editor){if(id==dragPointer)dragPointer=-1;return true;}
            boolean owned=touches.get(id)!=null;touches.remove(id);sendState();return owned;}
        return editor || touches.size()>0;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
