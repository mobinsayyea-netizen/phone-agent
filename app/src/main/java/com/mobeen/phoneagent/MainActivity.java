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
    title.setText("फ़ोन एजेंट");
    title.setTextSize(26);
    title.setTextColor(Color.parseColor("#111827"));
    title.setPadding(0, 0, 0, dp(8));
    if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    root.addView(title);

    status = new TextView(this);
    status.setTextSize(18);
    status.setPadding(0, dp(4), 0, dp(12));
    root.addView(status);

    accBtn = makeButton("Accessibility सेवा चालू करें", "#475569");
    accBtn.setOnClickListener(
        v -> {
          try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
          } catch (ActivityNotFoundException e) {
            setStatus("Accessibility सेटिंग्स नहीं खुल सकीं");
          }
        });
    root.addView(accBtn, lp());

    keyBox = new EditText(this);
    keyBox.setHint("Gemini API key");
    keyBox.setTextSize(18);
    keyBox.setSingleLine(true);
    keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    keyBox.setText(prefs.getString(KEY, ""));
    root.addView(keyBox, lp());

    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    Button save = makeButton("सेव", "#047857");
    Button cancel = makeButton("कैंसिल", "#475569");
    save.setOnClickListener(v -> saveKey());
    cancel.setOnClickListener(v -> cancelKey());
    LinearLayout.LayoutParams half =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half.setMargins(0, dp(6), dp(6), dp(6));
    row.addView(save, half);
    LinearLayout.LayoutParams half2 =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half2.setMargins(dp(6), dp(6), 0, dp(6));
    row.addView(cancel, half2);
    root.addView(row);

    cmdBox = new EditText(this);
    cmdBox.setHint("आदेश लिखें");
    cmdBox.setTextSize(18);
    cmdBox.setMinLines(3);
    cmdBox.setGravity(Gravity.TOP | Gravity.START);
    cmdBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    LinearLayout.LayoutParams cmdLp = lp();
    cmdLp.topMargin = dp(20);
    root.addView(cmdBox, cmdLp);

    Button send = makeButton("भेजें", "#1D4ED8");
    send.setOnClickListener(v -> runTask(cmdBox.getText().toString().trim()));
    root.addView(send, lp());

    Button voice = makeButton("आवाज़ से आदेश दें", "#1D4ED8");
    voice.setOnClickListener(v -> startVoice());
    root.addView(voice, lp());

    Button close = makeButton("आपातकालीन बंद", "#B91C1C");
    close.setOnClickListener(v -> emergencyClose());
    LinearLayout.LayoutParams closeLp = lp();
    closeLp.topMargin = dp(24);
    root.addView(close, closeLp);

    setContentView(scroll);
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

  private void refreshState() {
    boolean on = AgentService.instance != null;
    accBtn.setVisibility(on ? View.GONE : View.VISIBLE);
    if (!on) {
      status.setText("पहले Accessibility सेवा चालू करें");
    } else if (AgentService.running) {
      status.setText(AgentService.lastStatus);
    } else if (AgentService.lastStatus != null && !AgentService.lastStatus.isEmpty()) {
      status.setText(AgentService.lastStatus);
    } else {
      status.setText("तैयार है। आदेश लिखें या बोलें।");
    }
  }

  private void saveKey() {
    String k = keyBox.getText().toString().trim();
    if (k.isEmpty()) {
      announce("API key खाली है");
      return;
    }
    prefs.edit().putString(KEY, k).apply();
    announce("API key सफलतापूर्वक सेव हुई");
  }

  private void cancelKey() {
    keyBox.setText(prefs.getString(KEY, ""));
    announce("रद्द किया गया");
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
    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए");
    try {
      startActivityForResult(i, REQ_VOICE);
    } catch (ActivityNotFoundException e) {
      setStatus("इस फ़ोन में आवाज़ पहचान उपलब्ध नहीं है");
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
      setStatus("पहले आदेश लिखें या बोलें");
      return;
    }
    String key = prefs.getString(KEY, "").trim();
    if (key.isEmpty()) {
      setStatus("पहले Gemini API key डालकर सेव करें");
      return;
    }
    AgentService svc = AgentService.instance;
    if (svc == null) {
      refreshState();
      return;
    }
    if (!svc.startTask(task, key)) {
      setStatus("एक काम पहले से चल रहा है");
      return;
    }
    status.setText("आदेश मिल गया, काम चल रहा है");
    moveTaskToBack(true);
  }

  private void emergencyClose() {
    AgentService svc = AgentService.instance;
    if (svc != null) svc.stopTask("आपातकालीन रोक। काम बंद कर दिया गया।");
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
