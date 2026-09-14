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
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debounced, location-biased destination suggestions backed by Photon and Census. */
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
    private final DestinationStore destinationStore;

    AddressAutocompleteDialog(Activity activity, Location location, Listener listener) {
        this.activity = activity; this.location = location; this.listener = listener;destinationStore=new DestinationStore(activity);
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
        results.addAll(destinationStore.display());adapter.notifyDataSetChanged();status.setText(results.isEmpty()?"Start typing to search nearby places":"Recent destinations • hold one to favorite");
        dialog = new AlertDialog.Builder(activity).setTitle("Where are we going?").setView(panel)
                .setNegativeButton("Cancel", null).setNeutralButton("Report missing",null).setPositiveButton("Choose navigation app", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit(input.getText().toString()));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->reportMissing());
            input.requestFocus(); dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.setOnDismissListener(ignored -> { generation++; main.removeCallbacks(searchTask); network.shutdownNow(); });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                generation++; main.removeCallbacks(searchTask); results.clear(); adapter.notifyDataSetChanged();
                String q=s.toString().trim();
                if(q.length()<3){if(q.isEmpty()){results.addAll(destinationStore.display());adapter.notifyDataSetChanged();}status.setText(q.isEmpty()?"Recent destinations • hold one to favorite":"Type at least 3 characters");return;}
                status.setText("Searching…"); main.postDelayed(searchTask,700);
            }
            public void afterTextChanged(Editable s) { }
        });
        list.setOnItemClickListener((parent,view,position,id) -> submit(results.get(position)));
        list.setOnItemLongClickListener((parent,view,position,id)->{String value=DestinationStore.clean(results.get(position).replace("  • OFFLINE",""));boolean added=destinationStore.toggleFavorite(value);Toast.makeText(activity,added?"Added to favorites":"Removed from favorites",Toast.LENGTH_SHORT).show();results.clear();results.addAll(destinationStore.display());adapter.notifyDataSetChanged();return true;});
        dialog.show();
    }

    private void startSearch() {
        final String query=input.getText().toString().trim(); final int requestGeneration=generation;
        if(query.length()<3||network.isShutdown())return;
        network.execute(() -> {
            try {
                List<String> found=GeocodingService.suggestions(activity,query,location);
                main.post(() -> applyResults(requestGeneration,found));
            } catch(Exception error) { main.post(() -> {if(requestGeneration==generation)status.setText("Suggestions unavailable — you can still use the typed address");}); }
        });
    }

    private void applyResults(int requestGeneration,List<String> found){if(requestGeneration!=generation||dialog==null||!dialog.isShowing())return;results.clear();results.addAll(found);adapter.notifyDataSetChanged();status.setText(found.isEmpty()?"No exact matches — try adding a city or ZIP":"Tap a result, or keep typing");}
    private void submit(String value){String destination=DestinationStore.clean(value.replace("  • OFFLINE","")).trim();if(destination.isEmpty())return;destinationStore.used(destination);InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);dialog.dismiss();listener.onDestination(destination);}
    private void reportMissing(){if(location==null){Toast.makeText(activity,"GPS location is needed to place an OSM note",Toast.LENGTH_LONG).show();return;}String text=input.getText().toString().trim();String url=String.format(java.util.Locale.US,"https://www.openstreetmap.org/note/new?lat=%.7f&lon=%.7f",location.getLatitude(),location.getLongitude());if(!text.isEmpty())url+="&text="+Uri.encode("Missing or incorrect address: "+text);activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
