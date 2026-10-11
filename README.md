# AsamaTavern

**装好就能聊的本地 AI 酒馆。** 不搭服务器、不注册账号，填一个 API Key 就能开始角色扮演。

所有数据 —— 角色卡、世界书、聊天记录、密钥 —— 都在这台手机上。没有中转服务器，请求从手机直接发给你选的那家大模型。

---

## 它是什么

大多数 AI 角色扮演方案要你先在电脑上搭一套服务（SillyTavern 之类），手机只是个客户端。AsamaTavern 把这个关系翻过来：**手机就是全部**。

| | 传统方案 | AsamaTavern |
|---|---|---|
| 服务器 | 要自己搭 | 不需要 |
| 首次使用 | 装好一头雾水 | 4 步向导，两分钟开聊 |
| 界面 | 暗色、面板极多 | 白 + 浅蓝，只有 3 个页签 |
| 数据 | 在服务器上 | 全在本机 |
| 密钥 | 存在服务器配置里 | AES-GCM 加密存本机 |

---

## 功能

- **多角色扮演** —— HTML 消息渲染、重写 / 编辑 / 删除
- **世界书** —— 关键词命中才注入，省 token；支持常驻条目
- **前置词** —— 每轮自动拼在你发言前，用来固定场景与规则
- **生成参数** —— 温度、top_p、最大长度、上下文条数、频率 / 存在惩罚
- **聊天记录导入导出** —— 人能读的 `.txt` 格式，导出后可以再导回来
- **多服务商** —— DeepSeek / GLM / Kimi / Qwen，也能完全手填地址
- **连接自检** —— 地址、Key、模型三项逐个验证，全过才放行
- **全中文报错** —— 英文异常统一翻译成中文，并给出可操作的下一步

- **画图** —— MiniMax image-01，参考图驱动 + 相似度评审 + 不达标自动重画
- **联网搜索** —— 模型自己判断要不要查，搜完带资料重答
- **记忆宫殿** —— AI 自动归纳、自动配关键词、超限自动压缩
- **附件** —— 图片 / 文件 / 链接，一次性附着在当轮
- **多身份与多角色** —— 随时切换，互不干扰
- **深度思考** —— 思考过程与正文分离，可折叠查看
- **日志导出** —— 打包配置与运行日志，便于排错

---

## 编译

不需要 Gradle。手工管线，峰值内存不到 1 GB。

**准备**

- JDK 17+
- Android SDK build-tools（含 `aapt2` / `d8` / `zipalign` / `apksigner`）
- Python 3.8+

**改路径**

编辑 `tools/build_apk.py` 顶部：

```python
SDK = Path(r'D:\deepseek\sdk')   # 你的 Android SDK
JAVA_HOME = Path('D:/')          # 你的 JDK
```

**构建**

```bash
python -X utf8 tools/build_apk.py
```

产物在 `dist/AsamaTavern-<版本>.apk`。首次构建会自动生成一个 debug 签名用的 keystore。

---

## 项目结构

```
app/src/main/
  AndroidManifest.xml
  java/com/asamatavern/
    MainActivity.java   界面壳、JS 桥接、异步任务通道
    Api.java            直连大模型（OpenAI 兼容）
    Cn.java             中文错误翻译层
    Store.java          本地存储 + AES-GCM 加密保管箱
  assets/www/index.html 整个界面
tools/
  build_apk.py          无 Gradle 构建
  check_secrets.py      提交前密钥扫描
```

---

## 两条设计约定

**1. 桥接方法必须显式调用。**

- 把方法摘下来再调（`var f = AT.foo; f()`）会丢注入绑定，报 `non-injected object`
- 实参个数必须和 Java 形参一致，多传一个就报 `Method not found`

所以 `index.html` 里的 `call()` 是显式列举的 `switch`，不要"优化"成动态分发。

**2. 网络请求一律走 `startOp`。**

`@JavascriptInterface` 的方法跑在 WebView 的 JS 线程上、同步执行。在里面对外发请求会把整个界面冻住。耗时操作统一交给 `startOp`，结果通过 `window.__atDone(id, json)` 回传。

---

## 参与

欢迎提 Issue 和 PR。提交前请跑一遍：

```bash
python -X utf8 tools/check_secrets.py
```

它会扫描仓库里有没有混进真实密钥或私人地址。**这是硬性要求** —— 一旦密钥进了 Git 历史，删掉文件也没用。

---

## 许可

MIT，见 [LICENSE](LICENSE)。

---

## 免责

本项目只提供客户端。模型输出由你所选的服务商生成，内容与合规性由你和该服务商负责。请遵守当地法律法规与各服务商的使用条款。
