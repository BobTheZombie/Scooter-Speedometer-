package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class RiderLinkSocialActivity extends Activity {
    private RiderLinkClient client; private LinearLayout content; private int cyan=Color.rgb(0,229,255);
    @Override protected void onCreate(Bundle state){super.onCreate(state);client=new RiderLinkClient(this);build();client.refreshSession((ok,m,b)->{if(!ok){toast("Session expired. Sign in again.");finish();}else showRiders();});}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(4,10,14));
        root.setPadding(0,bar("status_bar"),0,bar("navigation_bar"));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(4),dp(10),dp(4));
        Button back=button("‹ MAP",Color.rgb(18,50,60));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(85),dp(46)));
        TextView title=text("RIDERLINK SOCIAL",22,cyan);header.addView(title,new LinearLayout.LayoutParams(0,dp(54),1));root.addView(header);
        LinearLayout tabs=new LinearLayout(this);String[] names={"RIDERS","FRIENDS","CLUBS"};for(int i=0;i<3;i++){Button b=button(names[i],Color.rgb(0,75,92));final int tab=i;b.setOnClickListener(v->{if(tab==0)showRiders();else if(tab==1)showFriends();else showClubs();});tabs.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}root.addView(tabs);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(10),dp(12),dp(18));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void loading(String label){content.removeAllViews();content.addView(text(label,16,Color.rgb(170,190,198)));}
    private void showRiders(){loading("Finding RiderLink members…");client.discoverRiders((ok,m,body)->{if(!ok){error(m);return;}content.removeAllViews();content.addView(heading("DISCOVER RIDERS"));try{JSONArray list=new JSONArray(body);if(list.length()==0)empty("No other riders yet.");for(int i=0;i<list.length();i++){JSONObject r=list.getJSONObject(i);String id=r.getString("id");row(r.optString("username","Rider"),r.optString("scooter","Scooter"),"ADD",v->client.requestFriend(id,(done,msg,data)->toast(done?"Friend request sent":msg)));}}catch(Exception e){error(e.getMessage());}});}
    private void showFriends(){loading("Loading friends…");client.friendRequests((ok,m,requests)->{if(!ok){error(m);return;}client.friends((friendsOk,friendsMsg,friendsBody)->{content.removeAllViews();content.addView(heading("FRIEND REQUESTS"));try{JSONArray list=new JSONArray(requests);if(list.length()==0)empty("No pending requests.");for(int i=0;i<list.length();i++){JSONObject request=list.getJSONObject(i);JSONObject rider=request.optJSONObject("requester");long id=request.getLong("id");row(rider==null?"Rider":rider.optString("username"),rider==null?"":rider.optString("scooter"),"ACCEPT",v->client.acceptFriend(id,(done,msg,data)->{toast(done?"Friend added":msg);showFriends();}));}}catch(Exception e){error(e.getMessage());}content.addView(heading("FRIENDS"));if(!friendsOk){empty(friendsMsg);return;}try{JSONArray list=new JSONArray(friendsBody);if(list.length()==0)empty("No friends yet.");for(int i=0;i<list.length();i++){JSONObject rider=list.getJSONObject(i);String id=rider.getString("id"),name=rider.optString("username","Rider");row(name,rider.optString("scooter","Scooter"),"CHAT",v->openDirectChat(id,name));}}catch(Exception e){error(e.getMessage());}});});}
    private void showClubs(){loading("Loading clubs…");content.removeAllViews();LinearLayout actions=new LinearLayout(this);Button create=button("CREATE CLUB",Color.rgb(135,36,13)),join=button("JOIN CODE",Color.rgb(0,89,106));actions.addView(create,new LinearLayout.LayoutParams(0,dp(50),1));actions.addView(join,new LinearLayout.LayoutParams(0,dp(50),1));content.addView(actions);create.setOnClickListener(v->clubNameDialog());join.setOnClickListener(v->joinDialog());content.addView(heading("MY CLUBS"));client.myClubs((ok,m,body)->{if(!ok){empty(m);return;}try{JSONArray list=new JSONArray(body);if(list.length()==0)empty("Create a club or join with an invite code.");for(int i=0;i<list.length();i++){JSONObject club=list.getJSONObject(i);long id=club.getLong("id");String name=club.optString("name","Club"),code=club.optString("invite_code","");row(name,"Invite code: "+code,"OPEN",v->openClubChat(id,name,code));}}catch(Exception e){error(e.getMessage());}});}
    private void clubNameDialog(){EditText input=dialogInput("Club name");new AlertDialog.Builder(this).setTitle("Create RiderLink club").setView(input).setPositiveButton("CREATE",(d,w)->{String name=input.getText().toString().trim();if(name.length()<3){toast("Club name is too short");return;}client.createClub(name,(ok,m,b)->{toast(ok?"Club created":m);if(ok)showClubs();});}).setNegativeButton("Cancel",null).show();}
    private void joinDialog(){EditText input=dialogInput("Invite code");new AlertDialog.Builder(this).setTitle("Join a club").setView(input).setPositiveButton("JOIN",(d,w)->client.joinClub(input.getText().toString().trim(),(ok,m,b)->{toast(ok?"Joined club":m);if(ok)showClubs();})).setNegativeButton("Cancel",null).show();}
    private void openDirectChat(String riderId,String name){openChat(name,(callback)->client.directMessages(riderId,callback),(message,callback)->client.sendDirect(riderId,message,callback));}
    private void openClubChat(long clubId,String name,String code){openChat(name+"  •  "+code,(callback)->client.clubMessages(clubId,callback),(message,callback)->client.sendClub(clubId,message,callback));}
    private interface Loader{void load(RiderLinkClient.Callback c);}private interface Sender{void send(String m,RiderLinkClient.Callback c);}
    private void openChat(String title,Loader loader,Sender sender){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(8),dp(14),dp(8));panel.setBackgroundColor(Color.rgb(7,13,17));
        ScrollView scroll=new ScrollView(this);TextView messages=text("Loading…",14,Color.WHITE);messages.setGravity(Gravity.LEFT);scroll.addView(messages);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(330)));
        LinearLayout compose=new LinearLayout(this);EditText input=dialogInput("Message");Button send=button("SEND",Color.rgb(0,105,125));compose.addView(input,new LinearLayout.LayoutParams(0,dp(54),1));compose.addView(send,new LinearLayout.LayoutParams(dp(88),dp(54)));panel.addView(compose);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(panel).setNegativeButton("Close",null).create();
        Runnable reload=()->loader.load((ok,m,body)->{if(!ok){messages.setText(m);return;}StringBuilder out=new StringBuilder();try{JSONArray list=new JSONArray(body);for(int i=0;i<list.length();i++){JSONObject msg=list.getJSONObject(i),profile=msg.optJSONObject("sender");out.append(profile==null?"Rider":profile.optString("username","Rider")).append(":  ").append(msg.optString("body")).append("\n\n");}messages.setText(out.length()==0?"No messages yet. Start the conversation.":out.toString());scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}catch(Exception e){messages.setText(e.getMessage());}});
        send.setOnClickListener(v->{String value=input.getText().toString().trim();if(value.isEmpty())return;sender.send(value,(ok,m,b)->{if(ok){input.setText("");reload.run();}else toast(m);});});dialog.setOnShowListener(d->reload.run());dialog.show();
    }
    private void row(String name,String detail,String action,View.OnClickListener listener){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(8),dp(4),dp(8));row.setBackgroundColor(Color.rgb(10,25,31));TextView info=text(name+"\n"+detail,16,Color.WHITE);info.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.addView(info,new LinearLayout.LayoutParams(0,dp(66),1));Button b=button(action,Color.rgb(0,91,108));b.setOnClickListener(listener);row.addView(b,new LinearLayout.LayoutParams(dp(88),dp(48)));content.addView(row);View gap=new View(this);content.addView(gap,new LinearLayout.LayoutParams(1,dp(5)));}
    private TextView heading(String value){TextView v=text(value,12,cyan);v.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);return v;}private void empty(String value){content.addView(text(value,14,Color.rgb(140,165,175)));}private void error(String value){content.removeAllViews();content.addView(text(value,15,Color.rgb(255,110,80)));}
    private EditText dialogInput(String hint){EditText v=new EditText(this);v.setHint(hint);v.setSingleLine(true);v.setTextColor(Color.WHITE);v.setHintTextColor(Color.rgb(130,150,160));v.setBackgroundColor(Color.rgb(18,30,35));v.setPadding(dp(12),0,dp(12),0);return v;}
    private Button button(String label,int color){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setBackgroundColor(color);return b;}private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER);v.setPadding(dp(10),dp(8),dp(10),dp(8));return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}private int bar(String name){int id=getResources().getIdentifier(name,"dimen","android");return id>0?getResources().getDimensionPixelSize(id):0;}private void toast(String v){Toast.makeText(this,v,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(client!=null)client.shutdown();super.onDestroy();}
}
