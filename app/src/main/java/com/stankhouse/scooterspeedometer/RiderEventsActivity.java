package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Local event board. All permissions and memberships are enforced again by Supabase. */
public final class RiderEventsActivity extends Activity {
    private RiderLinkClient client;
    private LinearLayout cards;
    private EditText city;
    private TextView status;
    private Button more;
    private String mode = "public";
    private int offset, generation;
    private boolean loading;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        client = new RiderLinkClient(this);
        if (state != null) mode = state.getString("mode", "public");
        LinearLayout root = column();
        root.setBackground(UiKit.screenBackground(this));
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.addView(UiKit.header(this, "◷", "RiderLink Events", "Meetups • group rides • local gatherings"));
        Button back = button("‹ Back", () -> finish());
        root.addView(back);
        LinearLayout tabs = new LinearLayout(this);
        String[] labels = {"Local board", "Invitations", "My events"};
        String[] modes = {"public", "invited", "mine"};
        for (int i=0; i<labels.length; i++) {
            final String selected = modes[i];
            Button tab = button(labels[i], () -> { mode=selected; load(true); });
            tab.setTextSize(12);
            tab.setPadding(dp(4),0,dp(4),0);
            tabs.addView(tab, new LinearLayout.LayoutParams(0,dp(50),1));
        }
        root.addView(tabs);
        city = field("Town / city (blank shows all)",100);
        city.setText(state != null ? state.getString("city", "") : getPreferences(0).getString("city", ""));
        root.addView(city);
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Refresh / filter", () -> load(true)),new LinearLayout.LayoutParams(0,dp(50),1));
        actions.addView(button("+ Host event", this::checkHosting),new LinearLayout.LayoutParams(0,dp(50),1));
        root.addView(actions);
        status=UiKit.text(this,"",13,UiKit.CYAN,true); root.addView(status);
        cards=column();
        ScrollView scroll=new ScrollView(this); scroll.addView(cards);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        more=button("Load more", () -> load(false)); more.setVisibility(View.GONE);root.addView(more);
        setContentView(root);
        load(true);
    }

    private void load(boolean reset) {
        if (!client.signedIn()) { status.setText("Sign in to RiderLink to view events."); return; }
        if (!reset && loading) return;
        final int ticket=++generation;
        if (reset) {offset=0;cards.removeAllViews();}
        final int pageOffset=offset;
        loading=true;more.setEnabled(false);city.setVisibility("public".equals(mode)?View.VISIBLE:View.GONE);
        String filter=city.getText().toString().trim();
        getPreferences(0).edit().putString("city",filter).apply();
        status.setText("Loading events…");
        rpc("list_rider_events", object("p_mode",mode,"p_city",filter,"p_offset",pageOffset),(ok,message,raw)->{
            if (ticket!=generation) return;
            loading=false;more.setEnabled(true);
            if (!ok) {status.setText(message);return;}
            try {
                JSONArray rows=new JSONArray(raw);
                for (int i=0;i<rows.length();i++) addEvent(rows.getJSONObject(i));
                offset=pageOffset+rows.length();more.setVisibility(rows.length()==30?View.VISIBLE:View.GONE);
                String label="public".equals(mode)?"Local board":"invited".equals(mode)?"Your invitations":"Hosting & saved events";
                status.setText(label+(offset==0?" • No events yet":" • "+offset+" events"));
            } catch (Exception e) {status.setText("Unable to read events. Tap Refresh to retry.");}
        });
    }

    private void addEvent(JSONObject event) {
        LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));UiKit.card(card);
        boolean cancelled=event.optBoolean("cancelled");
        card.addView(UiKit.text(this,(cancelled?"CANCELLED • ":"")+event.optString("title"),18,cancelled?UiKit.RED:UiKit.TEXT,true));
        card.addView(UiKit.text(this,displayTime(event.optString("starts_at"))+"\n"+event.optString("city")+" • "+visibility(event),13,UiKit.CYAN,false));
        card.addView(UiKit.text(this,"Host: "+event.optString("host_name")+" • "+event.optInt("going_count")+" going • "+event.optInt("interested_count")+" interested",12,UiKit.MUTED,false));
        String response=event.optString("my_response","none");
        if (event.optBoolean("invited") || !"none".equals(response))
            card.addView(UiKit.text(this,(event.optBoolean("invited")?"Invited • ":"")+"Your RSVP: "+response,13,UiKit.AMBER,true));
        card.setOnClickListener(v -> details(event));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(6));cards.addView(card,lp);
    }

    private void details(JSONObject event) {
        boolean host=client.userId().equals(event.optString("host_id"));
        boolean closed=event.optBoolean("cancelled") || parseTime(event.optString("ends_at"))<=System.currentTimeMillis();
        LinearLayout content=column();content.setPadding(dp(16),dp(8),dp(16),dp(8));
        String info=(event.optBoolean("cancelled")?"CANCELLED\n\n":"")+visibility(event)+"\nHost: "+event.optString("host_name")+
            "\n\n"+displayTime(event.optString("starts_at"))+"\nEnds: "+displayTime(event.optString("ends_at"))+
            "\n\n"+event.optString("meeting_place")+"\n"+event.optString("city")+"\n\n"+event.optString("description")+
            "\n\n"+event.optInt("going_count")+" going • "+event.optInt("interested_count")+" interested"+
            "\nUpdated: "+displayTime(event.optString("updated_at"));
        content.addView(UiKit.text(this,info,15,UiKit.TEXT,false));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(event.optString("title")).setView(scroll).setNegativeButton("Close",null).create();
        long id=event.optLong("id");
        if (host) {
            if (!closed) {
                content.addView(button("Edit event", () -> {dialog.dismiss();edit(event);}));
                content.addView(button("Invite riders", () -> invite(id)));
                content.addView(button("Cancel event", () -> new AlertDialog.Builder(this).setTitle("Cancel this event?")
                    .setMessage("Invited and RSVP’d riders will see it marked cancelled when they refresh their events.")
                    .setNegativeButton("Keep event",null).setPositiveButton("Cancel event",(d,w)->rpc("cancel_rider_event",object("p_event",id),(ok,m,b)->{
                        if(ok){dialog.dismiss();load(true);}else toast(m);
                    })).show()));
            }
            content.addView(button("Guest list", () -> guests(id)));
        } else if (!closed) {
            for (String response:new String[]{"going","interested","declined"}) {
                Button responseButton=button(response.equals("declined")?"Can't go / decline":response.equals("going")?"Going":"Interested", () -> {});
                responseButton.setOnClickListener(v->{
                    responseButton.setEnabled(false);
                    rpc("respond_rider_event",object("p_event",id,"p_response",response),(ok,m,b)->{
                        responseButton.setEnabled(true);
                        if(ok){toast("RSVP saved");dialog.dismiss();load(true);}else toast(m);
                    });
                });
                content.addView(responseButton);
            }
        }
        dialog.show();
    }

    private void checkHosting() {
        if(!client.signedIn()){toast("Sign in to RiderLink first");return;}
        client.membership((ok,m,b)->{
            if(isFinishing()||isDestroyed())return;
            if(!ok){toast(m);return;}
            if(client.plusActive()||client.membershipAdmin())edit(null);
            else new AlertDialog.Builder(this).setTitle("Host with RiderLink+")
                .setMessage("Plus members can host public or invite-only events. Browsing, invitations and RSVPs are free.")
                .setPositiveButton("OK",null).show();
        });
    }

    private void edit(JSONObject existing) {
        LinearLayout form=column();form.setPadding(dp(16),dp(6),dp(16),dp(6));
        EditText title=field("Event title",80),description=field("What is happening?",3000),town=field("Town / city, state",100),place=field("Meeting place / full address",240);
        description.setSingleLine(false);description.setMinLines(3);
        form.addView(title);form.addView(description);form.addView(town);form.addView(place);
        town.setText(city.getText());
        Calendar start=Calendar.getInstance();start.add(Calendar.DAY_OF_YEAR,1);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
        Calendar end=(Calendar)start.clone();end.add(Calendar.HOUR_OF_DAY,2);
        Switch publicEvent=new Switch(this);publicEvent.setText("Post publicly on the local board");publicEvent.setTextColor(UiKit.TEXT);publicEvent.setChecked(false);
        if(existing!=null){
            title.setText(existing.optString("title"));description.setText(existing.optString("description"));town.setText(existing.optString("city"));place.setText(existing.optString("meeting_place"));
            start.setTimeInMillis(parseTime(existing.optString("starts_at")));end.setTimeInMillis(parseTime(existing.optString("ends_at")));
            publicEvent.setChecked("public".equals(existing.optString("visibility")));publicEvent.setEnabled(false);
        }
        Button startButton=button("",()->{}),endButton=button("",()->{});
        Runnable dates=()->{startButton.setText("Starts: "+localTime(start.getTimeInMillis()));endButton.setText("Ends: "+localTime(end.getTimeInMillis()));};dates.run();
        startButton.setOnClickListener(v->pickDate(start,dates));endButton.setOnClickListener(v->pickDate(end,dates));
        form.addView(startButton);form.addView(endButton);
        form.addView(UiKit.text(this,"Times use your device timezone: "+TimeZone.getDefault().getID(),12,UiKit.MUTED,false));
        form.addView(publicEvent);
        TextView visibilityNote=UiKit.text(this,"",13,UiKit.AMBER,false);
        Runnable privacy=()->visibilityNote.setText(publicEvent.isChecked()?"Any signed-in RiderLink rider can see this event and meeting address. You can also invite riders.":"Only you and riders you invite can see this event and meeting address.");
        publicEvent.setOnCheckedChangeListener((v,checked)->privacy.run());privacy.run();form.addView(visibilityNote);
        if(existing!=null)form.addView(UiKit.text(this,"Visibility is fixed after posting. Cancel and create a new event to change it.",12,UiKit.MUTED,false));
        TextView error=UiKit.text(this,"",13,UiKit.RED,false);form.addView(error);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Host an event":"Edit event").setView(scroll)
            .setNegativeButton("Cancel",null).setPositiveButton(existing==null?"Post event":"Save changes",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String t=title.getText().toString().trim(),d=description.getText().toString().trim(),c=town.getText().toString().trim(),p=place.getText().toString().trim();
            if(t.length()<3||c.length()<2||p.length()<3){error.setText("Add a title, town/city and meeting address.");return;}
            if(start.getTimeInMillis()<=System.currentTimeMillis()||end.getTimeInMillis()<=start.getTimeInMillis()||end.getTimeInMillis()-start.getTimeInMillis()>7L*24*60*60*1000){error.setText("Choose a future start and an end within seven days of the start.");return;}
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setEnabled(false);error.setText("Saving…");
            rpc("save_rider_event",object("p_id",existing==null?JSONObject.NULL:existing.optLong("id"),"p_revision",existing==null?0:existing.optInt("revision"),
                "p_title",t,"p_description",d,"p_city",c,"p_meeting_place",p,"p_starts_at",utc(start.getTimeInMillis()),"p_ends_at",utc(end.getTimeInMillis()),"p_visibility",publicEvent.isChecked()?"public":"invite_only"),(ok,m,b)->{
                save.setEnabled(true);
                if(ok){dialog.dismiss();mode="mine";load(true);toast("Event saved. Open it to invite riders.");}else error.setText(m);
            });
        }));dialog.show();
    }

    private void invite(long id) {
        new AlertDialog.Builder(this).setTitle("Invite a rider").setItems(new String[]{"Choose a friend","Enter exact username"},(d,w)->{
            if(w==0)client.friends((ok,m,b)->{
                if(isFinishing()||isDestroyed())return;
                if(!ok){toast(m);return;}
                try{
                    JSONArray friends=new JSONArray(b);String[] names=new String[friends.length()];
                    for(int i=0;i<names.length;i++)names[i]=friends.getJSONObject(i).getString("username");
                    if(names.length==0){toast("No friends yet. Invite by exact username.");return;}
                    new AlertDialog.Builder(this).setTitle("Choose rider").setItems(names,(a,n)->confirmInvite(id,names[n])).setNegativeButton("Cancel",null).show();
                }catch(Exception e){toast("Unable to load friends");}
            });
            else {
                EditText name=field("Exact RiderLink username",100);
                AlertDialog input=new AlertDialog.Builder(this).setTitle("Invite by username").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Review invitation",null).create();
                input.setOnShowListener(v->input.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                    String username=name.getText().toString().trim();if(username.isEmpty()){name.setError("Enter a username");return;}input.dismiss();confirmInvite(id,username);
                }));input.show();
            }
        }).show();
    }

    private void confirmInvite(long id,String name) {
        new AlertDialog.Builder(this).setTitle("Invite "+name+"?").setMessage("They will see this event and its meeting address in their Invitations tab.")
            .setNegativeButton("Cancel",null).setPositiveButton("Send invitation",(d,w)->rpc("invite_rider_to_event",object("p_event",id,"p_username",name),(ok,m,b)->toast(ok?"Invitation added to their Events inbox":m))).show();
    }
    private void guests(long id) {
        rpc("rider_event_guest_list",object("p_event",id),(ok,m,b)->{
            if(!ok){toast(m);return;}
            try {
                JSONArray rows=new JSONArray(b);StringBuilder text=new StringBuilder();
                for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);text.append(r.optString("username")).append(" — ").append(r.optString("response")).append(r.optBoolean("invited")?" (invited)":"").append('\n');}
                TextView list=UiKit.text(this,rows.length()==0?"No invitations or RSVPs yet":text.toString(),15,UiKit.TEXT,false);list.setPadding(dp(16),dp(12),dp(16),dp(12));
                ScrollView scroll=new ScrollView(this);scroll.addView(list);
                new AlertDialog.Builder(this).setTitle("Guest list").setView(scroll).setPositiveButton("Close",null).show();
            }catch(Exception e){toast("Unable to read guest list");}
        });
    }
    private void pickDate(Calendar selected,Runnable changed) {
        new DatePickerDialog(this,(d,y,m,day)->new TimePickerDialog(this,(time,h,min)->{
            selected.set(y,m,day,h,min,0);selected.set(Calendar.MILLISECOND,0);changed.run();
        },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(this)).show(),
        selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void rpc(String name,JSONObject args,RiderLinkClient.Callback callback) {
        client.eventRpc(name,args,(ok,m,b)->{if(!isFinishing()&&!isDestroyed())callback.complete(ok,m,b);});
    }
    private JSONObject object(Object... pairs) {
        JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return result;
    }
    private String visibility(JSONObject e){return "public".equals(e.optString("visibility"))?"Public":"Invite only";}
    private static String utc(long time){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(time));}
    private static long parseTime(String value){
        // PostgREST timestamptz values may have fractional seconds and an offset.
        try{String normalized=value.replaceFirst("\\.\\d+","");SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);return f.parse(normalized).getTime();}catch(Exception e){return 0;}
    }
    private String localTime(long time){return new SimpleDateFormat("EEE, MMM d • h:mm a z",Locale.getDefault()).format(new Date(time));}
    private String displayTime(String value){long time=parseTime(value);return time==0?"Time unavailable":localTime(time);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private EditText field(String hint,int length){EditText e=new EditText(this);UiKit.field(e);e.setHint(hint);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});return e;}
    private Button button(String label,Runnable action){Button b=UiKit.iconButton(this,"",label,UiKit.SURFACE_ACTIVE);b.setOnClickListener(v->action.run());return b;}
    private int dp(int value){return UiKit.dp(this,value);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("mode",mode);state.putString("city",city.getText().toString());}
    @Override protected void onDestroy(){generation++;client.shutdown();super.onDestroy();}
}
