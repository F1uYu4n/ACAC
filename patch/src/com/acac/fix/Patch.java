package com.acac.fix;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xx.afaf.model.HomeRecommendItem;
import com.xx.afaf.model.video.AcContent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import o9.a;

/**
 * 频道(分区)数据修复补丁。
 *
 * 原 APK 通过抓取 https://www.acfun.cn/v/list{id}/index.htm 的 HTML
 * (div.channel-channel-module / div.video-item) 来生成分区列表。
 * AcFun 已在 2024/2025 改版为 Vue 单页应用, 旧 DOM 结构彻底消失,
 * 因此除"推荐"外的所有分区都解析为空。
 *
 * 本类改用 AcFun 现行官方接口:
 *   POST https://www.acfun.cn/rest/pc-direct/channel/resource/query/match
 *        sortField=rankScore&channelId={cid}&page={page}
 * 返回 JSON: { result:0, feed:[...], total:n, channelInfo:{...} }
 */
public final class Patch {

    private static final String TAG = "ACACFIX";
    private static final String API = "https://www.acfun.cn/rest/pc-direct/channel/resource/query/match";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/111.0.0.0 Safari/537.36";

    private static final int TITLE_ID_BASE = 100000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Patch() {
    }

    /** NetworkManager.getCategory(int, a) 的替代实现: 返回 [栏目标题] + [视频卡片...] */
    public static void category(final int cid, final a cb) {
        request(cid, 1, true, cb);
    }

    /** NetworkManager.getCategoryDetail(int, int, a) 的替代实现: 只返回视频卡片 */
    public static void categoryDetail(final int id, final int page, final a cb) {
        // 标题行传下来的是 100000+cid, 用于绕开 b9.a.f2207b 的"切换顶部标签"逻辑
        int cid = id >= TITLE_ID_BASE ? id - TITLE_ID_BASE : id;
        request(cid, page <= 0 ? 1 : page, false, cb);
    }

    private static void request(final int cid, final int page, final boolean withTitle, final a cb) {
        Log.i(TAG, "category cid=" + cid + " page=" + page + " title=" + withTitle);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<HomeRecommendItem> data = fetch(cid, page, withTitle);
                    Log.i(TAG, "cid=" + cid + " page=" + page + " items=" + data.size());
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.c(data);
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "cid=" + cid + " page=" + page + " failed: " + t, t);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.d(t);
                        }
                    });
                }
            }
        }, "acac-fix-" + cid).start();
    }

    private static List<HomeRecommendItem> fetch(int cid, int page, boolean withTitle) throws Exception {
        String body = "sortField=rankScore&channelId=" + cid + "&page=" + page;
        String json = post(API, body);
        JSONObject root = new JSONObject(json);
        int result = root.optInt("result", -1);
        if (result != 0) {
            throw new IllegalStateException("acfun result=" + result + " msg=" + root.optString("msg"));
        }

        ArrayList<HomeRecommendItem> out = new ArrayList<HomeRecommendItem>();
        if (withTitle) {
            HomeRecommendItem title = new HomeRecommendItem();
            title.setViewType(2);
            title.setTitleId(TITLE_ID_BASE + cid);
            title.setTitleStr(channelName(root, cid));
            out.add(title);
        }

        JSONArray feed = root.optJSONArray("feed");
        if (feed != null) {
            int n = 0;
            for (int i = 0; i < feed.length(); i++) {
                JSONObject o = feed.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                AcContent content = toContent(o);
                if (content == null) {
                    continue;
                }
                HomeRecommendItem item = new HomeRecommendItem();
                item.setViewType(1);
                item.setItem(content);
                item.setInnerItemCount(n++);
                out.add(item);
            }
        }
        return out;
    }

    private static AcContent toContent(JSONObject o) {
        String id = o.optString("dougaId", "");
        if (id.length() == 0) {
            return null;
        }
        long duration = o.optLong("durationMillis", 0L);
        if (duration <= 0) {
            JSONArray videos = o.optJSONArray("videoList");
            if (videos != null && videos.length() > 0) {
                JSONObject v0 = videos.optJSONObject(0);
                if (v0 != null) {
                    duration = v0.optLong("durationMillis", 0L);
                }
            }
        }
        String up = "";
        JSONObject user = o.optJSONObject("user");
        if (user != null) {
            up = user.optString("name", "");
        }
        AcContent c = new AcContent();
        c.setTitle(o.optString("title", ""));
        c.setUp(up);
        c.setImg(o.optString("coverUrl", ""));
        c.setUrl("https://www.acfun.cn/v/ac" + id);
        c.setType(1);
        c.setTime(fmtDuration(duration));
        c.setViewNum(fmtCount(o.optLong("viewCount", 0L)));
        c.setDmNum(fmtCount(o.optLong("danmakuCount", 0L)));
        c.setPublishDateStr(fmtDate(o.optLong("createTimeMillis", 0L)));
        c.setShowDetail("");
        return c;
    }

    private static String channelName(JSONObject root, int cid) {
        JSONObject info = root.optJSONObject("channelInfo");
        if (info != null) {
            String name = info.optString("name", "");
            if (name.length() > 0) {
                return name;
            }
        }
        switch (cid) {
            case 1: return "动画";
            case 60: return "娱乐";
            case 201: return "生活";
            case 58: return "音乐";
            case 123: return "舞蹈";
            case 59: return "游戏";
            case 70: return "科技";
            case 68: return "影视";
            case 69: return "体育";
            case 125: return "鱼塘";
            case 155: return "番剧";
            default: return "全部视频";
        }
    }

    private static String fmtDuration(long ms) {
        if (ms <= 0) {
            return "";
        }
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String fmtCount(long n) {
        if (n >= 100000000L) {
            return String.format(Locale.US, "%.1f亿", n / 100000000.0d);
        }
        if (n >= 10000L) {
            return String.format(Locale.US, "%.1f万", n / 10000.0d);
        }
        return String.valueOf(n);
    }

    private static String fmtDate(long ms) {
        if (ms <= 0) {
            return "";
        }
        return new SimpleDateFormat("MM-dd", Locale.US).format(new Date(ms));
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.acfun.cn/");
            conn.setRequestProperty("Origin", "https://www.acfun.cn");

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                throw new IllegalStateException("HTTP " + code + " without body");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            is.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code + ": " + text.substring(0, Math.min(120, text.length())));
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }
}
