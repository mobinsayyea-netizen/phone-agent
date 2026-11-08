package com.mobeen.phoneagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Gemini REST client (no external libraries) with automatic model discovery. */
final class Gemini {
  private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

  /** Used only if the live model list cannot be fetched. */
  private static final String[] FALLBACK = {
    "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-2.5-flash-lite"
  };

  private static List<String> cached;
  private static String cachedFor = "";
  private static long cachedAt;
  private static final Set<String> noThinking = new HashSet<>();

  static class GeminiException extends Exception {
    final int code;
    final boolean daily;
    final boolean modelProblem;

    GeminiException(int code, String msg, boolean daily) {
      super(msg);
      this.code = code;
      this.daily = daily;
      String m = msg == null ? "" : msg.toLowerCase();
      this.modelProblem =
          code == 404
              || m.contains("no longer available")
              || m.contains("is not found")
              || m.contains("not supported for generatecontent");
    }
  }

  private Gemini() {}

  // ---------- model discovery ----------

  /** Asks the API which models this key can use and returns the best ones, best first. */
  static synchronized List<String> pickModels(String key) {
    long now = System.currentTimeMillis();
    if (cached != null && key.equals(cachedFor) && now - cachedAt < 6L * 3600 * 1000) {
      return cached;
    }
    List<String> result = new ArrayList<>();
    try {
      List<String[]> found = new ArrayList<>(); // {id, score}
      String token = "";
      for (int page = 0; page < 4; page++) {
        String url = BASE + "models?pageSize=200" + (token.isEmpty() ? "" : "&pageToken=" + token);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("x-goog-api-key", key);
        int code = c.getResponseCode();
        String resp = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) break;
        JSONObject r = new JSONObject(resp);
        JSONArray arr = r.optJSONArray("models");
        if (arr != null) {
          for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            JSONArray meth = m.optJSONArray("supportedGenerationMethods");
            boolean ok = false;
            if (meth != null) {
              for (int k = 0; k < meth.length(); k++) {
                if ("generateContent".equals(meth.optString(k))) ok = true;
              }
            }
            if (!ok) continue;
            String id = m.optString("name", "").replace("models/", "");
            String score = score(id);
            if (score != null) found.add(new String[] {id, score});
          }
        }
        token = r.optString("nextPageToken", "");
        if (token.isEmpty()) break;
      }
      Collections.sort(found, (x, y) -> y[1].compareTo(x[1]));
      for (String[] f : found) {
        if (!result.contains(f[0])) result.add(f[0]);
        if (result.size() >= 4) break;
      }
    } catch (Exception ignored) {
    }
    if (result.isEmpty()) {
      for (String f : FALLBACK) result.add(f);
    } else {
      cached = result;
      cachedFor = key;
      cachedAt = now;
    }
    return result;
  }

  /** Higher string = better. Null = not a plain text flash model we want. */
  private static String score(String id) {
    Matcher m = Pattern.compile("^gemini-(\\d+)(?:\\.(\\d+))?-(flash-lite|flash)(.*)$").matcher(id);
    if (!m.matches()) return null;
    String rest = m.group(4).toLowerCase();
    String[] bad = {
      "image", "tts", "live", "audio", "embed", "robotics", "exp", "thinking", "native",
      "computer", "customtools", "vision"
    };
    for (String b : bad) if (rest.contains(b)) return null;
    int major = Integer.parseInt(m.group(1));
    int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    boolean lite = m.group(3).equals("flash-lite");
    boolean preview = rest.contains("preview");
    return String.format("%03d.%03d.%d.%d", major, minor, lite ? 0 : 1, preview ? 0 : 1);
  }

  // ---------- generation ----------

  private static byte[] payload(String model, String system, String prompt, String jpegB64)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put(
        "systemInstruction",
        new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
    JSONArray parts = new JSONArray();
    parts.put(new JSONObject().put("text", prompt));
    if (jpegB64 != null) {
      parts.put(
          new JSONObject()
              .put(
                  "inlineData",
                  new JSONObject().put("mimeType", "image/jpeg").put("data", jpegB64)));
    }
    body.put(
        "contents",
        new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
    JSONObject gen = new JSONObject().put("responseMimeType", "application/json");
    boolean v3 = model.startsWith("gemini-3");
    if (!v3) gen.put("temperature", 0.2);
    if (!noThinking.contains(model)) {
      gen.put(
          "thinkingConfig",
          v3
              ? new JSONObject().put("thinkingLevel", "minimal")
              : new JSONObject().put("thinkingBudget", 0));
    }
    body.put("generationConfig", gen);
    return body.toString().getBytes("UTF-8");
  }

  static String generate(String key, String model, String system, String prompt, String jpegB64)
      throws Exception {
    int tries429 = 0;
    int tries5xx = 0;
    while (true) {
      byte[] data = payload(model, system, prompt, jpegB64);
      HttpURLConnection c =
          (HttpURLConnection) new URL(BASE + "models/" + model + ":generateContent").openConnection();
      c.setRequestMethod("POST");
      c.setConnectTimeout(20000);
      c.setReadTimeout(90000);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("x-goog-api-key", key);
      c.setDoOutput(true);
      try (OutputStream os = c.getOutputStream()) {
        os.write(data);
      }
      int code = c.getResponseCode();
      InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
      String resp = read(is);
      c.disconnect();
      if (code >= 200 && code < 300) {
        return extractText(resp);
      }
      String msg = errorMessage(resp);
      if (code == 400 && msg.toLowerCase().contains("thinking") && !noThinking.contains(model)) {
        noThinking.add(model); // this model rejects our thinking setting: retry without it
        continue;
      }
      if (code == 429) {
        boolean daily = resp.contains("PerDay");
        if (daily) throw new GeminiException(429, msg, true);
        if (tries429 < 2) {
          tries429++;
          Thread.sleep(retryDelayMs(resp));
          continue;
        }
        throw new GeminiException(429, msg, false);
      }
      if ((code == 500 || code == 503) && tries5xx < 2) {
        tries5xx++;
        Thread.sleep(5000);
        continue;
      }
      throw new GeminiException(code, msg, false);
    }
  }

  private static long retryDelayMs(String resp) {
    Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)").matcher(resp);
    if (m.find()) {
      long s = Long.parseLong(m.group(1)) + 2;
      return Math.min(65L, s) * 1000L;
    }
    return 20000L;
  }

  private static String extractText(String resp) throws Exception {
    JSONObject r = new JSONObject(resp);
    JSONArray cands = r.optJSONArray("candidates");
    if (cands == null || cands.length() == 0) {
      throw new GeminiException(400, "empty response", false);
    }
    JSONObject content = cands.getJSONObject(0).optJSONObject("content");
    if (content == null) {
      throw new GeminiException(400, "no content", false);
    }
    JSONArray ps = content.optJSONArray("parts");
    StringBuilder sb = new StringBuilder();
    if (ps != null) {
      for (int i = 0; i < ps.length(); i++) {
        JSONObject p = ps.getJSONObject(i);
        if (p.optBoolean("thought", false)) continue;
        sb.append(p.optString("text", ""));
      }
    }
    return sb.toString();
  }

  private static String errorMessage(String resp) {
    try {
      JSONObject e = new JSONObject(resp).optJSONObject("error");
      if (e != null) return e.optString("message", resp);
    } catch (Exception ignored) {
    }
    return resp;
  }

  private static String read(InputStream is) throws IOException {
    if (is == null) return "";
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
    return bo.toString("UTF-8");
  }
}
