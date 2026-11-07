package com.mobeen.phoneagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
  private static final String PREFS = "agent";
  private static final String KEY = "gemini_key";
  private static final int REQ_VOICE = 7;

  private SharedPreferences prefs;
  private EditText keyBox;
  private LinearLayout keyRow;
  private Button cancelBtn;
  private Button changeBtn;
  private EditText cmdBox;
  private TextView status;
  private Button accBtn;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root);

    TextView title = new TextView(this);
    title.setText("Phone Agent");
    title.setTextSize(26);
    title.setTextColor(Color.parseColor("#111827"));
    title.setPadding(0, 0, 0, dp(8));
    if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    root.addView(title);

    status = new TextView(this);
    status.setTextSize(18);
    status.setPadding(0, dp(4), 0, dp(12));
    root.addView(status);

    accBtn = makeButton("Turn on Accessibility service", "#475569");
    accBtn.setOnClickListener(
        v -> {
          try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
          } catch (ActivityNotFoundException e) {
            setStatus("Could not open Accessibility settings");
          }
        });
    root.addView(accBtn, lp());

    // API key area: shown only until a key is saved. Afterwards only "Change API key" remains.
    keyBox = new EditText(this);
    keyBox.setHint("Gemini API key");
    keyBox.setTextSize(18);
    keyBox.setSingleLine(true);
    keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    root.addView(keyBox, lp());

    keyRow = new LinearLayout(this);
    keyRow.setOrientation(LinearLayout.HORIZONTAL);
    Button save = makeButton("Save", "#047857");
    cancelBtn = makeButton("Cancel", "#475569");
    save.setOnClickListener(v -> saveKey());
    cancelBtn.setOnClickListener(v -> cancelKey());
    LinearLayout.LayoutParams half =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half.setMargins(0, dp(6), dp(6), dp(6));
    keyRow.addView(save, half);
    LinearLayout.LayoutParams half2 =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half2.setMargins(dp(6), dp(6), 0, dp(6));
    keyRow.addView(cancelBtn, half2);
    root.addView(keyRow);

    changeBtn = makeButton("Change API key", "#475569");
    changeBtn.setOnClickListener(v -> showKeyEditor());
    root.addView(changeBtn, lp());

    cmdBox = new EditText(this);
    cmdBox.setHint("Type your command");
    cmdBox.setTextSize(18);
    cmdBox.setMinLines(3);
    cmdBox.setGravity(Gravity.TOP | Gravity.START);
    cmdBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    LinearLayout.LayoutParams cmdLp = lp();
    cmdLp.topMargin = dp(20);
    root.addView(cmdBox, cmdLp);

    Button send = makeButton("Send", "#1D4ED8");
    send.setOnClickListener(v -> runTask(cmdBox.getText().toString().trim()));
    root.addView(send, lp());

    Button voice = makeButton("Speak command", "#1D4ED8");
    voice.setOnClickListener(v -> startVoice());
    root.addView(voice, lp());

    Button close = makeButton("Emergency close", "#B91C1C");
    close.setOnClickListener(v -> emergencyClose());
    LinearLayout.LayoutParams closeLp = lp();
    closeLp.topMargin = dp(24);
    root.addView(close, closeLp);

    setContentView(scroll);

    if (savedKey().isEmpty()) showKeyEditor();
    else hideKeyEditor();
  }

  @Override
  protected void onResume() {
    super.onResume();
    AgentService.listener = s -> runOnUiThread(() -> status.setText(s));
    refreshState();
  }

  @Override
  protected void onPause() {
    super.onPause();
    AgentService.listener = null;
  }

  private String savedKey() {
    return prefs.getString(KEY, "").trim();
  }

  private void showKeyEditor() {
    keyBox.setText("");
    keyBox.setHint(savedKey().isEmpty() ? "Gemini API key" : "Enter new Gemini API key");
    keyBox.setVisibility(View.VISIBLE);
    keyRow.setVisibility(View.VISIBLE);
    cancelBtn.setVisibility(savedKey().isEmpty() ? View.GONE : View.VISIBLE);
    changeBtn.setVisibility(View.GONE);
  }

  private void hideKeyEditor() {
    keyBox.setText("");
    keyBox.setVisibility(View.GONE);
    keyRow.setVisibility(View.GONE);
    changeBtn.setVisibility(View.VISIBLE);
  }

  private void refreshState() {
    boolean on = AgentService.instance != null;
    accBtn.setVisibility(on ? View.GONE : View.VISIBLE);
    if (!on) {
      status.setText("Turn on the Accessibility service first");
    } else if (AgentService.lastStatus != null && !AgentService.lastStatus.isEmpty()) {
      status.setText(AgentService.lastStatus);
    } else {
      status.setText("Ready. Type or speak a command.");
    }
  }

  private void saveKey() {
    String k = keyBox.getText().toString().trim();
    if (k.isEmpty()) {
      announce("The API key box is empty");
      return;
    }
    prefs.edit().putString(KEY, k).apply();
    hideKeyEditor();
    announce("API key saved successfully");
  }

  private void cancelKey() {
    hideKeyEditor();
    announce("Cancelled");
  }

  private void announce(String msg) {
    status.setText(msg);
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    status.announceForAccessibility(msg);
  }

  private void setStatus(String msg) {
    status.setText(msg);
    status.announceForAccessibility(msg);
  }

  private void startVoice() {
    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    i.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command");
    try {
      startActivityForResult(i, REQ_VOICE);
    } catch (ActivityNotFoundException e) {
      setStatus("Speech recognition is not available on this phone");
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
      ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
      if (r != null && !r.isEmpty()) {
        String text = r.get(0).trim();
        cmdBox.setText(text);
        runTask(text);
      }
    }
  }

  private void runTask(String task) {
    if (task.isEmpty()) {
      setStatus("Type or speak a command first");
      return;
    }
    String key = savedKey();
    if (key.isEmpty()) {
      showKeyEditor();
      setStatus("Enter your Gemini API key and tap Save first");
      return;
    }
    AgentService svc = AgentService.instance;
    if (svc == null) {
      refreshState();
      return;
    }
    if (!svc.startTask(task, key)) {
      setStatus("A task is already running");
      return;
    }
    status.setText("Command received, working on it");
    moveTaskToBack(true);
  }

  private void emergencyClose() {
    AgentService svc = AgentService.instance;
    if (svc != null) svc.stopTask("Emergency stop. The task was stopped.");
    finishAndRemoveTask();
  }

  private Button makeButton(String text, String color) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(18);
    b.setTextColor(Color.WHITE);
    b.setBackgroundColor(Color.parseColor(color));
    b.setMinHeight(dp(56));
    b.setAllCaps(false);
    return b;
  }

  private LinearLayout.LayoutParams lp() {
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    p.setMargins(0, dp(6), 0, dp(6));
    return p;
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density);
  }
}
