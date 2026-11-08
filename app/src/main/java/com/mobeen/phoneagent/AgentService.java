package com.mobeen.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phone agent. Loop: read screen -> ask Gemini for ONE action -> perform it -> repeat.
 * It stays silent while working and speaks only when the task is finished (or cannot continue).
 */
public class AgentService extends AccessibilityService {

  interface Listener {
    void onStatus(String s);
  }

  static volatile AgentService instance;
  static volatile Listener listener;
  static volatile boolean running;
  static volatile String lastStatus = "";

  private static final int MAX_STEPS = 40;

  private static final String SYS =
      "You control an Android phone for a visually impaired user through the Accessibility API. "
          + "Every turn you get the user's TASK, your PLAN with done/todo marks, a numbered list of "
          + "the elements on the CURRENT screen (sometimes also a screenshot) and your previous "
          + "steps. Reply with ONE JSON object and nothing else, in this shape: "
          + "{\"task_name\": \"\", \"plan\": [], \"done\": [], \"actions\": [ {\"action\": ...} ]}. "
          + "FIRST reply only: give \"task_name\" (short name) and \"plan\": the smallest ordered list "
          + "of short English steps (one per thing the user asked for, e.g. [\"Turn on TalkBack\", "
          + "\"Increase brightness\"]). Later replies: leave plan and task_name out. "
          + "\"done\": the 1-based numbers of plan steps that are completed once THIS reply's "
          + "actions have run (cumulative: include earlier done numbers). When every plan step is in "
          + "done, the app finishes by itself, so mark the last step done in the same reply as its "
          + "final action. "
          + "SCOPE: do exactly what the plan says and nothing else. Never open an app, setting or "
          + "search that the task does not need. Never use search unless nothing else works. Do not "
          + "re-check things that already worked. "
          + "SPEED: every reply costs seconds, so put up to 4 actions in one reply when they all act "
          + "on the CURRENT screen (e.g. type text then click the final button). Never put an action "
          + "after one that opens a new screen, app, dialog or page: send it alone. finish, fail and "
          + "ask_user must be the only action in their reply. Prefer direct routes: open_settings "
          + "with the right page (TalkBack and other accessibility services: "
          + "android.settings.ACCESSIBILITY_SETTINGS; brightness: android.settings.DISPLAY_SETTINGS "
          + "or global quick_settings, then set_progress on the brightness slider), open_app for apps. "
          + "Actions: click, long_click, tap_xy, type, ime_enter, scroll, set_progress, global, "
          + "open_app, open_settings, wait, screenshot, ask_user, finish, fail. Fields: "
          + "\"index\" (element number from the CURRENT list, for click/long_click/type/scroll/"
          + "set_progress); \"text\" (for type); \"percent\" (0-100, for set_progress on an element "
          + "that shows range=...); \"x\" and \"y\" (0-1000 across the screenshot, only for tap_xy); "
          + "\"direction\" (up/down/left/right, for scroll); "
          + "\"name\" (back/home/recents/notifications/quick_settings/lock, for global); "
          + "\"app\" (app name as shown on the launcher, for open_app); "
          + "\"settings_action\" (for open_settings, e.g. android.settings.SETTINGS, "
          + "android.settings.ACCESSIBILITY_SETTINGS, android.settings.DISPLAY_SETTINGS, "
          + "android.settings.WIFI_SETTINGS, android.settings.BLUETOOTH_SETTINGS, "
          + "android.settings.SOUND_SETTINGS, android.settings.APPLICATION_SETTINGS); "
          + "\"seconds\" (for wait); \"question\" (for ask_user); \"reason\" (for fail); "
          + "\"task_name\" and \"parts\" (for finish). "
          + "RULES: Element numbers change every step, use only the CURRENT list. If something is "
          + "not where expected, look in other places of the SAME area (other menus, scrolling, "
          + "other names for the same feature, e.g. TalkBack may be under Vision or Installed apps). "
          + "After typing a message either use ime_enter or click the send button. If a button has "
          + "no label and a screenshot is attached, use tap_xy. Never read out or reveal passwords. "
          + "Do NOT do irreversible or financial things (payments, transfers, deleting data, factory "
          + "reset, uninstalling apps) unless the task explicitly asks for exactly that. For OTP, "
          + "captcha, fingerprint or face unlock, payment confirmation, voice-model training or "
          + "anything only the user can do, use ask_user. Write task_name, plan, parts, question "
          + "and reason in simple English (the user may speak Hindi or English).";

  private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
  private final List<String> nodeSigs = new ArrayList<>();
  private Thread worker;
  private volatile long lastEvent = 0;
  private final ArrayDeque<Long> callTimes = new ArrayDeque<>();
  private volatile boolean stopFlag;
  private volatile boolean wantShotNext;
  private volatile String currentPkg = "?";
  private TextToSpeech tts;
  private volatile boolean ttsReady;
  private boolean volUp;
  private boolean volDown;

  // ---------- lifecycle ----------

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
    tts =
        new TextToSpeech(
            this,
            st -> {
              if (st == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.ENGLISH);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                  tts.setLanguage(Locale.getDefault());
                }
                tts.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsReady = true;
              }
            });
    publish("Ready. Type or speak a command.");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    lastEvent = System.currentTimeMillis();
  }

  @Override
  public void onInterrupt() {}

  @Override
  public boolean onUnbind(Intent intent) {
    shutdown();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    shutdown();
    super.onDestroy();
  }

  private void shutdown() {
    stopFlag = true;
    instance = null;
    if (tts != null) {
      tts.stop();
      tts.shutdown();
      tts = null;
    }
  }

  /** Emergency stop: press volume up and volume down together. */
  @Override
  protected boolean onKeyEvent(KeyEvent e) {
    int k = e.getKeyCode();
    boolean pressed = e.getAction() != KeyEvent.ACTION_UP;
    if (k == KeyEvent.KEYCODE_VOLUME_UP) {
      volUp = pressed;
    } else if (k == KeyEvent.KEYCODE_VOLUME_DOWN) {
      volDown = pressed;
    }
    if (volUp && volDown && running) {
      stopTask("Emergency stop. The task was stopped.");
    }
    return false;
  }

  // ---------- public control ----------

  synchronized boolean startTask(final String task, final String key) {
    if (running) return false;
    running = true;
    stopFlag = false;
    worker =
        new Thread(
            () -> {
              try {
                runTask(task, key);
              } catch (Throwable t) {
                if (!stopFlag) finishFail("something went wrong (" + t.getClass().getSimpleName() + ")");
              } finally {
                running = false;
              }
            });
    worker.start();
    return true;
  }

  void stopTask(String message) {
    stopFlag = true;
    Thread w = worker;
    if (w != null) w.interrupt();
    publish(message);
    speak(message);
  }

  // ---------- main loop ----------

  private void runTask(String task, String key) throws Exception {
    List<String> history = new ArrayList<>();
    List<String> parts = new ArrayList<>();
    List<String> plan = new ArrayList<>();
    TreeSet<Integer> done = new TreeSet<>();
    String taskName = "";
    String lastSig = "";
    int same = 0;
    int bad = 0;
    int mi = 0;
    String prov = Gemini.detect(key);
    List<String> models = new ArrayList<>();
    if (prov.equals("gemini")) {
      models.addAll(Gemini.pickModels(key));
      // Same generation as the Vision assistant's Gemini model (3.1): try it first.
      for (int i = 0; i < models.size(); i++) {
        if (models.get(i).contains("3.1-flash")) {
          models.add(0, models.remove(i));
          break;
        }
      }
      // A model typed in the app's settings always goes first.
      String forced = getSharedPreferences("agent", MODE_PRIVATE).getString("model", "").trim();
      if (!forced.isEmpty()) {
        models.remove(forced);
        models.add(0, forced);
      }
    } else {
      models.add(prov.equals("deepseek") ? Gemini.DEEPSEEK_MODEL : Gemini.OPENAI_MODEL);
    }
    wantShotNext = false;

    publish("Working on it");
    performGlobalAction(GLOBAL_ACTION_HOME);
    waitIdle(400, 1500);

    for (int step = 1; step <= MAX_STEPS; step++) {
      if (stopFlag) return;

      String screen = readScreen();
      String sig = currentPkg + "|" + screen.hashCode();
      if (sig.equals(lastSig)) same++;
      else same = 0;
      lastSig = sig;

      String hint = "";
      if (same >= 2) {
        hint =
            "\nHINT: the screen did not change after your last actions. Try something different "
                + "(another element, scroll, global back, or request a screenshot).";
        wantShotNext = true;
      }
      if (nodes.size() < 4) wantShotNext = true;

      String shot = null;
      if (wantShotNext && Build.VERSION.SDK_INT >= 30) {
        shot = captureShot();
      }
      wantShotNext = false;

      StringBuilder p = new StringBuilder();
      p.append("TASK: ").append(task).append('\n');
      if (plan.isEmpty()) {
        p.append("(No plan yet: in this reply also give task_name and plan.)\n");
      } else {
        p.append("YOUR PLAN (follow it, do nothing outside it):\n");
        for (int i = 0; i < plan.size(); i++) {
          p.append(i + 1).append(". ").append(done.contains(i + 1) ? "[done] " : "[todo] ")
              .append(plan.get(i)).append('\n');
        }
      }
      p.append("STEP: ").append(step).append('/').append(MAX_STEPS).append('\n');
      p.append("CURRENT APP PACKAGE: ").append(currentPkg).append('\n');
      p.append("PREVIOUS STEPS:\n");
      int from = Math.max(0, history.size() - 15);
      if (history.isEmpty()) p.append("(none)\n");
      for (int i = from; i < history.size(); i++) p.append(history.get(i)).append('\n');
      p.append("SCREEN ELEMENTS:\n").append(screen);
      if (shot != null) p.append("\n(A screenshot of the current screen is attached.)");
      p.append(hint);

      String reply = null;
      while (reply == null) {
        pace(mi == 0 ? 9 : 13);
        if (stopFlag) return;
        try {
          reply =
              prov.equals("gemini")
                  ? Gemini.generate(key, models.get(mi), SYS, p.toString(), shot)
                  : Gemini.generateOai(prov, key, SYS, p.toString(), shot);
        } catch (Gemini.GeminiException e) {
          if (stopFlag) return;
          if ((e.code == 429 || e.code >= 500 || e.modelProblem) && mi + 1 < models.size()) {
            mi++;
            continue;
          }
          finishFail(friendly(e) + " (model: " + models.get(mi) + ")");
          return;
        }
      }
      if (stopFlag) return;

      JSONObject a;
      List<JSONObject> list = new ArrayList<>();
      try {
        a = parseJson(reply);
        JSONArray acts = a.optJSONArray("actions");
        if (acts != null) {
          for (int i = 0; i < acts.length() && i < 4; i++) {
            JSONObject o = acts.optJSONObject(i);
            if (o != null) list.add(o);
          }
        } else if (a.has("action")) {
          list.add(a);
        }
        if (list.isEmpty()) throw new Exception("no actions");
        JSONArray pl = a.optJSONArray("plan");
        if (plan.isEmpty() && pl != null) {
          for (int i = 0; i < pl.length() && i < 8; i++) {
            String t = pl.optString(i, "").trim();
            if (!t.isEmpty()) plan.add(t);
          }
        }
        String tn = a.optString("task_name", "").trim();
        if (taskName.isEmpty() && !tn.isEmpty()) taskName = tn;
        JSONArray dn = a.optJSONArray("done");
        if (dn != null) {
          for (int i = 0; i < dn.length(); i++) {
            int v = dn.optInt(i, -1);
            if (v >= 1) done.add(v);
          }
        }
      } catch (Exception e) {
        history.add(step + ". (your reply was invalid, answer with one JSON object with an actions array)");
        if (++bad >= 3) {
          finishFail("the AI gave an answer I could not understand");
          return;
        }
        continue;
      }

      JSONObject first = list.get(0);
      String fa = first.optString("action", "");
      if (fa.equals("finish")) {
        List<String> fin = new ArrayList<>();
        JSONArray arr = first.optJSONArray("parts");
        if (arr == null) arr = a.optJSONArray("parts");
        if (arr != null) {
          for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) fin.add(s);
          }
        }
        if (fin.isEmpty()) fin = parts;
        String name = first.optString("task_name", a.optString("task_name", "")).trim();
        if (name.isEmpty()) name = task;
        finishOk(name, fin);
        return;
      }
      if (fa.equals("fail")) {
        finishFail(first.optString("reason", a.optString("reason", "the task could not be completed")));
        return;
      }
      if (fa.equals("ask_user")) {
        String q = first.optString("question", a.optString("question", "I need your help"));
        String m = q + " Please do this yourself, then give the command again.";
        publish(m);
        speak(m);
        return;
      }

      boolean allOk = true;
      for (int k = 0; k < list.size(); k++) {
        JSONObject o = list.get(k);
        String act = o.optString("action", "");
        if (act.equals("finish") || act.equals("fail") || act.equals("ask_user")) break;
        if (k > 0 && o.has("index") && !stillValid(o.optInt("index", -1))) {
          history.add(step + "." + (k + 1) + " (skipped: the screen changed, look again)");
          allOk = false;
          break;
        }
        if (stopFlag) return;
        String result = execute(o);
        history.add(step + "." + (k + 1) + " " + compact(o) + " -> " + result);
        if (result.startsWith("ERROR") || result.contains("failed")) {
          allOk = false;
          break;
        }
        if (k < list.size() - 1) waitIdle(250, 1200);
      }
      boolean planComplete = !plan.isEmpty();
      for (int i = 1; i <= plan.size(); i++) {
        if (!done.contains(i)) planComplete = false;
      }
      if (allOk && planComplete) {
        finishOk(taskName.isEmpty() ? task : taskName, plan);
        return;
      }

      if (!stopFlag) waitIdle(300, 2500);
    }
    finishFail("it took too many steps");
  }

  // ---------- finishing / speaking ----------

  private static final String[] ORD = {"First", "Second", "Third", "Fourth", "Fifth", "Sixth"};

  private void finishOk(String name, List<String> parts) {
    StringBuilder s = new StringBuilder("Your task was completed successfully: ").append(name).append(". ");
    if (!parts.isEmpty()) {
      s.append("It had these parts. ");
      for (int i = 0; i < parts.size(); i++) {
        String o = i < ORD.length ? ORD[i] : ("Part " + (i + 1));
        s.append(o).append(": ").append(parts.get(i)).append(". ");
      }
    }
    String msg = s.toString();
    publish(msg);
    speak(msg);
  }

  private void finishFail(String reason) {
    String msg = "The task could not be completed: " + reason;
    publish(msg);
    speak(msg);
  }

  private String friendly(Gemini.GeminiException e) {
    if (e.code == 429) {
      if (e.daily) {
        return "the daily free Gemini limit is used up for this key's Google project (it resets at"
            + " midnight Pacific time, about 1:30 PM in India). Other apps using the same key or"
            + " project also use this limit.";
      }
      return "the per-minute Gemini limit was reached. Wait one minute and try again.";
    }
    if (e.code == 599) {
      return "the AI took too long to answer. Check your internet and try again.";
    }
    if (e.code >= 500) {
      return "the AI server had a problem (" + e.code + "). Try again in a minute.";
    }
    if (e.modelProblem) {
      return "none of the available Gemini models worked for this key (" + clipMsg(e.getMessage()) + ")";
    }
    if (e.code == 400 || e.code == 401 || e.code == 403) {
      return "the API key is not valid or is not allowed (" + clipMsg(e.getMessage()) + ")";
    }
    return "Gemini error " + e.code + ": " + clipMsg(e.getMessage());
  }

  private static String clipMsg(String m) {
    if (m == null) return "";
    String s = m.replace('\n', ' ').trim();
    return s.length() > 140 ? s.substring(0, 140) : s;
  }

  private void publish(String s) {
    lastStatus = s;
    Listener l = listener;
    if (l != null) l.onStatus(s);
  }

  private void speak(String text) {
    if (tts != null && ttsReady) {
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent");
    }
  }

  // ---------- reading the screen ----------

  private String readScreen() {
    nodes.clear();
    nodeSigs.clear();
    AccessibilityNodeInfo root = null;
    for (int i = 0; i < 6; i++) {
      root = getRootInActiveWindow();
      if (root != null) break;
      sleep(500);
    }
    if (root == null) {
      currentPkg = "?";
      return "(screen could not be read)";
    }
    currentPkg = String.valueOf(root.getPackageName());
    StringBuilder sb = new StringBuilder();
    walk(root, 0, sb);
    if (sb.length() == 0) sb.append("(no readable elements)");
    return sb.toString();
  }

  private void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
    if (n == null || nodes.size() >= 250 || depth > 40) return;
    if (!n.isVisibleToUser()) return;
    CharSequence t = n.getText();
    CharSequence d = n.getContentDescription();
    CharSequence h = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
    boolean cl = n.isClickable();
    boolean ed = n.isEditable();
    boolean sc = n.isScrollable();
    boolean ch = n.isCheckable();
    AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
    boolean has = notEmpty(t) || notEmpty(d) || notEmpty(h);
    if (has || cl || ed || sc || ch || ri != null) {
      int idx = nodes.size();
      nodes.add(n);
      nodeSigs.add(sigOf(n));
      sb.append('[').append(idx).append("] ").append(shortClass(n.getClassName()));
      if (n.isPassword()) {
        sb.append(" (password field)");
      } else if (notEmpty(t)) {
        sb.append(" text=\"").append(clip(t)).append('"');
      }
      if (notEmpty(d)) sb.append(" desc=\"").append(clip(d)).append('"');
      if (notEmpty(h)) sb.append(" hint=\"").append(clip(h)).append('"');
      if (ri != null) {
        sb.append(" range=").append((int) ri.getMin()).append("..").append((int) ri.getMax())
            .append(" current=").append((int) ri.getCurrent());
      }
      if (cl) sb.append(" clickable");
      if (ed) sb.append(" editable");
      if (ch) sb.append(n.isChecked() ? " checked" : " unchecked");
      if (sc) sb.append(" scrollable");
      if (n.isFocused()) sb.append(" focused");
      sb.append('\n');
    }
    for (int i = 0; i < n.getChildCount(); i++) {
      walk(n.getChild(i), depth + 1, sb);
    }
  }

  private static String sigOf(AccessibilityNodeInfo n) {
    return shortClass(n.getClassName()) + "|" + n.getText() + "|" + n.getContentDescription();
  }

  private boolean stillValid(int idx) {
    if (idx < 0 || idx >= nodes.size()) return false;
    AccessibilityNodeInfo n = nodes.get(idx);
    try {
      if (!n.refresh()) return false;
    } catch (Exception e) {
      return false;
    }
    return sigOf(n).equals(nodeSigs.get(idx));
  }

  /** Waits only when we are about to exceed the per-minute request limit. */
  private void pace(int perMinute) {
    long now = System.currentTimeMillis();
    while (!callTimes.isEmpty() && now - callTimes.peekFirst() > 60000) callTimes.pollFirst();
    if (callTimes.size() >= perMinute) {
      long wait = callTimes.peekFirst() + 60000 - now + 300;
      if (wait > 0) sleep(wait);
    }
    callTimes.addLast(System.currentTimeMillis());
  }

  /** Waits until the screen stops changing (or maxMs), instead of a fixed long sleep. */
  private void waitIdle(long minMs, long maxMs) {
    long start = System.currentTimeMillis();
    sleep(minMs);
    while (!stopFlag
        && System.currentTimeMillis() - start < maxMs
        && System.currentTimeMillis() - lastEvent < 350) {
      sleep(50);
    }
  }

  private static boolean notEmpty(CharSequence c) {
    return c != null && c.toString().trim().length() > 0;
  }

  private static String clip(CharSequence c) {
    String s = c.toString().replace('\n', ' ').trim();
    return s.length() > 120 ? s.substring(0, 120) + "..." : s;
  }

  private static String shortClass(CharSequence c) {
    String s = c == null ? "View" : c.toString();
    int i = s.lastIndexOf('.');
    return i >= 0 ? s.substring(i + 1) : s;
  }

  private String captureShot() {
    final Bitmap[] out = new Bitmap[1];
    final CountDownLatch latch = new CountDownLatch(1);
    try {
      takeScreenshot(
          Display.DEFAULT_DISPLAY,
          getMainExecutor(),
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult r) {
              try {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                if (hw != null) out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                r.getHardwareBuffer().close();
              } catch (Throwable ignored) {
              }
              latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
              latch.countDown();
            }
          });
      latch.await(6, TimeUnit.SECONDS);
    } catch (Throwable t) {
      return null;
    }
    Bitmap bmp = out[0];
    if (bmp == null) return null;
    int w = bmp.getWidth();
    int h = bmp.getHeight();
    float s = Math.min(1f, 1024f / Math.max(w, h));
    Bitmap sm = Bitmap.createScaledBitmap(bmp, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    sm.compress(Bitmap.CompressFormat.JPEG, 70, bo);
    return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
  }

  // ---------- doing actions ----------

  private String execute(JSONObject a) {
    String act = a.optString("action", "");
    switch (act) {
      case "click":
      case "long_click":
        {
          AccessibilityNodeInfo n = node(a);
          if (n == null) return "ERROR: invalid element index";
          boolean lng = act.equals("long_click");
          return clickNode(n, lng) ? (lng ? "long-clicked" : "clicked") : "click failed";
        }
      case "tap_xy":
        {
          if (a.optDouble("x", -1) < 0 || a.optDouble("y", -1) < 0) return "ERROR: x and y needed";
          DisplayMetrics dm = getResources().getDisplayMetrics();
          float x = (float) (a.optDouble("x") / 1000.0 * dm.widthPixels);
          float y = (float) (a.optDouble("y") / 1000.0 * dm.heightPixels);
          Path p = new Path();
          p.moveTo(x, y);
          return gesture(p, 60) ? "tapped" : "tap failed";
        }
      case "type":
        {
          AccessibilityNodeInfo n = node(a);
          if (n == null) n = focusedInput();
          if (n == null) return "ERROR: no input field found";
          Bundle b = new Bundle();
          b.putCharSequence(
              AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text", ""));
          n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
          return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
              ? "typed"
              : "type failed";
        }
      case "ime_enter":
        {
          AccessibilityNodeInfo f = focusedInput();
          if (f == null) return "ERROR: no focused input field";
          if (Build.VERSION.SDK_INT >= 30) {
            boolean ok =
                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            return ok ? "enter pressed" : "enter failed - click the send button instead";
          }
          return "enter not supported - click the send button instead";
        }
      case "scroll":
        {
          AccessibilityNodeInfo n = node(a);
          if (n == null) {
            for (AccessibilityNodeInfo c : nodes) {
              if (c.isScrollable()) {
                n = c;
                break;
              }
            }
          }
          String dir = a.optString("direction", "down");
          boolean fwd = dir.equals("down") || dir.equals("right") || dir.equals("forward");
          if (n != null
              && n.performAction(
                  fwd
                      ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                      : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return "scrolled " + dir;
          }
          DisplayMetrics dm = getResources().getDisplayMetrics();
          float cx = dm.widthPixels / 2f;
          float y1 = dm.heightPixels * 0.7f;
          float y2 = dm.heightPixels * 0.3f;
          Path p = new Path();
          if (fwd) {
            p.moveTo(cx, y1);
            p.lineTo(cx, y2);
          } else {
            p.moveTo(cx, y2);
            p.lineTo(cx, y1);
          }
          return gesture(p, 400) ? "swiped " + dir : "scroll failed";
        }
      case "set_progress":
        {
          AccessibilityNodeInfo n = node(a);
          if (n == null) return "ERROR: invalid element index";
          AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
          if (ri == null) return "ERROR: that element is not a slider";
          double pct = Math.max(0, Math.min(100, a.optDouble("percent", 50)));
          float v = (float) (ri.getMin() + (ri.getMax() - ri.getMin()) * pct / 100.0);
          Bundle b = new Bundle();
          b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v);
          return n.performAction(
                  AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), b)
              ? "slider set to " + (int) pct + "%"
              : "set_progress failed";
        }
      case "global":
        return globalAction(a.optString("name", ""));
      case "open_app":
        return openApp(a.optString("app", ""));
      case "open_settings":
        return openSettings(a.optString("settings_action", ""));
      case "wait":
        {
          int s = Math.max(1, Math.min(10, a.optInt("seconds", 2)));
          sleep(s * 1000L);
          return "waited " + s + "s";
        }
      case "screenshot":
        wantShotNext = true;
        return "a screenshot will be attached to the next step";
      default:
        return "ERROR: unknown action '" + act + "'";
    }
  }

  private AccessibilityNodeInfo node(JSONObject a) {
    int idx = a.optInt("index", -1);
    return (idx >= 0 && idx < nodes.size()) ? nodes.get(idx) : null;
  }

  private AccessibilityNodeInfo focusedInput() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return null;
    return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
  }

  private boolean clickNode(AccessibilityNodeInfo n, boolean lng) {
    int act = lng ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
    AccessibilityNodeInfo c = n;
    for (int i = 0; i < 6 && c != null; i++) {
      if (lng ? c.isLongClickable() : c.isClickable()) {
        if (c.performAction(act)) return true;
        break;
      }
      c = c.getParent();
    }
    Rect r = new Rect();
    n.getBoundsInScreen(r);
    if (r.isEmpty()) return false;
    Path p = new Path();
    p.moveTo(r.exactCenterX(), r.exactCenterY());
    return gesture(p, lng ? 800 : 60);
  }

  private boolean gesture(Path path, long duration) {
    final CountDownLatch latch = new CountDownLatch(1);
    final boolean[] ok = {false};
    GestureDescription g =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
            .build();
    boolean sent =
        dispatchGesture(
            g,
            new GestureResultCallback() {
              @Override
              public void onCompleted(GestureDescription d) {
                ok[0] = true;
                latch.countDown();
              }

              @Override
              public void onCancelled(GestureDescription d) {
                latch.countDown();
              }
            },
            null);
    if (!sent) return false;
    try {
      latch.await(3, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
    return ok[0];
  }

  private String globalAction(String name) {
    int act;
    switch (name) {
      case "back":
        act = GLOBAL_ACTION_BACK;
        break;
      case "home":
        act = GLOBAL_ACTION_HOME;
        break;
      case "recents":
        act = GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
        act = GLOBAL_ACTION_NOTIFICATIONS;
        break;
      case "quick_settings":
        act = GLOBAL_ACTION_QUICK_SETTINGS;
        break;
      case "lock":
        if (Build.VERSION.SDK_INT < 28) return "lock not supported on this Android version";
        act = GLOBAL_ACTION_LOCK_SCREEN;
        break;
      default:
        return "ERROR: unknown global action '" + name + "'";
    }
    return performGlobalAction(act) ? "done: " + name : "global action failed";
  }

  private String openApp(String name) {
    String want = name.trim().toLowerCase(Locale.ROOT);
    if (want.isEmpty()) return "ERROR: app name needed";
    PackageManager pm = getPackageManager();
    Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
    ResolveInfo best = null;
    StringBuilder names = new StringBuilder();
    for (ResolveInfo ri : list) {
      String label = String.valueOf(ri.loadLabel(pm));
      String l = label.toLowerCase(Locale.ROOT);
      if (names.length() < 900) names.append(label).append(", ");
      if (l.equals(want)) {
        best = ri;
        break;
      }
      if (best == null && (l.contains(want) || want.contains(l))) best = ri;
    }
    if (best == null) {
      for (ResolveInfo ri : list) {
        if (ri.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(want)) {
          best = ri;
          break;
        }
      }
    }
    if (best == null) return "ERROR: app not found. Installed apps include: " + names;
    Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
    if (i == null) return "ERROR: cannot launch that app";
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(i);
      return "opened " + best.loadLabel(pm);
    } catch (Exception e) {
      return "open failed";
    }
  }

  private String openSettings(String action) {
    String act = action == null ? "" : action.trim();
    if (!act.startsWith("android.settings.")) act = android.provider.Settings.ACTION_SETTINGS;
    Intent i = new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(i);
      return "opened settings page " + act;
    } catch (ActivityNotFoundException e) {
      try {
        startActivity(
            new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "that page does not exist, opened main Settings instead";
      } catch (Exception e2) {
        return "open settings failed";
      }
    } catch (Exception e) {
      return "open settings failed";
    }
  }

  // ---------- helpers ----------

  private static JSONObject parseJson(String reply) throws Exception {
    String s = reply.trim();
    if (s.startsWith("```")) {
      int a = s.indexOf('\n');
      int b = s.lastIndexOf("```");
      if (a > 0 && b > a) s = s.substring(a + 1, b).trim();
    }
    int i = s.indexOf('{');
    int j = s.lastIndexOf('}');
    if (i >= 0 && j > i) s = s.substring(i, j + 1);
    return new JSONObject(s);
  }

  private static String compact(JSONObject a) {
    StringBuilder sb = new StringBuilder(a.optString("action", "?"));
    String[] keys = {"index", "text", "x", "y", "direction", "name", "app", "settings_action"};
    for (String k : keys) {
      if (a.has(k)) sb.append(' ').append(k).append('=').append(a.opt(k));
    }
    return sb.toString();
  }

  private void sleep(long ms) {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) {
      if (stopFlag) return;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        return;
      }
    }
  }
}
