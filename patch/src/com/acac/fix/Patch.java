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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *
 * 1.0.4 修复要点:
 *   - 同一分区内请求串行化: App 的滚动监听会在一次快滑里连发十几个页码,
 *     原来每页各开一个线程, 响应顺序完全错乱, 而列表只按"到达顺序"追加,
 *     于是页序颠倒; 更糟的是 page==1 走 set() 替换, 若它晚于 page==2 到达,
 *     已追加的第 2 页会被整个冲掉。改为每 cid 一个单线程队列后, 响应严格
 *     按请求顺序回到 UI 线程。
 *   - 在途去重 + 已加载页码短路: 同一 (cid,page) 只发一次; 页码不大于已交付
 *     的最高页时直接返回空列表, 既不重复追加也不浪费流量。
 *   - 末页不再报错: 接口对超出范围的页返回 result=21, 原来当成异常抛给 UI,
 *     整页被替换成"网络错误"。现在按 total 预先算出末页, 越界直接返回空列表;
 *     万一仍拿到 result=21, 也按"没有更多"处理。
 *   - 日期统一为 yyyy-MM-dd HH:mm (原来只有 MM-dd, 跨年内容有歧义)。
 */
public final class Patch {

    private static final String TAG = "ACACFIX";
    private static final String API = "https://www.acfun.cn/rest/pc-direct/channel/resource/query/match";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/111.0.0.0 Safari/537.36";

    /** 接口对"页码越界"返回的业务码 */
    private static final int RESULT_NO_MORE = 21;

    private static final int TITLE_ID_BASE = 100000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 保护下面四张表; 请求从 UI 线程发起, 结果在各自的工作线程写回 */
    private static final Object LOCK = new Object();
    /** cid -> 单线程队列, 保证同一分区的请求按页码顺序完成 */
    private static final Map<Integer, ExecutorService> QUEUES = new HashMap<Integer, ExecutorService>();
    /** cid -> 在途页码, 防止同一页被重复拉取 */
    private static final Map<Integer, Set<Integer>> PENDING = new HashMap<Integer, Set<Integer>>();
    /** cid -> 已成功交付的最大页码(page>1), 用于丢弃重复请求 */
    private static final Map<Integer, Integer> HIGHEST = new HashMap<Integer, Integer>();
    /** cid -> 末页页码, 来自 page=1 的 total, 越界请求直接短路 */
    private static final Map<Integer, Integer> LAST_PAGE = new HashMap<Integer, Integer>();

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
        request(cid, page, false, cb);
    }

    private static void request(final int cid, final int reqPage, final boolean withTitle, final a cb) {
        final int page = reqPage <= 0 ? 1 : reqPage;
        synchronized (LOCK) {
            if (!withTitle) {
                Integer last = LAST_PAGE.get(cid);
                if (last != null && page > last.intValue()) {
                    Log.i(TAG, "cid=" + cid + " page=" + page + " > last(" + last + "), skip");
                    postEmpty(cb);
                    return;
                }
                Integer hi = HIGHEST.get(cid);
                if (hi != null && page <= hi.intValue()) {
                    Log.i(TAG, "cid=" + cid + " page=" + page + " already loaded(" + hi + "), skip");
                    postEmpty(cb);
                    return;
                }
            }
            Set<Integer> pending = PENDING.get(cid);
            if (pending == null) {
                pending = new HashSet<Integer>();
                PENDING.put(cid, pending);
            }
            if (!pending.add(Integer.valueOf(page))) {
                // 同一页已在途: 不再发请求, 由在途的那次回调
                Log.i(TAG, "cid=" + cid + " page=" + page + " in flight, skip");
                return;
            }
        }

        Log.i(TAG, "enqueue cid=" + cid + " page=" + page + " title=" + withTitle);
        executor(cid).execute(new Runnable() {
            @Override
            public void run() {
                List<HomeRecommendItem> data = null;
                Throwable error = null;
                try {
                    data = fetch(cid, page, withTitle);
                } catch (Throwable t) {
                    error = t;
                }
                synchronized (LOCK) {
                    PENDING.get(cid).remove(Integer.valueOf(page));
                }
                if (error != null) {
                    Log.e(TAG, "cid=" + cid + " page=" + page + " failed: " + error, error);
                    postError(cb, error);
                    return;
                }
                // data == null 表示"已到末页": 记下末页, 返回空列表而不是抛异常
                List<HomeRecommendItem> out = data == null ? new ArrayList<HomeRecommendItem>(0) : data;
                Log.i(TAG, "cid=" + cid + " page=" + page + " items=" + out.size());
                postData(cb, out);
            }
        });
    }

    private static ExecutorService executor(final int cid) {
        synchronized (LOCK) {
            ExecutorService es = QUEUES.get(cid);
            if (es == null) {
                es = Executors.newSingleThreadExecutor();
                QUEUES.put(cid, es);
            }
            return es;
        }
    }

    private static void postData(final a cb, final List<HomeRecommendItem> data) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.c(data);
            }
        });
    }

    private static void postEmpty(final a cb) {
        postData(cb, new ArrayList<HomeRecommendItem>(0));
    }

    private static void postError(final a cb, final Throwable t) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.d(t);
            }
        });
    }

    /**
     * 请求一页数据。
     *
     * @return 卡片列表; 返回 null 表示页码越界(已到末页), 调用方应转成空列表
     */
    private static List<HomeRecommendItem> fetch(int cid, int page, boolean withTitle) throws Exception {
        String body = "sortField=rankScore&channelId=" + cid + "&page=" + page;
        String json = post(API, body);
        JSONObject root = new JSONObject(json);
        int result = root.optInt("result", -1);
        if (result != 0) {
            if (page > 1 && result == RESULT_NO_MORE) {
                // 越界: 记住"上一页"就是末页, 后续对该 cid 的更大页码直接短路
                synchronized (LOCK) {
                    Integer last = LAST_PAGE.get(cid);
                    int bound = page - 1;
                    if (last == null || bound < last.intValue()) {
                        LAST_PAGE.put(cid, Integer.valueOf(bound));
                    }
                }
                Log.i(TAG, "cid=" + cid + " page=" + page + " beyond end (result=" + result + ")");
                return null;
            }
            throw new IllegalStateException("acfun result=" + result + " msg=" + root.optString("msg"));
        }

        JSONArray feed = root.optJSONArray("feed");
        int feedLen = feed == null ? 0 : feed.length();

        // 用 total / 每页条数反推末页, 让越界请求在本地被拦下, 不必真的打到接口
        if (page == 1 && feedLen > 0) {
            long total = root.optLong("total", 0L);
            if (total > 0) {
                int lastPage = (int) ((total + feedLen - 1) / feedLen);
                synchronized (LOCK) {
                    LAST_PAGE.put(cid, Integer.valueOf(lastPage));
                }
            }
        }

        if (feedLen == 0) {
            // result=0 但没数据, 同样视为到底
            if (page > 1) {
                synchronized (LOCK) {
                    LAST_PAGE.put(cid, Integer.valueOf(page - 1));
                }
                return null;
            }
        }

        ArrayList<HomeRecommendItem> out = new ArrayList<HomeRecommendItem>();
        if (withTitle) {
            HomeRecommendItem title = new HomeRecommendItem();
            title.setViewType(2);
            title.setTitleId(TITLE_ID_BASE + cid);
            title.setTitleStr(channelName(root, cid));
            out.add(title);
        }

        int n = 0;
        for (int i = 0; i < feedLen; i++) {
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

        if (!withTitle && page > 1 && n > 0) {
            synchronized (LOCK) {
                Integer hi = HIGHEST.get(cid);
                if (hi == null || page > hi.intValue()) {
                    HIGHEST.put(cid, Integer.valueOf(page));
                }
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
        // 卡片里日期与 UP 名同行, 宽度有限: 只到日, 既消除跨年歧义又不会被裁掉
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
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
