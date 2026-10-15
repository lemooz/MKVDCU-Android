package org.mkvdcu.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full-size preview: edits never send guest input and only Save commits them. */
public final class GamepadEditorActivity extends Activity {
    private VirtualGamepadView pad;
    private TextView selection;
    private Button recordButton;
    private boolean recording;
    private int virtualButtons,virtualLt,virtualRt,physicalLt,physicalRt;
    private final Map<String,Integer> physicalKeys=new HashMap<>();
    private final Map<Integer,Integer> physicalAxes=new HashMap<>();
    private final MacroCapture macroCapture=new MacroCapture();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams windowParams=getWindow().getAttributes();
        windowParams.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(windowParams);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        GamepadLayout layout=GamepadLayout.load(this);
        if(state!=null)try{layout=GamepadLayout.fromJson(new org.json.JSONObject(state.getString("draft","{}")));}catch(Exception ignored){}
        FrameLayout root=new FrameLayout(this);
        pad=new VirtualGamepadView(this,layout,(b,lx,ly,rx,ry,lt,rt)->{},null,true);
        root.addView(pad,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(0xEF17222E);
        HorizontalScrollView scroll=new HorizontalScrollView(this);LinearLayout tools=new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(tools);panel.addView(scroll);
        button(tools,"SALVAR",()->{
            try{pad.layout().save(this);Toast.makeText(this,"Controle salvo",Toast.LENGTH_SHORT).show();finish();}
            catch(Exception error){Toast.makeText(this,"Não foi possível salvar o controle",Toast.LENGTH_LONG).show();}
        });
        button(tools,"CANCELAR",this::finish);
        button(tools,"CONTROLES",()->{
            String[] names=new String[pad.layout().controls.size()];
            for(int i=0;i<names.length;i++){GamepadLayout.Control c=pad.layout().controls.get(i);names[i]=c.name+(c.visible ? "" : " (oculto)");}
            new AlertDialog.Builder(this).setTitle("Selecionar controle").setItems(names,(d,w)->pad.select(pad.layout().controls.get(w).id)).show();
        });
        button(tools,"TAMANHO",()->{
            GamepadLayout.Control c=pad.selection();if(c==null)return;
            slider("Tamanho — "+c.name,65,165,Math.round(c.size*100),v->{c.size=v/100f;pad.changed();});
        });
        button(tools,"OPACIDADE",()->slider("Opacidade do controle",20,100,Math.round(pad.layout().opacity*100),v->{pad.layout().opacity=v/100f;pad.changed();}));
        button(tools,"NOVO ESPECIAL",()->editMacro(null));
        button(tools,"EDITAR ESPECIAL",this::chooseMacro);
        recordButton=button(tools,"GRAVAR MACRO",this::toggleCapture);
        button(tools,"MAIS",this::more);
        selection=new TextView(this);selection.setTextColor(0xFFB5C7D8);selection.setTextSize(12);selection.setPadding(dp(12),0,dp(12),dp(5));panel.addView(selection);
        root.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        pad.setSelectionListener(c->selection.setText((c==null ? "" : c.name+" • ")+"arraste para mover; tamanho e posição são individuais"));
        pad.select(state!=null ? state.getInt("selected",GamepadLayout.LEFT_STICK) : GamepadLayout.LEFT_STICK);
        setContentView(root);
    }
    private void toggleCapture(){if(recording)stopCapture(true);else startCapture();}
    private void startCapture(){
        macroCapture.reset();physicalKeys.clear();physicalAxes.clear();virtualButtons=virtualLt=virtualRt=physicalLt=physicalRt=0;
        recording=true;pad.setInputCapture((buttons,lt,rt)->{virtualButtons=buttons;virtualLt=lt;virtualRt=rt;updateCaptureState();});
        recordButton.setText("PARAR E SALVAR");selection.setText("GRAVANDO • toque no preview e/ou pressione o controle físico • use PARAR E SALVAR ao terminar");
        Toast.makeText(this,"Gravação iniciada",Toast.LENGTH_SHORT).show();
    }
    private void stopCapture(boolean save){
        if(!recording)return;
        pad.setInputCapture(null);recording=false;virtualButtons=virtualLt=virtualRt=physicalLt=physicalRt=0;physicalKeys.clear();physicalAxes.clear();
        macroCapture.update(0,0,0);List<GamepadLayout.MacroStep> steps=macroCapture.finish();
        recordButton.setText("GRAVAR MACRO");
        if(!save){selection.setText("Gravação descartada • arraste para mover; tamanho e posição são individuais");return;}
        if(steps.isEmpty()){selection.setText("Nenhum comando foi gravado");Toast.makeText(this,"Nenhum botão foi registrado",Toast.LENGTH_SHORT).show();return;}
        saveRecordedMacro(steps);
    }
    private void updateCaptureState(){
        if(recording){
            int buttons=virtualButtons|allPhysicalButtons(),lt=Math.max(virtualLt,physicalLt),rt=Math.max(virtualRt,physicalRt);
            macroCapture.update(buttons,lt,rt);String current=buttonNames(buttons);
            if(lt>=64)current+=(current.isEmpty()?"":"&")+"LT";if(rt>=64)current+=(current.isEmpty()?"":"&")+"RT";
            if(current.isEmpty())current="solto";
            String status="GRAVANDO • "+macroCapture.count()+" passos • agora: "+current+" • PARAR E SALVAR";
            if(!status.contentEquals(selection.getText()))selection.setText(status);
        }
    }
    private int allPhysicalButtons(){int result=0;for(int bit:physicalKeys.values())result|=bit;for(int bit:physicalAxes.values())result|=bit;return result;}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(recording){
            int bit=keyButton(event.getKeyCode());int trigger=keyTrigger(event.getKeyCode());
            if(bit!=0 || trigger!=0){
                String key=event.getDeviceId()+":"+event.getKeyCode();
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(bit!=0)physicalKeys.put(key,bit);if(trigger==1)physicalLt=255;if(trigger==2)physicalRt=255;}
                else if(event.getAction()==KeyEvent.ACTION_UP){physicalKeys.remove(key);if(trigger==1)physicalLt=0;if(trigger==2)physicalRt=0;}
                updateCaptureState();return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(recording && (event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)||event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK))){
            int device=event.getDeviceId();float lx=event.getAxisValue(MotionEvent.AXIS_LTRIGGER),rx=event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            float brake=event.getAxisValue(MotionEvent.AXIS_BRAKE),gas=event.getAxisValue(MotionEvent.AXIS_GAS);
            physicalLt=Math.round(Math.max(Math.max(lx,brake),0)*255);physicalRt=Math.round(Math.max(Math.max(rx,gas),0)*255);
            int dpad=0;float hx=event.getAxisValue(MotionEvent.AXIS_HAT_X),hy=event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if(hy<-.5f)dpad|=1;if(hy>.5f)dpad|=2;if(hx<-.5f)dpad|=4;if(hx>.5f)dpad|=8;
            if(dpad==0)physicalAxes.remove(device);else physicalAxes.put(device,dpad);
            updateCaptureState();return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    private int keyButton(int code){
        switch(code){
            case KeyEvent.KEYCODE_DPAD_UP:return 1;case KeyEvent.KEYCODE_DPAD_DOWN:return 2;case KeyEvent.KEYCODE_DPAD_LEFT:return 4;case KeyEvent.KEYCODE_DPAD_RIGHT:return 8;
            case KeyEvent.KEYCODE_BUTTON_START:return 0x10;case KeyEvent.KEYCODE_BUTTON_SELECT:case KeyEvent.KEYCODE_BACK:return 0x20;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:return 0x40;case KeyEvent.KEYCODE_BUTTON_THUMBR:return 0x80;
            case KeyEvent.KEYCODE_BUTTON_L1:return 0x100;case KeyEvent.KEYCODE_BUTTON_R1:return 0x200;
            case KeyEvent.KEYCODE_BUTTON_A:case KeyEvent.KEYCODE_BUTTON_1:return 0x1000;
            case KeyEvent.KEYCODE_BUTTON_B:case KeyEvent.KEYCODE_BUTTON_2:return 0x2000;
            case KeyEvent.KEYCODE_BUTTON_X:case KeyEvent.KEYCODE_BUTTON_3:return 0x4000;
            case KeyEvent.KEYCODE_BUTTON_Y:case KeyEvent.KEYCODE_BUTTON_4:return 0x8000;
            default:return 0;
        }
    }
    private int keyTrigger(int code){if(code==KeyEvent.KEYCODE_BUTTON_L2)return 1;if(code==KeyEvent.KEYCODE_BUTTON_R2)return 2;return 0;}
    private interface Value {void set(int value);}
    private void slider(String title,int min,int max,int initial,Value changed){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView value=new TextView(this);value.setText(initial+"%");value.setTextColor(Color.WHITE);value.setTextSize(18);content.addView(value);
        SeekBar seek=new SeekBar(this);seek.setMax(max-min);seek.setProgress(initial-min);content.addView(seek,new LinearLayout.LayoutParams(-1,dp(48)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean user){value.setText((progress+min)+"%");if(user)changed.set(progress+min);}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(content).setPositiveButton("Aplicar",(d,w)->changed.set(seek.getProgress()+min))
            .setNegativeButton("Cancelar",(d,w)->changed.set(initial)).create();
        dialog.setOnCancelListener(d->changed.set(initial));dialog.show();
    }
    private void more(){
        GamepadLayout.Control c=pad.selection();
        String[] options={"Zona morta dos analógicos",(pad.layout().mirrorLeftDpad ? "Desativar" : "Ativar")+" analógico esquerdo como direcional",
            (c!=null && c.visible ? "Ocultar" : "Mostrar")+" controle selecionado","Restaurar layout Xbox 360","Centralizar controle selecionado","Excluir botão especial selecionado"};
        new AlertDialog.Builder(this).setTitle("Opções do controle").setItems(options,(d,w)->{
            if(w==0)slider("Zona morta dos analógicos",0,35,Math.round(pad.layout().deadzone*100),v->pad.layout().deadzone=v/100f);
            if(w==1){pad.layout().mirrorLeftDpad=!pad.layout().mirrorLeftDpad;Toast.makeText(this,pad.layout().mirrorLeftDpad ? "Analógico também envia o direcional" : "Analógico e direcional independentes",Toast.LENGTH_SHORT).show();}
            if(w==2 && c!=null){c.visible=!c.visible;pad.changed();pad.select(c.id);}
            if(w==3)pad.replaceLayout(GamepadLayout.defaults());
            if(w==4 && c!=null){c.x=.5f;c.y=.74f;pad.changed();}
            if(w==5 && c!=null && c.kind==GamepadLayout.MACRO){pad.layout().removeMacro(c.macroId);pad.select(GamepadLayout.LEFT_STICK);pad.changed();}
        }).show();
    }
    private void chooseMacro(){
        if(pad.layout().macros.isEmpty()){Toast.makeText(this,"Crie um botão especial primeiro",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[pad.layout().macros.size()];for(int i=0;i<names.length;i++)names[i]=pad.layout().macros.get(i).name;
        new AlertDialog.Builder(this).setTitle("Editar especial").setItems(names,(d,w)->editMacro(pad.layout().macros.get(w))).show();
    }
    private void editMacro(GamepadLayout.Macro existing){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(12),dp(20),dp(8));
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Nome do especial");name.setText(existing==null?"Especial":existing.name);box.addView(name);
        EditText sequence=new EditText(this);sequence.setSingleLine(true);sequence.setHint("← + ← + X");sequence.setText(existing==null?"← + ← + X":formatMacro(existing));box.addView(sequence);
        TextView timing=new TextView(this);timing.setText("Cada passo: 90 ms pressionado + 70 ms de intervalo");timing.setTextColor(0xFFB5C7D8);box.addView(timing);
        SeekBar hold=new SeekBar(this);hold.setMax(280);hold.setProgress(70);box.addView(hold);
        hold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){timing.setText("Cada passo: "+(p+30)+" ms pressionado + 70 ms de intervalo");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Criar botão especial":"Editar botão especial").setMessage("Passos em ordem, separados por +, vírgula ou >. Use & para pressionar botões juntos. Ex.: ← + ← + X").setView(box)
            .setPositiveButton("SALVAR",null).setNegativeButton("CANCELAR",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            java.util.List<GamepadLayout.MacroStep> steps=parseMacro(sequence.getText().toString(),hold.getProgress()+30);
            if(steps.isEmpty()){sequence.setError("Sequência inválida ou vazia");return;}
            String title=name.getText().toString().trim();if(title.isEmpty())title="Especial";
            GamepadLayout.Control control;
            if(existing==null){control=pad.layout().addMacro(title,steps);pad.select(control.id);}else{pad.layout().updateMacro(existing.id,title,steps);control=pad.layout().find(GamepadLayout.FIRST_MACRO_CONTROL+existing.id);}
            pad.changed();dialog.dismiss();
        }));dialog.show();
    }
    private String formatMacro(GamepadLayout.Macro macro){
        StringBuilder out=new StringBuilder();for(GamepadLayout.MacroStep step:macro.steps){if(out.length()>0)out.append(" + ");String buttons=buttonNames(step.buttons);if(step.leftTrigger!=0)buttons+=(buttons.isEmpty()?"":"&")+"LT";if(step.rightTrigger!=0)buttons+=(buttons.isEmpty()?"":"&")+"RT";out.append(buttons);}return out.toString();
    }
    private String buttonNames(int bits){
        String[] names={"↑","↓","←","→","START","BACK","LS","RS","LB","RB","A","B","X","Y"};
        int[] masks={1,2,4,8,0x10,0x20,0x40,0x80,0x100,0x200,0x1000,0x2000,0x4000,0x8000};StringBuilder out=new StringBuilder();
        for(int i=0;i<masks.length;i++)if((bits&masks[i])!=0){if(out.length()>0)out.append('&');out.append(names[i]);}return out.toString();
    }
    private java.util.List<GamepadLayout.MacroStep> parseMacro(String text,int holdMs){
        java.util.List<GamepadLayout.MacroStep> steps=new java.util.ArrayList<>();String[] tokens=text.toUpperCase(java.util.Locale.ROOT).split("[,;>+]",-1);
        if(tokens.length>32)return steps;
        for(String token:tokens){int mask=0;String[] chord=token.trim().split("&");if(chord.length==0)return new java.util.ArrayList<>();
            int lt=0,rt=0;for(String raw:chord){String t=raw.trim();int bit;
                switch(t){case "↑":case "UP":case "U":bit=1;break;case "↓":case "DOWN":case "D":bit=2;break;case "←":case "LEFT":case "L":bit=4;break;case "→":case "RIGHT":case "R":bit=8;break;
                    case "LT":bit=0;lt=255;break;case "RT":bit=0;rt=255;break;
                    case "START":case "ST":bit=0x10;break;case "BACK":case "SELECT":bit=0x20;break;case "LS":case "L3":bit=0x40;break;case "RS":case "R3":bit=0x80;break;case "LB":bit=0x100;break;case "RB":bit=0x200;break;case "A":bit=0x1000;break;case "B":bit=0x2000;break;case "X":bit=0x4000;break;case "Y":bit=0x8000;break;default:return new java.util.ArrayList<>();}
                mask|=bit;
            }
            if(mask==0 && lt==0 && rt==0)return new java.util.ArrayList<>();steps.add(new GamepadLayout.MacroStep(mask,holdMs,70,lt,rt));
        }return steps;
    }
    private String formatRecordedSteps(List<GamepadLayout.MacroStep> steps){
        StringBuilder out=new StringBuilder();for(GamepadLayout.MacroStep step:steps){if(out.length()>0)out.append("  →  ");String buttons=buttonNames(step.buttons);if(step.leftTrigger!=0)buttons+=(buttons.isEmpty()?"":"&")+"LT";if(step.rightTrigger!=0)buttons+=(buttons.isEmpty()?"":"&")+"RT";out.append(buttons).append(" (").append(step.holdMs).append(" ms)");}return out.toString();
    }
    private void saveRecordedMacro(List<GamepadLayout.MacroStep> steps){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Nome do especial");name.setText("Especial");
        TextView preview=new TextView(this);preview.setText(formatRecordedSteps(steps));preview.setTextColor(0xFFB5C7D8);preview.setPadding(dp(20),dp(8),dp(20),dp(8));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.addView(name);content.addView(preview);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Salvar macro gravada").setMessage("A gravação preserva a ordem e o tempo de cada toque. Você poderá mover e redimensionar o botão depois.").setView(content)
            .setPositiveButton("SALVAR",(d,w)->{
                String title=name.getText().toString().trim();if(title.isEmpty())title="Especial";
                GamepadLayout.Control control=pad.layout().addMacro(title,steps);pad.select(control.id);pad.changed();
                selection.setText("Macro "+title+" criada • arraste o botão roxo para posicioná-lo");
            }).setNegativeButton("DESCARTAR",null).create();dialog.show();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setMinHeight(0);b.setMinimumHeight(0);
        parent.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(40)));b.setOnClickListener(v->action.run());return b;}
    private static final class CaptureStep {final int buttons;int holdMs,gapMs,leftTrigger,rightTrigger;CaptureStep(int b,int h,int lt,int rt){buttons=b;holdMs=h;leftTrigger=lt;rightTrigger=rt;}}
    private static final class MacroCapture {
        private static final int MAX_STEPS=32,CHORD_WINDOW_MS=70;
        private final ArrayList<CaptureStep> steps=new ArrayList<>();
        private int activeButtons,activeLt,activeRt;
        private long activeSince,releasedAt;
        void reset(){steps.clear();activeButtons=activeLt=activeRt=0;activeSince=releasedAt=0;}
        void update(int buttons,int lt,int rt){
            buttons&=0xF0FF;lt=lt>=64?255:0;rt=rt>=64?255:0;
            if(buttons==activeButtons && lt==activeLt && rt==activeRt)return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(activeButtons==0 && activeLt==0 && activeRt==0){
                if(buttons==0 && lt==0 && rt==0)return;
                if(steps.size()>=MAX_STEPS)return;
                if(!steps.isEmpty())steps.get(steps.size()-1).gapMs=clamp(now-releasedAt,0,500);
                activeButtons=buttons;activeLt=lt;activeRt=rt;activeSince=now;return;
            }
            if(buttons==0 && lt==0 && rt==0){close(now);releasedAt=now;activeButtons=activeLt=activeRt=0;return;}
            boolean addsOnly=((buttons&activeButtons)==activeButtons && lt>=activeLt && rt>=activeRt);
            if(addsOnly && now-activeSince<=CHORD_WINDOW_MS){activeButtons=buttons;activeLt=lt;activeRt=rt;return;}
            close(now);
            if(steps.size()>=MAX_STEPS){activeButtons=activeLt=activeRt=0;return;}
            activeButtons=buttons;activeLt=lt;activeRt=rt;activeSince=now;
        }
        private void close(long now){
            if(steps.size()>=MAX_STEPS)return;
            int held=clamp(now-activeSince,30,500);
            steps.add(new CaptureStep(activeButtons,held,activeLt,activeRt));
        }
        int count(){return steps.size()+((activeButtons!=0 || activeLt!=0 || activeRt!=0)?1:0);}
        List<GamepadLayout.MacroStep> finish(){
            if(activeButtons!=0 || activeLt!=0 || activeRt!=0)close(android.os.SystemClock.elapsedRealtime());
            activeButtons=activeLt=activeRt=0;ArrayList<GamepadLayout.MacroStep> result=new ArrayList<>();
            for(CaptureStep step:steps)result.add(new GamepadLayout.MacroStep(step.buttons,step.holdMs,step.gapMs,step.leftTrigger,step.rightTrigger));
            return result;
        }
        private static int clamp(long v,int min,int max){return (int)Math.max(min,Math.min(max,v));}
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);try{state.putString("draft",pad.layout().toJson().toString());}catch(Exception ignored){}
        if(pad.selection()!=null)state.putInt("selected",pad.selection().id);
    }
    @Override protected void onStop(){if(recording)stopCapture(false);super.onStop();}
}
