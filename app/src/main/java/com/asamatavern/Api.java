package com.asamatavern;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 直连大模型的 OpenAI 兼容客户端。
 *
 * 没有中转服务器 - 这个类就是原来 SillyTavern 的位置。所有请求从手机直接发出去。
 */
final class Api {

    /** 内置预设。用户也可以完全手填。 */
    static final String[][] PRESETS = {
        // 名称, 接口地址(不含 /chat/completions), 默认模型, 是否支持看图
        {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "0"},
        {"GLM 智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "1"},
        {"Kimi 月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-32k", "1"},
        {"Qwen 通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "1"},
    };

    private String base = "";
    private String key = "";
    private String model = "deepseek-chat";

    static class Resp {
        int code;
        String body = "";
        String error;

        boolean ok() {
            return error == null && code >= 200 && code < 300;
        }
    }

    void configure(String base, String key, String model) {
        if (base != null) {
            this.base = base.trim();
        }
        if (key != null) {
            this.key = key.trim();
        }
        if (model != null && !model.trim().isEmpty()) {
            this.model = model.trim();
        }
    }

    String base() {
        return base;
    }

    String model() {
        return model;
    }

    boolean hasKey() {
        return key != null && !key.isEmpty();
    }

    private String url(String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * @return HTTP 状态码与响应体；网络层失败时 code = -1 且 error 已是中文
     */
    private Resp post(String path, JSONObject body, int timeoutSec) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url(path)).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutSec * 1000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            byte[] raw = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(raw.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(raw);
                os.flush();
            } finally {
                os.close();
            }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(in);
            if (r.code >= 400) {
                r.error = Cn.http(r.code, r.body);
            }
        } catch (Exception e) {
            r.code = -1;
            r.error = Cn.net(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    private Resp get(String path, int timeoutSec) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url(path)).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutSec * 1000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(in);
            if (r.code >= 400) {
                r.error = Cn.http(r.code, r.body);
            }
        } catch (Exception e) {
            r.code = -1;
            r.error = Cn.net(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 能力

    /** 拉模型列表，给用户挑。 */
    String listModels() {
        String bad = Cn.config(base, key, "x");
        if (bad != null) {
            return err(bad);
        }
        Resp r = get("/models", 30);
        if (!r.ok()) {
            return err(r.error);
        }
        try {
            JSONObject j = new JSONObject(r.body);
            JSONArray data = j.optJSONArray("data");
            if (data == null) {
                return err("这个地址没有返回模型列表。可能是它不支持 /models，"
                        + "那就手动在设置里填模型名。");
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                String id = m.optString("id", "");
                if (!id.isEmpty()) {
                    out.put(id);
                }
            }
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("models", out);
            return res.toString();
        } catch (Exception e) {
            return err("模型列表解析不了：" + e.getMessage());
        }
    }

    /**
     * 自检：发一句最短的话，确认真能出字。
     * @return {ok, ms, text, promptTokens, completionTokens}
     */
    String testConnection() {
        String bad = Cn.config(base, key, model);
        if (bad != null) {
            return err(bad);
        }
        long t0 = System.currentTimeMillis();
        JSONArray msgs = new JSONArray();
        try {
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "只回复两个字：可以");
            msgs.put(m);
        } catch (Exception ignored) {
        }
        JSONObject test = new JSONObject();
        try {
            test.put("maxTokens", 24);
            test.put("temperature", 0.1);
        } catch (Exception ignored) {
        }
        Resp r = chat(msgs, test);
        long ms = System.currentTimeMillis() - t0;
        if (!r.ok()) {
            return err(r.error);
        }
        try {
            JSONObject j = new JSONObject(r.body);
            JSONArray choices = j.optJSONArray("choices");
            String text = "";
            if (choices != null && choices.length() > 0) {
                text = choices.getJSONObject(0).optJSONObject("message")
                        .optString("content", "");
            }
            JSONObject usage = j.optJSONObject("usage");
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("ms", ms);
            res.put("text", text.trim());
            res.put("model", j.optString("model", model));
            res.put("promptTokens", usage == null ? 0 : usage.optInt("prompt_tokens", 0));
            res.put("completionTokens", usage == null ? 0 : usage.optInt("completion_tokens", 0));
            return res.toString();
        } catch (Exception e) {
            return err("返回内容解析不了：" + e.getMessage());
        }
    }

    /**
     * 一次对话。
     * @param msgs OpenAI 格式的 messages 数组
     */
    Resp chat(JSONArray msgs, JSONObject params) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("messages", msgs);
            body.put("max_tokens", params.optInt("maxTokens", 1024));
            body.put("temperature", params.optDouble("temperature", 0.9));
            body.put("top_p", params.optDouble("topP", 1.0));
            double fp = params.optDouble("freqPen", 0.0);
            double pp = params.optDouble("presPen", 0.0);
            if (fp != 0.0) {
                body.put("frequency_penalty", fp);
            }
            if (pp != 0.0) {
                body.put("presence_penalty", pp);
            }
            // 深度思考：各家拼写不一样，能塞的都塞上，不认识的一方会忽略
            if (params.optBoolean("deepThinking", false)) {
                body.put("reasoning_effort", "medium");
            }
            body.put("stream", false);
        } catch (Exception e) {
            Resp r = new Resp();
            r.code = -1;
            r.error = "请求体构造失败：" + e.getMessage();
            return r;
        }
        return post("/chat/completions", body, 180);
    }

    /**
     * 完整对话。
     * @param historyJson 历史消息数组 [{"isUser":bool,"mes":"..."}]，按顺序放进来
     * @param system      系统提示词
     * @param prefix      前置词：拼在用户这一句之前
     */
    String generate(String system, String historyJson, String prefix,
                    String userText, String paramsJson) {
        String bad = Cn.config(base, key, model);
        if (bad != null) {
            return err(bad);
        }
        JSONObject params;
        try {
            params = new JSONObject(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            params = new JSONObject();
        }
        int ctx = params.optInt("ctxLen", 20);
        JSONArray msgs = new JSONArray();
        try {
            if (system != null && !system.trim().isEmpty()) {
                JSONObject sy = new JSONObject();
                sy.put("role", "system");
                sy.put("content", system);
                msgs.put(sy);
            }
            JSONArray hist = new JSONArray(historyJson == null ? "[]" : historyJson);
            int start = Math.max(0, hist.length() - ctx);
            for (int i = start; i < hist.length(); i++) {
                JSONObject h = hist.getJSONObject(i);
                JSONObject m = new JSONObject();
                m.put("role", h.optBoolean("isUser", false) ? "user" : "assistant");
                m.put("content", h.optString("mes", ""));
                msgs.put(m);
            }
            String pre = prefix == null ? "" : prefix.trim();
            String u = userText == null ? "" : userText;
            JSONObject last = new JSONObject();
            last.put("role", "user");
            last.put("content", pre.isEmpty() ? u : (pre + "\n" + u));
            msgs.put(last);
        } catch (Exception ignored) {
        }

        Resp r = chat(msgs, params);
        if (!r.ok()) {
            return err(r.error);
        }
        try {
            JSONObject j = new JSONObject(r.body);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return err("对方没有返回任何内容。可能是这个模型名不对，"
                        + "换一个试试。");
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            String text = msg == null ? "" : msg.optString("content", "");
            // 有些模型把思考放在 reasoning_content 里
            String reasoning = msg == null ? "" : msg.optString("reasoning_content", "");
            JSONObject usage = j.optJSONObject("usage");
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("text", text);
            res.put("reasoning", reasoning);
            res.put("model", j.optString("model", model));
            res.put("promptTokens", usage == null ? 0 : usage.optInt("prompt_tokens", 0));
            res.put("completionTokens", usage == null ? 0 : usage.optInt("completion_tokens", 0));
            return res.toString();
        } catch (Exception e) {
            return err("返回内容解析不了：" + e.getMessage());
        }
    }

    /** 预设列表给界面用。 */
    static String presetsJson() {
        try {
            JSONArray out = new JSONArray();
            for (String[] p : PRESETS) {
                JSONObject o = new JSONObject();
                o.put("name", p[0]);
                o.put("base", p[1]);
                o.put("model", p[2]);
                o.put("vision", "1".equals(p[3]));
                out.put(o);
            }
            return out.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ------------------------------------------------------------ 联网搜索

    /**
     * 用 MiniMax 的 web_search 搜一次。
     *
     * 注意：这条能力只在 Anthropic 风格的 /anthropic/v1/messages 上有效，
     * OpenAI 风格的端点上声明工具会被静默忽略（返回的 usage 还是十几个 token，
     * 说明它压根没搜）。这是 6.8 上实测出来的。
     *
     * 搜索用的地址和 Key 与聊天分开配置，用户也可以不配 —— 那样联网功能就是关的。
     */
    static String search(String query, String sBase, String sKey) {
        if (sBase == null || sBase.trim().isEmpty() || sKey == null || sKey.trim().isEmpty()) {
            return err("还没配置联网搜索。去「设置 → 联网」填地址和 Key。");
        }
        if (query == null || query.trim().isEmpty()) {
            return err("搜索词是空的。");
        }
        HttpURLConnection c = null;
        try {
            String b = sBase.trim();
            while (b.endsWith("/")) {
                b = b.substring(0, b.length() - 1);
            }
            JSONObject body = new JSONObject();
            body.put("model", "MiniMax-M3");
            body.put("max_tokens", 2048);
            // 不强制的话它经常直接从记忆里答，一条来源都不给
            body.put("system",
                    "你必须先调用 web_search 获取最新资料，再基于搜索结果回答，"
                  + "并在末尾列出来源链接。不要凭记忆回答。");
            JSONArray msgs = new JSONArray();
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", query.trim());
            msgs.put(u);
            body.put("messages", msgs);
            JSONArray tools = new JSONArray();
            JSONObject tool = new JSONObject();
            tool.put("type", "web_search_20250305");
            tool.put("name", "web_search");
            tool.put("max_uses", 3);
            tools.put(tool);
            body.put("tools", tools);

            c = (HttpURLConnection) new URL(b + "/anthropic/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("x-api-key", sKey.trim());
            c.setRequestProperty("anthropic-version", "2023-06-01");
            byte[] raw = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(raw.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(raw);
                os.flush();
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            if (code >= 400) {
                return err(Cn.http(code, text));
            }
            JSONObject j = new JSONObject(text);
            JSONObject br = j.optJSONObject("base_resp");
            if (br != null && br.optInt("status_code", 0) != 0) {
                return err("搜索被拒：" + br.optString("status_msg", ""));
            }
            StringBuilder sb = new StringBuilder();
            int sources = 0;
            JSONArray content = j.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject blk = content.getJSONObject(i);
                    if ("text".equals(blk.optString("type"))) {
                        sb.append(blk.optString("text", ""));
                    } else if ("web_search_tool_result".equals(blk.optString("type"))) {
                        JSONArray r = blk.optJSONArray("content");
                        if (r != null) {
                            sources += r.length();
                        }
                    }
                }
            }
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("text", sb.toString().trim());
            res.put("sources", sources);
            if (sources == 0) {
                res.put("warn", "这次没有拿到来源，可能是搜索没触发，"
                        + "结果仅供参考。");
            }
            return res.toString();
        } catch (Exception e) {
            return err(Cn.net(e));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 把生成图和参考图放在一起，让模型判断像不像。
     *
     * 需要聊天模型本身能看图。不支持的话这里会失败，调用方按"未能验收"处理 ——
     * 不能因为判不了就当它合格。
     */
    String judge(String refData, String genData, String paramsJson) {
        String bad = Cn.config(base, key, model);
        if (bad != null) {
            return err(bad);
        }
        if (refData == null || refData.trim().isEmpty()) {
            return err("没有参考图可比");
        }
        if (genData == null || genData.trim().isEmpty()) {
            return err("没有生成图可比");
        }
        JSONObject params;
        try {
            params = new JSONObject(paramsJson == null ? "{}" : paramsJson);
        } catch (Exception e) {
            params = new JSONObject();
        }
        JSONArray msgs = new JSONArray();
        try {
            JSONArray parts = new JSONArray();
            JSONObject tx = new JSONObject();
            tx.put("type", "text");
            tx.put("text", "第一张是角色参考图，第二张是 AI 生成的图。\n"
                    + "请判断第二张里的人物在第一张的基础上，五官、脸型、发型、发色、"
                    + "体态气质有多接近。\n"
                    + "只输出一行 JSON，不要解释："
                    + "{\"similarity\": 0到100的整数, \"reason\": \"一句话说哪里不像\"}\n"
                    + "similarity 的含义：100 = 几乎同一个人，0 = 完全不是一个人。");
            parts.put(tx);
            JSONObject i1 = new JSONObject();
            i1.put("type", "image_url");
            JSONObject u1 = new JSONObject();
            u1.put("url", refData.trim());
            i1.put("image_url", u1);
            parts.put(i1);
            JSONObject i2 = new JSONObject();
            i2.put("type", "image_url");
            JSONObject u2 = new JSONObject();
            u2.put("url", genData.trim());
            i2.put("image_url", u2);
            parts.put(i2);
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", parts);
            msgs.put(um);
        } catch (Exception e) {
            return err("构造评判请求失败：" + e.getMessage());
        }
        JSONObject jp = new JSONObject();
        try {
            jp.put("maxTokens", params.optInt("maxTokens", 200));
            jp.put("temperature", 0.0);
        } catch (Exception ignored) {
        }
        Resp r = chat(msgs, jp);
        if (!r.ok()) {
            return err(r.error);
        }
        try {
            JSONObject j = new JSONObject(r.body);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return err("评判没有返回内容");
            }
            String text = choices.getJSONObject(0).optJSONObject("message")
                    .optString("content", "");
            String clean = text.replace("```json", "").replace("```", "");
            int x = clean.indexOf('{'), y = clean.lastIndexOf('}');
            if (x < 0 || y <= x) {
                JSONObject bad2 = new JSONObject();
                bad2.put("ok", false);
                bad2.put("error", "评判模型没给出百分比");
                bad2.put("raw", text.length() > 200 ? text.substring(0, 200) : text);
                return bad2.toString();
            }
            JSONObject v = new JSONObject(clean.substring(x, y + 1));
            int sim = v.optInt("similarity", -1);
            if (sim < 0) {
                return err("评判结果里没有 similarity 字段");
            }
            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("similarity", Math.max(0, Math.min(100, sim)));
            res.put("reason", v.optString("reason", ""));
            return res.toString();
        } catch (Exception e) {
            return err("评判结果解析失败：" + e.getMessage());
        }
    }

    static String err(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", message == null ? "未知错误" : message);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"未知错误\"}";
        }
    }

    /** 去掉首尾空白，给外部用。 */
    static List<String> wrap(String s) {
        List<String> l = new ArrayList<>();
        l.add(s);
        return l;
    }
}
