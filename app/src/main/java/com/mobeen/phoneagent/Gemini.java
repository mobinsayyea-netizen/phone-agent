package com.mobeen.phoneagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal Gemini REST client (no external libraries). */
final class Gemini {
  static final String MODEL = "gemini-2.5-flash";

  static class GeminiException extends Exception {
    final int code;

    GeminiException(int code, String msg) {
      super(msg);
      this.code = code;
    }
  }

  private Gemini() {}

  /** Sends one turn; returns the model's text (expected to be a JSON object). */
  static String generate(String key, String system, String prompt, String jpegB64)
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

    int attempt = 0;
    while (true) {
      attempt++;
      HttpURLConnection c =
          (HttpURLConnection)
              new URL(
                      "https://generativelanguage.googleapis.com/v1beta/models/"
                          + MODEL
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
      if ((code == 429 || code == 500 || code == 503) && attempt < 4) {
        Thread.sleep(attempt * 6000L);
        continue;
      }
      throw new GeminiException(code, errorMessage(resp));
    }
  }

  private static String extractText(String resp) throws Exception {
    JSONObject r = new JSONObject(resp);
    JSONArray cands = r.optJSONArray("candidates");
    if (cands == null || cands.length() == 0) {
      throw new GeminiException(400, "empty response");
    }
    JSONObject content = cands.getJSONObject(0).optJSONObject("content");
    if (content == null) {
      throw new GeminiException(400, "no content");
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
