package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debounced, location-biased destination suggestions backed by OpenStreetMap Nominatim. */
final class AddressAutocompleteDialog {
    interface Listener { void onDestination(String destination); }

    private final Activity activity;
    private final Location location;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<String> results = new ArrayList<>();
    private final Runnable searchTask = this::startSearch;
    private ArrayAdapter<String> adapter;
    private EditText input;
    private TextView status;
    private AlertDialog dialog;
    private int generation;
    private long lastRequestAt;

    AddressAutocompleteDialog(Activity activity, Location location, Listener listener) {
        this.activity = activity; this.location = location; this.listener = listener;
    }

    void show() {
        int pad = dp(18);
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(4), pad, 0);
        input = new EditText(activity); input.setHint("Address, business, or destination");
        input.setSingleLine(true); input.setTextColor(Color.WHITE); input.setHintTextColor(0xFF91A4AD);
        panel.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
        status = new TextView(activity); status.setText("Start typing to search nearby places");
        status.setTextColor(0xFF91A4AD); status.setTextSize(13); status.setPadding(dp(4), dp(8), dp(4), dp(8));
        panel.addView(status);
        ListView list = new ListView(activity); list.setDividerHeight(1); list.setBackgroundColor(0xFF101820);
        adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, results) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view=(TextView)super.getView(position,convertView,parent); view.setTextColor(Color.WHITE);
                view.setTextSize(15); view.setPadding(dp(14),dp(12),dp(10),dp(12)); return view;
            }
        };
        list.setAdapter(adapter); panel.addView(list, new LinearLayout.LayoutParams(-1, dp(250)));
        dialog = new AlertDialog.Builder(activity).setTitle("Where are we going?").setView(panel)
                .setNegativeButton("Cancel", null).setPositiveButton("Choose navigation app", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(input.getText().toString()));
            input.requestFocus(); dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.setOnDismissListener(ignored -> { generation++; main.removeCallbacks(searchTask); network.shutdownNow(); });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                generation++; main.removeCallbacks(searchTask); results.clear(); adapter.notifyDataSetChanged();
                String q=s.toString().trim();
                if(q.length()<3){status.setText(q.isEmpty()?"Start typing to search nearby places":"Type at least 3 characters");return;}
                status.setText("Searching…"); main.postDelayed(searchTask,700);
            }
            public void afterTextChanged(Editable s) { }
        });
        list.setOnItemClickListener((parent,view,position,id) -> submit(results.get(position)));
        dialog.show();
    }

    private void startSearch() {
        final String query=input.getText().toString().trim(); final int requestGeneration=generation;
        if(query.length()<3||network.isShutdown())return;
        network.execute(() -> {
            try {
                long wait=Math.max(0,1050-(System.currentTimeMillis()-lastRequestAt)); if(wait>0)Thread.sleep(wait);
                lastRequestAt=System.currentTimeMillis();
                StringBuilder endpoint=new StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=6&q=")
                        .append(android.net.Uri.encode(query));
                if(location!=null){double lat=location.getLatitude(),lon=location.getLongitude();endpoint.append(String.format(Locale.US,"&viewbox=%.5f,%.5f,%.5f,%.5f&bounded=0",lon-.7,lat+.5,lon+.7,lat-.5));}
                HttpURLConnection c=(HttpURLConnection)new URL(endpoint.toString()).openConnection();
                c.setConnectTimeout(8000);c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent","Scooter-Speedometer/7.2 (github.com/BobTheZombie/Scooter-Speedometer-)");
                BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder body=new StringBuilder();String line;
                while((line=reader.readLine())!=null)body.append(line);reader.close();c.disconnect();
                JSONArray json=new JSONArray(body.toString());List<String> found=new ArrayList<>();
                for(int i=0;i<json.length();i++){JSONObject item=json.getJSONObject(i);String label=item.optString("display_name","").trim();if(!label.isEmpty()&&!found.contains(label))found.add(label);}
                main.post(() -> applyResults(requestGeneration,found));
            } catch(Exception error) { main.post(() -> {if(requestGeneration==generation)status.setText("Suggestions unavailable — you can still use the typed address");}); }
        });
    }

    private void applyResults(int requestGeneration,List<String> found){if(requestGeneration!=generation||dialog==null||!dialog.isShowing())return;results.clear();results.addAll(found);adapter.notifyDataSetChanged();status.setText(found.isEmpty()?"No exact matches — try adding a city or ZIP":"Tap a result, or keep typing");}
    private void submit(String value){String destination=value.trim();if(destination.isEmpty())return;InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);dialog.dismiss();listener.onDestination(destination);}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
