package com.asamatavern;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 画图。默认走 MiniMax 的 image-01，地址和 Key 都由用户自己填 —— 开源版
 * 不内置任何凭据，也不内置参考图。
 *
 * 参考图通过 subject_reference 送进去。纯文字描述人物长相太不稳定，给张图
 * 让模型照着画是唯一可靠的办法。
 */
final class Img {

    private String base = "https://api.minimaxi.com";
    private String key = "";
    private String model = "image-01";

    void configure(String base, String key, String model) {
        if (base != null && !base.trim().isEmpty()) {
            this.base = base.trim();
        }
        if (key != null && !key.trim().isEmpty()) {
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

    boolean ready() {
        return key != null && !key.isEmpty();
    }

    private String url(String path) {
        String b = base;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }

    /**
     * @param prompt  画面描述
     * @param refData 参考图的 data URL，没有就传空
     * @return {ok, image:"data:image/jpeg;base64,..."}
     */
    String generate(String prompt, String refData) {
        if (!ready()) {
            return Api.err("还没填画图的 API Key。去「设置 → 画图」填一下。");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return Api.err("画面描述是空的。");
        }
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            // 权威提示词：明确要求以参考图为准。只给图不说这句话，
            // 模型会把参考图当"灵感"而不是"依据"。
            String p = prompt.trim();
            if (refData != null && !refData.trim().isEmpty()) {
                p = "请严格参照所提供参考图中人物的五官、脸型、发型、发色与整体气质来绘制，"
                  + "保持角色外貌一致，不要改变人物长相。\n\n画面内容：\n" + p;
            }
            body.put("prompt", p);
            body.put("n", 1);
            body.put("response_format", "base64");
            body.put("aspect_ratio", "1:1");
            if (refData != null && !refData.trim().isEmpty()) {
                String raw = refData.trim();
                String mime = "image/jpeg";
                if (raw.startsWith("data:")) {
                    int semi = raw.indexOf(';');
                    if (semi > 5) {
                        mime = raw.substring(5, semi);
                    }
                    int comma = raw.indexOf(',');
                    if (comma > 0) {
                        raw = raw.substring(comma + 1);
                    }
                }
                JSONArray refs = new JSONArray();
                JSONObject r = new JSONObject();
                r.put("type", "character");
                r.put("image_file", raw);
                refs.put(r);
                body.put("subject_reference", refs);
            }

            c = (HttpURLConnection) new URL(url("/v1/image_generation")).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
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
                return Api.err(Cn.http(code, text));
            }
            JSONObject j = new JSONObject(text);
            JSONObject br = j.optJSONObject("base_resp");
            if (br != null && br.optInt("status_code", 0) != 0) {
                int sc = br.optInt("status_code");
                String msg = br.optString("status_msg", "");
                if (sc == 1002 || sc == 1004) {
                    return Api.err("画图额度不够或 Key 没通过（" + msg + "）。"
                            + "去 MiniMax 后台看看余额。");
                }
                if (sc == 2013) {
                    return Api.err("参数被拒：" + msg
                            + "\n\n常见原因是这个 Key 没开通 image-01，"
                            + "或者参考图格式不对。");
                }
                return Api.err("画图失败：" + msg + "（" + sc + "）");
            }
            JSONObject data = j.optJSONObject("data");
            JSONArray urls = data == null ? null : data.optJSONArray("image_urls");
            if (urls == null || urls.length() == 0) {
                JSONArray b64 = data == null ? null : data.optJSONArray("image_base64");
                if (b64 != null && b64.length() > 0) {
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("image", "data:image/jpeg;base64," + b64.getString(0));
                    res.put("usedRefs", refData != null && !refData.trim().isEmpty());
                    res.put("model", model);
                    return res.toString();
                }
                return Api.err("对方没返回图片。");
            }
            JSONObject res = new JSONObject();
            res.put("ok", true);
            // 返回的是 URL，取回来转成 data URL，方便直接显示与落盘
            String imgUrl = urls.getString(0);
            res.put("image", downloadAsDataUrl(imgUrl));
            res.put("usedRefs", refData != null && !refData.trim().isEmpty());
            res.put("model", model);
            return res.toString();
        } catch (Exception e) {
            return Api.err(Cn.net(e));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String downloadAsDataUrl(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return "data:image/jpeg;base64,"
                    + android.util.Base64.encodeToString(bos.toByteArray(),
                            android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return u;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
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
}
