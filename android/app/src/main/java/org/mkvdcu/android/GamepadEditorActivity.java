package org.mkvdcu.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
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

/** Full-size preview: edits never send guest input and only Save commits them. */
public final class GamepadEditorActivity extends Activity {
    private VirtualGamepadView pad;
    private TextView selection;
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
        button(tools,"MAIS",this::more);
        selection=new TextView(this);selection.setTextColor(0xFFB5C7D8);selection.setTextSize(12);selection.setPadding(dp(12),0,dp(12),dp(5));panel.addView(selection);
        root.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        pad.setSelectionListener(c->selection.setText((c==null ? "" : c.name+" • ")+"arraste para mover; tamanho e posição são individuais"));
        pad.select(state!=null ? state.getInt("selected",GamepadLayout.LEFT_STICK) : GamepadLayout.LEFT_STICK);
        setContentView(root);
    }
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
        StringBuilder out=new StringBuilder();for(GamepadLayout.MacroStep step:macro.steps){if(out.length()>0)out.append(" + ");out.append(buttonNames(step.buttons));}return out.toString();
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
            for(String raw:chord){String t=raw.trim();int bit;
                switch(t){case "↑":case "UP":case "U":bit=1;break;case "↓":case "DOWN":case "D":bit=2;break;case "←":case "LEFT":case "L":bit=4;break;case "→":case "RIGHT":case "R":bit=8;break;
                    case "START":case "ST":bit=0x10;break;case "BACK":case "SELECT":bit=0x20;break;case "LS":case "L3":bit=0x40;break;case "RS":case "R3":bit=0x80;break;case "LB":bit=0x100;break;case "RB":bit=0x200;break;case "A":bit=0x1000;break;case "B":bit=0x2000;break;case "X":bit=0x4000;break;case "Y":bit=0x8000;break;default:return new java.util.ArrayList<>();}
                mask|=bit;
            }
            if(mask==0)return new java.util.ArrayList<>();steps.add(new GamepadLayout.MacroStep(mask,holdMs,70));
        }return steps;
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void button(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setMinHeight(0);b.setMinimumHeight(0);
        parent.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(40)));b.setOnClickListener(v->action.run());}
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);try{state.putString("draft",pad.layout().toJson().toString());}catch(Exception ignored){}
        if(pad.selection()!=null)state.putInt("selected",pad.selection().id);
    }
}
