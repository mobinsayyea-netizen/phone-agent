package com.mobeen.phoneagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Gemini REST client (no external libraries). */
final class Gemini {
  /** Tried in order. Each model has its own free quota, so we fall back when one runs out. */
  static final String[] MODELS = {"gemini-2.5-flash", "gemini-2.5-flash-lite"};

  static class GeminiException extends Exception {
    final int code;
    final boolean daily;

    GeminiException(int code, String msg, boolean daily) {
      super(msg);
      this.code = code;
      this.daily = daily;
    }
  }

  private Gemini() {}

  static String generate(String key, String model, String system, String prompt, String jpegB64)
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
    body.put(
        "generationConfig",
        new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2));
    byte[] payload = body.toString().getBytes("UTF-8");

    int tries429 = 0;
    int tries5xx = 0;
    while (true) {
      HttpURLConnection c =
          (HttpURLConnection)
              new URL(
                      "https://generativelanguage.googleapis.com/v1beta/models/"
                          + model
                          + ":generateContent")
                  .openConnection();
      c.setRequestMethod("POST");
      c.setConnectTimeout(20000);
      c.setReadTimeout(90000);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("x-goog-api-key", key);
      c.setDoOutput(true);
      try (OutputStream os = c.getOutputStream()) {
        os.write(payload);
      }
      int code = c.getResponseCode();
      InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
      String resp = read(is);
      c.disconnect();
      if (code >= 200 && code < 300) {
        return extractText(resp);
      }
      String msg = errorMessage(resp);
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
        sb.append(ps.getJSONObject(i).optString("text", ""));
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
