package com.asamatavern;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 全部数据落在 App 私有目录，不经过任何服务器。
 *
 *   files/settings.json   界面设置（不含密钥）
 *   files/vault.bin       AES-GCM 加密：API Key、网页密码
 *   files/chars/          角色卡
 *   files/persona.json    主人公
 *   files/chats/          会话
 */
final class Store {

    private static final String KEY_ALIAS = "asamatavern_vault";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final File root;
    private final File chars;
    private final File chats;
    private final File worlds;
    private final File images;
    private final File refs;

    Store(Context ctx) {
        root = ctx.getFilesDir();
        chars = new File(root, "chars");
        chats = new File(root, "chats");
        worlds = new File(root, "worlds");
        images = new File(root, "images");
        refs = new File(root, "refs");
        chars.mkdirs();
        chats.mkdirs();
        worlds.mkdirs();
        images.mkdirs();
        refs.mkdirs();
    }

    File root() {
        return root;
    }

    // ------------------------------------------------------------- 普通读写

    private String read(File f) {
        if (!f.exists()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean write(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String loadSettings() {
        String s = read(new File(root, "settings.json"));
        return s == null ? "{}" : s;
    }

    boolean saveSettings(String json) {
        return write(new File(root, "settings.json"), json == null ? "{}" : json);
    }

    /** 主人公可以有多份（不同身份）。整份列表存一个文件，简单可靠。 */
    String loadPersonas() {
        String s = read(new File(root, "personas.json"));
        return s == null ? "[]" : s;
    }

    boolean savePersonas(String json) {
        return write(new File(root, "personas.json"), json == null ? "[]" : json);
    }

    // ------------------------------------------------------------- 世界书

    String listWorlds() {
        JSONArray out = new JSONArray();
        File[] fs = worlds.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) {
                    continue;
                }
                try {
                    out.put(new JSONObject(read(f)));
                } catch (Exception ignored) {
                }
            }
        }
        return out.toString();
    }

    boolean saveWorld(String json) {
        try {
            JSONObject w = new JSONObject(json);
            String id = w.optString("id", "");
            if (id.isEmpty()) {
                id = "w" + System.currentTimeMillis();
            }
            w.put("id", id);
            return write(new File(worlds, safe(id) + ".json"), w.toString());
        } catch (Exception e) {
            return false;
        }
    }

    boolean deleteWorld(String id) {
        return new File(worlds, safe(id) + ".json").delete();
    }

    // ------------------------------------------------------------- 图片

    /** 把一张 data URL 存成文件。只返回文件名，不把 base64 塞进聊天记录。 */
    boolean saveImage(String name, String dataUrl, boolean isRef) {
        try {
            String b64 = dataUrl == null ? "" : dataUrl;
            int comma = b64.indexOf(',');
            if (comma >= 0) {
                b64 = b64.substring(comma + 1);
            }
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            File dir = isRef ? refs : images;
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(dir, safe(name)));
            try {
                out.write(raw);
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String readImage(String name, boolean isRef) {
        try {
            File f = new File(isRef ? refs : images, safe(name));
            if (!f.exists()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            return "data:image/jpeg;base64,"
                    + android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 参考图列表：文件名 + 大小，给界面挑。 */
    String listRefs() {
        JSONArray out = new JSONArray();
        File[] fs = refs.listFiles();
        if (fs != null) {
            for (File f : fs) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("bytes", f.length());
                    out.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        return out.toString();
    }

    boolean deleteRef(String name) {
        return new File(refs, safe(name)).delete();
    }

    // ------------------------------------------------------------- 通用文本

    /** 导出用：写到 cacheDir，之后交给系统文件选择器。 */
    java.io.File cacheFile(String name) {
        return new java.io.File(root, "export_" + safe(name));
    }

    // ------------------------------------------------------------- 角色卡

    String listChars() {
        JSONArray out = new JSONArray();
        File[] fs = chars.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) {
                    continue;
                }
                try {
                    out.put(new JSONObject(read(f)));
                } catch (Exception ignored) {
                }
            }
        }
        return out.toString();
    }

    boolean saveChar(String json) {
        try {
            JSONObject c = new JSONObject(json);
            String id = c.optString("id", "");
            if (id.isEmpty()) {
                id = "c" + System.currentTimeMillis();
            }
            c.put("id", id);
            // 防目录穿越
            id = id.replaceAll("[^A-Za-z0-9_\\-]", "_");
            return write(new File(chars, id + ".json"), c.toString());
        } catch (Exception e) {
            return false;
        }
    }

    boolean deleteChar(String id) {
        return new File(chars, safe(id) + ".json").delete();
    }

    // ------------------------------------------------------------- 会话

    private String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private File chatFile(String charId, String chatId) {
        File d = new File(chats, safe(charId));
        d.mkdirs();
        return new File(d, safe(chatId) + ".json");
    }

    String listChats(String charId) {
        JSONArray out = new JSONArray();
        File[] fs = new File(chats, safe(charId)).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) {
                    continue;
                }
                try {
                    JSONObject c = new JSONObject(read(f));
                    JSONObject o = new JSONObject();
                    o.put("id", f.getName().replace(".json", ""));
                    o.put("count", c.optJSONArray("messages") == null
                            ? 0 : c.optJSONArray("messages").length());
                    o.put("updated", f.lastModified());
                    out.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        return out.toString();
    }

    String loadChat(String charId, String chatId) {
        String s = read(chatFile(charId, chatId));
        return s == null ? "{\"messages\":[]}" : s;
    }

    boolean saveChat(String charId, String chatId, String json) {
        return write(chatFile(charId, chatId), json);
    }

    // ------------------------------------------------------------- 加密保管箱

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** 把整份密钥表加密写入 vault.bin。iv 拼在密文前面。 */
    boolean saveVault(String json) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(json.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            FileOutputStream out = new FileOutputStream(new File(root, "vault.bin"));
            try {
                out.write(all);
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String loadVault() {
        File f = new File(root, "vault.bin");
        if (!f.exists()) {
            return "{}";
        }
        try {
            byte[] all = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < all.length) {
                int n = in.read(all, off, all.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] pt = c.doFinal(all, 12, all.length - 12);
            return new String(pt, "UTF-8");
        } catch (Exception e) {
            // 换过签名或者清了 keystore 就会解不开，这时当作空的
            return "{}";
        }
    }

    /** 给「设置 → 存储」显示用。 */
    String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("root", root.getAbsolutePath());
            o.put("chars", chars.listFiles() == null ? 0 : chars.listFiles().length);
            o.put("vault", new File(root, "vault.bin").exists());
            o.put("worlds", worlds.listFiles() == null ? 0 : worlds.listFiles().length);
            o.put("images", images.listFiles() == null ? 0 : images.listFiles().length);
            o.put("refs", refs.listFiles() == null ? 0 : refs.listFiles().length);
            long bytes = 0;
            File[] fs = root.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    bytes += f.isFile() ? f.length() : 0;
                }
            }
            o.put("bytes", bytes);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
