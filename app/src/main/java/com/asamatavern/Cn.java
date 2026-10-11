package com.asamatavern;

/**
 * 所有对外的报错都从这里出去，保证是简体中文、并且带可操作的下一步。
 *
 * 英文原文一律不直接displayed给用户 - HTTP 状态码、套接字异常、各家 API 的
 * JSON 错误体全部收敛到这几个分支里。
 */
final class Cn {

    private Cn() {}

    /** 按异常类型翻译网络层错误。 */
    static String net(Exception e) {
        String n = e.getClass().getSimpleName();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String low = msg.toLowerCase();

        if (n.contains("UnknownHost")) {
            return "域名解析不了，检查接口地址有没有拼错，以及手机能不能上网。";
        }
        if (n.contains("SocketTimeout") || low.contains("timeout")) {
            return "连上了但一直没回应（超时）。可能是网络慢，或者这个模型在排队，稍后再试。";
        }
        if (n.contains("ConnectException") || low.contains("failed to connect")
                || low.contains("connection refused")) {
            return "连不上这个地址。检查接口地址是否正确、端口有没有写错。";
        }
        if (n.contains("SSL") || n.contains("Cert")) {
            return "HTTPS 证书有问题。确认地址是不是 https，以及网络有没有被劫持。";
        }
        if (low.contains("cleartext")) {
            return "这是 http 明文地址，被系统拦下了。改用 https。";
        }
        return "网络出错：" + (msg.isEmpty() ? n : msg);
    }

    /** 按 HTTP 状态码翻译。 */
    static String http(int code, String body) {
        String tail = detail(body);
        switch (code) {
            case 400:
                return "请求被拒绝（400）。多半是参数不合法。" + tail;
            case 401:
                return "API Key 不对或已失效（401）。检查设置里的 Key 有没有复制完整，"
                        + "注意别把前后的空格一起粘进去。" + tail;
            case 403:
                return "没有权限（403）。这个 Key 可能没开通该模型，或者被限制了。" + tail;
            case 404:
                return "接口地址不存在（404）。检查地址是不是多写或少写了 /v1。" + tail;
            case 413:
                return "内容太长（413）。把上下文条数调小一点试试。";
            case 422:
                return "参数格式不对（422）。" + tail;
            case 429:
                return "请求太频繁或额度用完了（429）。等一会儿再试，或者去后台看看余额。";
            case 500:
            case 502:
            case 503:
            case 504:
                return "对方服务器出错（" + code + "）。不是你的问题，稍后再试。" + tail;
            default:
                return "请求失败（HTTP " + code + "）。" + tail;
        }
    }

    /** 从各家五花八门的错误体里抠出人能读的那一句。 */
    static String detail(String body) {
        if (body == null) {
            return "";
        }
        String b = body.trim();
        if (b.isEmpty()) {
            return "";
        }
        String s = pick(b, "\"message\"");
        if (s == null) {
            s = pick(b, "\"error_msg\"");
        }
        if (s == null) {
            s = pick(b, "\"msg\"");
        }
        if (s == null) {
            s = pick(b, "\"detail\"");
        }
        if (s == null && b.length() < 160) {
            s = b;
        }
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.length() > 200) {
            s = s.substring(0, 200) + "…";
        }
        return "\n\n原文：" + s;
    }

    private static String pick(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int c = json.indexOf(':', i + key.length());
        if (c < 0) {
            return null;
        }
        int q = json.indexOf('"', c + 1);
        if (q < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char nx = json.charAt(k + 1);
                if (nx == 'n') {
                    sb.append(' ');
                } else if (nx == '"' || nx == '\\' || nx == '/') {
                    sb.append(nx);
                } else {
                    sb.append(nx);
                }
                k++;
                continue;
            }
            if (ch == '"') {
                break;
            }
            sb.append(ch);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /** 配置类校验，在发请求之前就拦住。 */
    static String config(String base, String key, String model) {
        if (base == null || base.trim().isEmpty()) {
            return "还没填接口地址。去「设置 → 模型」选一家预设，或者手动填一个。";
        }
        if (key == null || key.trim().isEmpty()) {
            return "还没填 API Key。去「设置 → 模型」把 Key 贴上。";
        }
        if (model == null || model.trim().isEmpty()) {
            return "还没选模型。连上之后从列表里挑一个。";
        }
        String b = base.trim();
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            return "接口地址要以 http:// 或 https:// 开头。你填的是：" + b;
        }
        return null;
    }
}
