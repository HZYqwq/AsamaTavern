package com.asamatavern;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 界面壳 + 桥接。
 *
 * 关键点：@JavascriptInterface 的方法跑在 WebView 的 JS 线程上，同步执行。
 * 任何网络请求都不能在里面做，否则整个界面卡死 —— ASAMA 6.x 早期就栽过这个。
 * 所以除了纯读写的轻操作，全部走 startOp 异步通道。
 */
public class MainActivity extends Activity {

    private WebView web;
    private Api api;
    private Img img;
    private Store store;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int nextOpId = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        api = new Api();
        img = new Img();
        restoreApiConfig();

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v,
                    android.webkit.ValueCallback<android.net.Uri[]> cb,
                    FileChooserParams p) {
                return MainActivity.this.onShowFileChooser(v, cb, p);
            }
        });
        web.addJavascriptInterface(new Live(), "AT");
        web.setBackgroundColor(0xFFFFFFFF);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    /** 上次填过的地址和模型从设置里恢复；key 从加密保管箱取。 */
    private void restoreApiConfig() {
        String base = "", model = "deepseek-chat";
        try {
            JSONObject st = new JSONObject(store.loadSettings());
            base = st.optString("base", "");
            model = st.optString("model", model);
        } catch (Exception ignored) {
        }
        String key = "";
        try {
            JSONObject v = new JSONObject(store.loadVault());
            key = v.optString("apiKey", "");
        } catch (Exception ignored) {
        }
        api.configure(base, key, model);

        String ibase = "https://api.minimaxi.com", imodel = "image-01", ikey = "";
        try {
            JSONObject st = new JSONObject(store.loadSettings());
            ibase = st.optString("imgBase", ibase);
            imodel = st.optString("imgModel", imodel);
        } catch (Exception ignored) {
        }
        try {
            JSONObject v = new JSONObject(store.loadVault());
            ikey = v.optString("imgKey", "");
        } catch (Exception ignored) {
        }
        img.configure(ibase, ikey, imodel);

        try {
            JSONObject st = new JSONObject(store.loadSettings());
            searchBase = st.optString("searchBase", "");
        } catch (Exception ignored) {
        }
        try {
            JSONObject v = new JSONObject(store.loadVault());
            searchKey = v.optString("searchKey", "");
        } catch (Exception ignored) {
        }
    }

    private String searchBase = "";
    private String searchKey = "";

    // ------------------------------------------------------------ 文件选择
    private static final int REQ_FILE = 5803;
    private android.webkit.ValueCallback<android.net.Uri[]> fileCb;

    /**
     * 网页里 <input type="file"> 必须靠这个回调才能弹出选择器。
     * 漏了它，点上传按钮会毫无反应 —— 6.8 早期就栽过。
     */
    /**
     * 注意：这不是 override —— onShowFileChooser 属于 WebChromeClient，
     * 不属于 Activity。这里只是个普通方法，由下面那个内部类转调过来。
     */
    public boolean onShowFileChooser(WebView view,
            android.webkit.ValueCallback<android.net.Uri[]> cb,
            android.webkit.WebChromeClient.FileChooserParams params) {
        if (fileCb != null) {
            fileCb.onReceiveValue(null);
        }
        fileCb = cb;
        try {
            android.content.Intent i = params.createIntent();
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_FILE);
        } catch (Throwable e) {
            fileCb = null;
            toast("打不开文件选择器");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ 抓网页

    /** 抓一个链接的正文，剥掉标签。给「＋ → 链接」用。 */
    @JavascriptInterface
    public String fetchUrl(String url) {
        if (url == null || !url.startsWith("http")) {
            return Api.err("链接要以 http:// 或 https:// 开头。");
        }
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url.trim()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile");
            int code = c.getResponseCode();
            if (code >= 400) {
                return Api.err(Cn.http(code, ""));
            }
            java.io.InputStream in = c.getInputStream();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            String html = sb.toString();
            // 去掉脚本、样式、标签
            html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
            html = html.replaceAll("(?s)<[^>]+>", " ");
            html = html.replace("&nbsp;", " ").replace("&amp;", "&")
                       .replace("&lt;", "<").replace("&gt;", ">")
                       .replace("&quot;", "\"");
            html = html.replaceAll("\\s+", " ").trim();
            if (html.length() > 12000) {
                html = html.substring(0, 12000);
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ok", true);
            o.put("text", html);
            return o.toString();
        } catch (Exception e) {
            return Api.err(Cn.net(e));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ 导入 / 导出
    private static final int REQ_SAVE = 5801;
    private static final int REQ_OPEN = 5802;
    private String pendingBody;

    /** 让用户选个地方存文件。走系统选择器，不需要任何存储权限。 */
    @JavascriptInterface
    public String exportText(String filename, String content) {
        pendingBody = content == null ? "" : content;
        final String name = (filename == null || filename.trim().isEmpty())
                ? "asamatavern.txt" : filename.trim();
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    i.setType("text/plain");
                    i.putExtra(android.content.Intent.EXTRA_TITLE, name);
                    startActivityForResult(i, REQ_SAVE);
                } catch (Throwable e) {
                    toast("这台设备没有文件选择器");
                }
            }
        });
        return "{\"ok\":true}";
    }

    /** 让用户挑一个文本文件读进来。 */
    @JavascriptInterface
    public String importText() {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    i.setType("text/plain");
                    startActivityForResult(i, REQ_OPEN);
                } catch (Throwable e) {
                    toast("这台设备没有文件选择器");
                }
            }
        });
        return "{\"ok\":true}";
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write((pendingBody == null ? "" : pendingBody).getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    toast("已保存");
                } catch (Throwable e) {
                    toast("保存失败：" + e);
                }
            }
            pendingBody = null;
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(
                        resultCode == RESULT_OK && data != null ? data.getDataString() == null
                                ? null : new android.net.Uri[]{data.getData()} : null);
                fileCb = null;
            }
            return;
        }
        if (requestCode == REQ_OPEN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    br.close();
                    final String text = sb.toString();
                    ui.post(new Runnable() {
                        public void run() {
                            if (web == null) {
                                return;
                            }
                            try {
                                web.evaluateJavascript("window.__atImport("
                                        + org.json.JSONObject.quote(text) + ")", null);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable e) {
                    toast("读取失败：" + e);
                }
            }
            return;
        }
    }

    // ---------------------------------------------------------------- 后台保活

    /**
     * 切出去时不要冻住 WebView 的 JS 定时器 —— 正在跑的生成还要靠回调回来。
     * 6.8 上这里没处理，表现就是"后台不生成还报错"。
     */
    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (web != null) {
                web.onResume();
                web.resumeTimers();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            if (web != null) {
                web.onPause();
            }
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    // ---------------------------------------------------------------- 异步通道

    private void startOp(final int id, final String op, final JSONArray args) {
        pool.execute(new Runnable() {
            public void run() {
                String out;
                try {
                    out = runOp(op, args);
                } catch (Throwable e) {
                    out = Api.err("操作" + op + "出错：" + e);
                }
                deliver(id, out);
            }
        });
    }

    private void deliver(final int id, final String json) {
        ui.post(new Runnable() {
            public void run() {
                if (web == null) {
                    return;
                }
                String js = "window.__atDone(" + id + ","
                        + JSONObject.quote(json == null ? "{}" : json) + ")";
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 把所有耗时或联网的操作集中在这里。 */
    private String runOp(String op, JSONArray a) {
        if ("testConnection".equals(op)) {
            return api.testConnection();
        }
        if ("listModels".equals(op)) {
            return api.listModels();
        }
        if ("judgeImage".equals(op)) {
            return api.judge(a.optString(0, ""), a.optString(1, ""), a.optString(2, "{}"));
        }
        if ("search".equals(op)) {
            return Api.search(a.optString(0, ""), searchBase, searchKey);
        }
        if ("generateImage".equals(op)) {
            return img.generate(a.optString(0, ""), a.optString(1, ""));
        }
        if ("generate".equals(op)) {
            return api.generate(a.optString(0, ""), a.optString(1, ""),
                    a.optString(2, ""), a.optString(3, ""), a.optString(4, "{}"));
        }
        return Api.err("未知操作：" + op
                + "（已注册：testConnection、listModels、generate、generateImage、search、judgeImage）");
    }

    // ---------------------------------------------------------------- 桥接

    /**
     * 暴露给网页的方法集合。
     *
     * 必须 public：桥接是靠反射找方法的，非 public 的内部类会报 Method not found。
     * 另外每个方法的形参个数要和 JS 侧传的实参个数一致 —— 桥接不做默认值补齐。
     */
    public class Live {

        @JavascriptInterface
        public String startOp(String op, String argsJson) {
            try {
                int id = nextOpId++;
                JSONArray args = new JSONArray(argsJson == null ? "[]" : argsJson);
                // 必须写全限定名：这个内部类自己也有个 startOp(String,String)，
                // 不限定就会解析到它自己身上，参数对不上
                MainActivity.this.startOp(id, op, args);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("id", id);
                return o.toString();
            } catch (Exception e) {
                return Api.err("无法启动操作：" + e);
            }
        }

        @JavascriptInterface
        public String presets() {
            return Api.presetsJson();
        }

        @JavascriptInterface
        public String settings() {
            return store.loadSettings();
        }

        @JavascriptInterface
        public String saveSettings(String json) {
            return ok(store.saveSettings(json));
        }

        @JavascriptInterface
        public String personas() {
            return store.loadPersonas();
        }

        @JavascriptInterface
        public String savePersonas(String json) {
            return ok(store.savePersonas(json));
        }

        @JavascriptInterface
        public String listChars() {
            return store.listChars();
        }

        @JavascriptInterface
        public String saveChar(String json) {
            return ok(store.saveChar(json));
        }

        @JavascriptInterface
        public String deleteChar(String id) {
            return ok(store.deleteChar(id));
        }

        @JavascriptInterface
        public String listChats(String charId) {
            return store.listChats(charId);
        }

        @JavascriptInterface
        public String loadChat(String charId, String chatId) {
            return store.loadChat(charId, chatId);
        }

        @JavascriptInterface
        public String saveChat(String charId, String chatId, String json) {
            return ok(store.saveChat(charId, chatId, json));
        }

        @JavascriptInterface
        public String storeInfo() {
            return store.info();
        }

        @JavascriptInterface
        public String fetchUrl(String url) {
            return MainActivity.this.fetchUrl(url);
        }

        @JavascriptInterface
        public String searchState() {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("base", searchBase);
                o.put("hasKey", !searchKey.isEmpty());
                o.put("keyHint", searchKey.length() > 8
                        ? (searchKey.substring(0, 4) + "…" + searchKey.substring(searchKey.length() - 4))
                        : (searchKey.isEmpty() ? "" : "已保存"));
                return o.toString();
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public synchronized String saveSearchConfig(String base, String key) {
            try {
                JSONObject st;
                try {
                    st = new JSONObject(store.loadSettings());
                } catch (Exception e) {
                    st = new JSONObject();
                }
                st.put("searchBase", base == null ? "" : base.trim());
                store.saveSettings(st.toString());
                searchBase = st.optString("searchBase", "");
                if (key != null && !key.trim().isEmpty()) {
                    JSONObject v;
                    try {
                        v = new JSONObject(store.loadVault());
                    } catch (Exception e) {
                        v = new JSONObject();
                    }
                    v.put("searchKey", key.trim());
                    if (!store.saveVault(v.toString())) {
                        return Api.err("搜索 Key 存不进加密保管箱。");
                    }
                    searchKey = key.trim();
                }
                return "{\"ok\":true}";
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public String imgState() {
            try {
                JSONObject v = new JSONObject(store.loadVault());
                String k = v.optString("imgKey", "");
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("base", img.base());
                o.put("model", img.model());
                o.put("hasKey", !k.isEmpty());
                o.put("keyHint", k.length() > 8
                        ? (k.substring(0, 4) + "…" + k.substring(k.length() - 4))
                        : (k.isEmpty() ? "" : "已保存"));
                return o.toString();
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public synchronized String saveImgConfig(String base, String model, String key) {
            try {
                JSONObject st;
                try {
                    st = new JSONObject(store.loadSettings());
                } catch (Exception e) {
                    st = new JSONObject();
                }
                st.put("imgBase", base == null ? "" : base.trim());
                st.put("imgModel", model == null ? "" : model.trim());
                store.saveSettings(st.toString());
                JSONObject v;
                try {
                    v = new JSONObject(store.loadVault());
                } catch (Exception e) {
                    v = new JSONObject();
                }
                if (key != null && !key.trim().isEmpty()) {
                    v.put("imgKey", key.trim());
                    if (!store.saveVault(v.toString())) {
                        return Api.err("画图 Key 存不进加密保管箱。");
                    }
                }
                img.configure(st.optString("imgBase", ""), v.optString("imgKey", ""),
                        st.optString("imgModel", ""));
                return "{\"ok\":true}";
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public String saveImage(String name, String dataUrl) {
            boolean okk = store.saveImage(name, dataUrl, false);
            return okk ? "{\"ok\":true}" : Api.err("图片落盘失败，检查存储空间。");
        }

        @JavascriptInterface
        public String readImage(String name) {
            String d = store.readImage(name, false);
            if (d == null) {
                return Api.err("这张图找不到了：" + name);
            }
            try {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("dataUrl", d);
                return o.toString();
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public String saveRef(String name, String dataUrl) {
            boolean okk = store.saveImage(name, dataUrl, true);
            return okk ? "{\"ok\":true}" : Api.err("参考图落盘失败。");
        }

        @JavascriptInterface
        public String listRefs() {
            return store.listRefs();
        }

        @JavascriptInterface
        public String readRef(String name) {
            String d = store.readImage(name, true);
            if (d == null) {
                return Api.err("参考图找不到了：" + name);
            }
            try {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("dataUrl", d);
                return o.toString();
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        @JavascriptInterface
        public String deleteRef(String name) {
            return ok(store.deleteRef(name));
        }

        @JavascriptInterface
        public String listWorlds() {
            return store.listWorlds();
        }

        @JavascriptInterface
        public String saveWorld(String json) {
            return ok(store.saveWorld(json));
        }

        @JavascriptInterface
        public String deleteWorld(String id) {
            return ok(store.deleteWorld(id));
        }



        @JavascriptInterface
        public String exportText(String filename, String content) {
            return MainActivity.this.exportText(filename, content);
        }

        @JavascriptInterface
        public String importText() {
            return MainActivity.this.importText();
        }

        /** 保存 API 配置：地址与模型进 settings，key 进加密保管箱。 */
        @JavascriptInterface
        public synchronized String saveApi(String base, String model, String key) {
            try {
                JSONObject st;
                try {
                    st = new JSONObject(store.loadSettings());
                } catch (Exception e) {
                    st = new JSONObject();
                }
                st.put("base", base == null ? "" : base.trim());
                st.put("model", model == null ? "" : model.trim());
                store.saveSettings(st.toString());

                JSONObject v;
                try {
                    v = new JSONObject(store.loadVault());
                } catch (Exception e) {
                    v = new JSONObject();
                }
                if (key != null && !key.trim().isEmpty()) {
                    v.put("apiKey", key.trim());
                    if (!store.saveVault(v.toString())) {
                        return Api.err("Key 存不进加密保管箱。可能是系统密钥库异常，重启 App 再试。");
                    }
                }
                api.configure(st.optString("base", ""), v.optString("apiKey", ""),
                        st.optString("model", ""));
                return "{\"ok\":true}";
            } catch (Exception e) {
                return Api.err("保存失败：" + e);
            }
        }

        /** key 只回一个打码版本，绝不把明文送进网页。 */
        @JavascriptInterface
        public String apiState() {
            try {
                JSONObject v = new JSONObject(store.loadVault());
                String k = v.optString("apiKey", "");
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("base", api.base());
                o.put("model", api.model());
                o.put("hasKey", !k.isEmpty());
                o.put("keyHint", k.length() > 8
                        ? (k.substring(0, 4) + "…" + k.substring(k.length() - 4))
                        : (k.isEmpty() ? "" : "已保存"));
                return o.toString();
            } catch (Exception e) {
                return Api.err(String.valueOf(e));
            }
        }

        private String ok(boolean b) {
            return b ? "{\"ok\":true}" : Api.err("写入失败，检查存储空间。");
        }
    }
}
