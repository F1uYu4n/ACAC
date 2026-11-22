package org.json;

public class JSONObject {
    public JSONObject() { }
    public JSONObject(String json) throws JSONException { }
    public int optInt(String name, int fallback) { return fallback; }
    public long optLong(String name, long fallback) { return fallback; }
    public String optString(String name) { return ""; }
    public String optString(String name, String fallback) { return fallback; }
    public JSONArray optJSONArray(String name) { return null; }
    public JSONObject optJSONObject(String name) { return null; }
    public boolean optBoolean(String name, boolean fallback) { return fallback; }
}
