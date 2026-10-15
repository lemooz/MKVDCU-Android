package org.mkvdcu.android;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Normalized layout shared atomically by launcher and game processes. */
final class GamepadLayout {
    static final int BUTTON=0, STICK=1, DPAD=2, TRIGGER=3, MACRO=4;
    static final int LEFT_STICK=100, RIGHT_STICK=101, DIRECTIONAL=102, LEFT_TRIGGER=103, RIGHT_TRIGGER=104;
    static final int FIRST_MACRO_CONTROL=1000;
    static final class MacroStep {
        final int buttons,holdMs,gapMs,leftTrigger,rightTrigger;
        MacroStep(int buttons,int holdMs,int gapMs){this(buttons,holdMs,gapMs,0,0);}
        MacroStep(int buttons,int holdMs,int gapMs,int leftTrigger,int rightTrigger){
            this.buttons=buttons;this.holdMs=holdMs;this.gapMs=gapMs;
            this.leftTrigger=Math.max(0,Math.min(255,leftTrigger));this.rightTrigger=Math.max(0,Math.min(255,rightTrigger));
        }
    }
    static final class Macro {
        final int id;
        String name;
        final List<MacroStep> steps=new ArrayList<>();
        Macro(int id,String name){this.id=id;this.name=name;}
    }
    static final class Control {
        final int id, kind, bit, color, macroId;
        String label, name;
        float x,y,size=1;
        boolean visible=true;
        Control(int id,int kind,int bit,String label,String name,float x,float y,int color) {
            this(id,kind,bit,label,name,x,y,color,-1);
        }
        Control(int id,int kind,int bit,String label,String name,float x,float y,int color,int macroId) {
            this.id=id;this.kind=kind;this.bit=bit;this.label=label;this.name=name;this.x=x;this.y=y;this.color=color;this.macroId=macroId;
        }
        boolean round(){return kind==STICK || kind==DPAD || kind==MACRO || bit>=0x1000 || bit==0x0400;}
        float radius(float h){return h*(kind==STICK ? .102f : kind==DPAD ? .090f : .060f)*size;}
        float halfWidth(float h){return round() ? radius(h) : h*.063f*size;}
        float halfHeight(float h){return round() ? radius(h) : h*.030f*size;}
    }
    final List<Control> controls=new ArrayList<>();
    final List<Macro> macros=new ArrayList<>();
    float opacity=.78f,deadzone=.12f;
    boolean mirrorLeftDpad=false;
    static GamepadLayout defaults(){
        GamepadLayout l=new GamepadLayout();
        l.add(LEFT_STICK,STICK,0,"L","Analógico esquerdo",.115f,.69f,0xFF94A3B8);
        l.add(DIRECTIONAL,DPAD,0,"+","Direcional",.285f,.81f,0xFF94A3B8);
        l.add(RIGHT_STICK,STICK,0,"R","Analógico direito",.695f,.81f,0xFF94A3B8);
        l.add(LEFT_TRIGGER,TRIGGER,0,"LT","Gatilho esquerdo",.090f,.37f,0xFFB4C0CE);
        l.add(0x0100,BUTTON,0x0100,"LB","Botão superior esquerdo",.215f,.37f,0xFFB4C0CE);
        l.add(0x0200,BUTTON,0x0200,"RB","Botão superior direito",.785f,.37f,0xFFB4C0CE);
        l.add(RIGHT_TRIGGER,TRIGGER,0,"RT","Gatilho direito",.925f,.37f,0xFFB4C0CE);
        l.add(0x0020,BUTTON,0x0020,"BACK","Back",.425f,.91f,0xFFB4C0CE);
        l.add(0x0400,BUTTON,0x0400,"X","Guia Xbox",.500f,.91f,0xFF78C344);
        l.add(0x0010,BUTTON,0x0010,"START","Start",.575f,.91f,0xFFB4C0CE);
        l.add(0x0040,BUTTON,0x0040,"LS","Clique LS (L3)",.405f,.77f,0xFFB4C0CE);
        l.add(0x0080,BUTTON,0x0080,"RS","Clique RS (R3)",.575f,.77f,0xFFB4C0CE);
        l.add(0x1000,BUTTON,0x1000,"A","A",.895f,.74f,0xFF65BD43);
        l.add(0x2000,BUTTON,0x2000,"B","B",.955f,.61f,0xFFE24848);
        l.add(0x4000,BUTTON,0x4000,"X","X",.835f,.61f,0xFF398ADB);
        l.add(0x8000,BUTTON,0x8000,"Y","Y",.895f,.48f,0xFFF6C943);
        return l;
    }
    private void add(int id,int kind,int bit,String label,String name,float x,float y,int color){controls.add(new Control(id,kind,bit,label,name,x,y,color));}
    Control find(int id){for(Control c:controls)if(c.id==id)return c;return null;}
    Macro findMacro(int id){for(Macro m:macros)if(m.id==id)return m;return null;}
    Control addMacro(String name,List<MacroStep> steps){
        int macroId=macros.isEmpty()?1:macros.get(macros.size()-1).id+1;
        while(find(FIRST_MACRO_CONTROL+macroId)!=null){macroId++;}
        return addMacro(macroId,name,steps,.5f,.55f);
    }
    void updateMacro(int id,String name,List<MacroStep> steps){
        Macro macro=findMacro(id);if(macro==null)return;macro.name=name;macro.steps.clear();macro.steps.addAll(steps);
        Control control=find(FIRST_MACRO_CONTROL+id);if(control!=null)control.name=name;
    }
    void removeMacro(int id){macros.removeIf(m->m.id==id);controls.removeIf(c->c.kind==MACRO&&c.macroId==id);}
    private Control addMacro(int macroId,String name,List<MacroStep> steps,float x,float y){
        Macro macro=new Macro(macroId,name);macro.steps.addAll(steps);macros.add(macro);
        Control control=new Control(FIRST_MACRO_CONTROL+macroId,MACRO,0,"M"+macroId,name,x,y,0xFF7C5CDB,macroId);controls.add(control);return control;
    }
    static float clamp(float v,float min,float max,float fallback){return Float.isFinite(v) ? Math.max(min,Math.min(max,v)) : fallback;}
    static float unit(float w,float h){return Math.min(h,w/2.16f);}
    void constrain(Control c,float w,float h){
        if(w<=0 || h<=0)return;
        float u=unit(w,h);
        float dx=Math.min(.48f,c.halfWidth(u)/w+.008f),dy=Math.min(.48f,c.halfHeight(u)/h+.008f);
        c.x=clamp(c.x,dx,1-dx,.5f);c.y=clamp(c.y,dy,1-dy,.6f);
    }
    JSONObject toJson() throws org.json.JSONException {
        JSONObject j=new JSONObject().put("version",3).put("opacity",opacity).put("deadzone",deadzone).put("mirrorLeftDpad",mirrorLeftDpad);
        JSONArray a=new JSONArray();
        for(Control c:controls)a.put(new JSONObject().put("id",c.id).put("x",c.x).put("y",c.y).put("size",c.size).put("visible",c.visible));
        JSONArray macrosJson=new JSONArray();
        for(Macro m:macros){JSONArray steps=new JSONArray();for(MacroStep s:m.steps)steps.put(new JSONObject().put("buttons",s.buttons).put("holdMs",s.holdMs).put("gapMs",s.gapMs).put("leftTrigger",s.leftTrigger).put("rightTrigger",s.rightTrigger));
            macrosJson.put(new JSONObject().put("id",m.id).put("name",m.name).put("steps",steps));}
        return j.put("controls",a).put("macros",macrosJson);
    }
    static GamepadLayout fromJson(JSONObject j){
        GamepadLayout l=defaults();int version=j.optInt("version");if(version<1 || version>3)return l;
        l.opacity=clamp((float)j.optDouble("opacity",.78),.20f,1,.78f);
        l.deadzone=clamp((float)j.optDouble("deadzone",.12),0,.35f,.12f);
        l.mirrorLeftDpad=j.optBoolean("mirrorLeftDpad",false);
        if(version>=2){
            JSONArray ma=j.optJSONArray("macros");
            if(ma!=null)for(int i=0;i<Math.min(ma.length(),32);i++){
                JSONObject item=ma.optJSONObject(i);if(item==null)continue;
                int id=item.optInt("id",-1);if(id<1 || id>10000 || l.findMacro(id)!=null)continue;
                JSONArray sj=item.optJSONArray("steps");if(sj==null || sj.length()==0 || sj.length()>32)continue;
                List<MacroStep> steps=new ArrayList<>();
                for(int k=0;k<sj.length();k++){
                    JSONObject step=sj.optJSONObject(k);if(step==null)continue;
                    int buttons=step.optInt("buttons",0)&0xF0FF;
                    int leftTrigger=version>=3?Math.max(0,Math.min(255,step.optInt("leftTrigger",0))):0;
                    int rightTrigger=version>=3?Math.max(0,Math.min(255,step.optInt("rightTrigger",0))):0;
                    if(buttons==0 && leftTrigger==0 && rightTrigger==0)continue;
                    int hold=step.optInt("holdMs",90),gap=step.optInt("gapMs",60);
                    steps.add(new MacroStep(buttons,Math.max(20,Math.min(500,hold)),Math.max(0,Math.min(500,gap)),leftTrigger,rightTrigger));
                }
                if(steps.isEmpty())continue;
                JSONObject position=null;JSONArray saved=j.optJSONArray("controls");
                if(saved!=null)for(int k=0;k<saved.length();k++){JSONObject c=saved.optJSONObject(k);if(c!=null && c.optInt("id")==FIRST_MACRO_CONTROL+id){position=c;break;}}
                float x=position==null?.5f:clamp((float)position.optDouble("x",.5),0,1,.5f);
                float y=position==null?.55f:clamp((float)position.optDouble("y",.55),0,1,.55f);
                Control control=l.addMacro(id,item.optString("name","Macro "+id),steps,x,y);
                if(position!=null){control.size=clamp((float)position.optDouble("size",1),.65f,1.65f,1);control.visible=position.optBoolean("visible",true);}
            }
        }
        JSONArray a=j.optJSONArray("controls");
        if(a!=null)for(int i=0;i<Math.min(a.length(),64);i++){
            JSONObject v=a.optJSONObject(i);if(v==null)continue;Control c=l.find(v.optInt("id",-1));if(c==null)continue;
            c.x=clamp((float)v.optDouble("x",c.x),0,1,c.x);c.y=clamp((float)v.optDouble("y",c.y),0,1,c.y);
            c.size=clamp((float)v.optDouble("size",1),.65f,1.65f,1);c.visible=v.optBoolean("visible",true);
        }
        return l;
    }
    private static AtomicFile storage(Context context){return new AtomicFile(new File(context.getFilesDir(),"touch-gamepad-v1.json"));}
    static GamepadLayout load(Context context){
        try(FileInputStream in=storage(context).openRead()){
            byte[] bytes=new byte[32769];int used=0,n;
            while(used<bytes.length && (n=in.read(bytes,used,bytes.length-used))>0)used+=n;
            if(used>32768)return defaults();return fromJson(new JSONObject(new String(bytes,0,used,StandardCharsets.UTF_8)));
        }catch(Exception ignored){return defaults();}
    }
    void save(Context context) throws Exception {
        AtomicFile file=storage(context);FileOutputStream out=null;
        try{byte[] bytes=toJson().toString().getBytes(StandardCharsets.UTF_8);out=file.startWrite();out.write(bytes);file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
    }
}
