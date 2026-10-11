"""一键把 AsamaTavern 整理成可以推到 GitHub 的仓库。

做的事，按顺序：

  1. 检查 git 是否可用
  2. 跑密钥扫描，**不干净就直接停**
  3. 检查有没有 APK / keystore / 构建产物会被误提交
  4. 补齐 CHANGELOG.md
  5. 补 .github/workflows/check.yml（以后每次推送自动扫密钥）
  6. git init + add + 首次提交
  7. 打印推到 GitHub 的下一步

用法：
    python -X utf8 tools/make_repo.py          # 真的执行
    python -X utf8 tools/make_repo.py --check  # 只检查，不动仓库
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECK = ROOT / 'tools' / 'check_secrets.py'
DRY = '--check' in sys.argv

# 这些东西一旦进了 Git 历史就很难清掉，宁可错杀
FORBIDDEN_SUFFIX = {'.apk', '.ap_', '.keystore', '.jks', '.p12', '.pem', '.key'}
FORBIDDEN_DIRS = {'build', 'dist', '.gradle'}

CHANGELOG = """# 更新日志

## 1.10

- 修复聊天记录导出/导入点了没反应（桥接分发漏了一项）
- 新增「续写」：接着最后一条角色消息往下写，合并进同一条
- 修复忙碌状态只复位一个按钮，导致续写只能用一次

## 1.9

- 画图加入自评闭环：生成后与参考图比对相似度，不达标自动重画（默认 4 次）
- 结果以画廊形式全部列出，达标的自动保留，其余由用户挑
- 相似度显示在图片上方（达标绿 / 不达标橙）
- 评判失败时明确标为「未验收」，绝不当作合格放行

## 1.8

- 深度思考接通：思考内容与正文分离，可折叠查看
- 新增日志导出（配置 + 数据统计 + 界面日志）
- 开机自检从三项扩到五项，加入看图能力推断与联网检查

## 1.7

- 新增附件：图片 / 文件 / 链接，一次性附着在当轮
- 新增多身份与多角色切换
- **修复**：参考图上传点不动 —— WebView 的 `<input type="file">`
  必须重写 `WebChromeClient.onShowFileChooser`，1.4 漏了这一步

## 1.6

- 新增记忆宫殿：AI 自动归纳、自动配关键词、超限自动压缩
- 实现上它是一本特殊的世界书，复用世界书的存储与注入逻辑

## 1.5

- 新增自动联网搜索：模型判断需要实时信息时输出 `<search>` 标签，
  应用拦截、搜索、带资料重答

## 1.4

- 新增画图（MiniMax image-01），参考图由用户自己上传
- 图片折叠挂在消息下方，只存文件名不存 base64

## 1.3

- 新增前置词、完整生成参数、世界书
- 新增聊天记录 .txt 导出 / 导入
- 补上后台保活，避免切出去之后生成回调丢失

## 1.2 / 1.1 / 1.0

- 1.2 修复 `Java bridge method can't be invoked on a non-injected object`
- 1.1 修复 `Method not found`（桥接实参个数必须与形参一致）
- 1.0 骨架：四步向导、多服务商接入、本地存储、加密保管箱
"""

WORKFLOW = """name: 密钥扫描

on:
  push:
  pull_request:

jobs:
  secrets:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: 扫描硬编码的密钥与私人地址
        run: python tools/check_secrets.py
"""


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', cwd=str(ROOT), **kw)


def fail(msg):
    print()
    print('✗ ' + msg)
    sys.exit(1)


def step(n, title):
    print()
    print('=== [%s] %s ===' % (n, title))


def main():
    print('项目：%s' % ROOT)

    # ---------------- 1. git ----------------
    step('1/7', '检查 git')
    if not shutil.which('git'):
        fail('找不到 git。先装 Git for Windows，或者把 git 加进 PATH。')
    v = run(['git', '--version'])
    print('  ' + v.stdout.strip())
    already = (ROOT / '.git').exists()
    if already:
        # 如果 .git 是别的账户（比如管理员组）建的，git 会以
        # "dubious ownership" 拒绝一切操作 —— 而报错信息跟真正的原因
        # 毫不相干。这里直接把它加进白名单。
        probe = run(['git', 'status', '--porcelain'])
        if probe.returncode != 0 and 'dubious ownership' in (probe.stderr or ''):
            print('  .git 属主和当前用户不一致，加入 safe.directory')
            run(['git', 'config', '--global', '--add', 'safe.directory', str(ROOT)])
            probe = run(['git', 'status', '--porcelain'])
            if probe.returncode != 0:
                fail('git 仍然无法操作这个目录：' + (probe.stderr or '').strip())
    print('  仓库状态：' + ('已存在 .git' if already else '还没有 .git，稍后会初始化'))

    # ---------------- 2. 密钥 ----------------
    step('2/7', '密钥扫描')
    r = run([sys.executable, '-X', 'utf8', str(CHECK)])
    print(r.stdout.strip())
    if r.returncode != 0:
        fail('扫描不通过。先把上面列出的内容处理掉再跑这个脚本。')

    # ---------------- 3. 危险文件 ----------------
    step('3/7', '检查会被误提交的文件')
    bad = []
    for p in ROOT.rglob('*'):
        if not p.is_file():
            continue
        if any(part in FORBIDDEN_DIRS for part in p.relative_to(ROOT).parts):
            continue          # 这些目录本来就被 .gitignore 挡住
        if p.suffix.lower() not in FORBIDDEN_SUFFIX:
            continue
        rel = p.relative_to(ROOT)
        # 已经被 .gitignore 挡住的就没问题 —— debug.keystore 本来就该留在
        # 本地（构建要用），只是不能进仓库
        if already:
            ci = run(['git', 'check-ignore', '-q', str(rel)])
            if ci.returncode == 0:
                continue        # 确认被忽略
            if ci.returncode not in (0, 1):
                # git 自己出错了，不能当成"没被忽略"，退回看 .gitignore 文本
                gi_txt = (ROOT / '.gitignore').read_text(encoding='utf-8')
                if ('*' + p.suffix) in gi_txt:
                    continue
        bad.append(rel)
    if bad:
        print('  这些危险文件不会被 .gitignore 挡住：')
        for b in bad:
            print('    ' + str(b))
        fail('把它们移出项目目录，或加进 .gitignore。')
    print('  没有会被提交的 APK / keystore / 签名文件，干净')

    gi = ROOT / '.gitignore'
    if not gi.exists():
        fail('.gitignore 不见了，先补回来。')
    txt = gi.read_text(encoding='utf-8')
    for must in ('*.keystore', 'dist/', 'build/'):
        if must not in txt:
            fail('.gitignore 里缺少 %s' % must)
    print('  .gitignore 覆盖了 keystore / dist / build')

    # ---------------- 4. CHANGELOG ----------------
    step('4/7', '更新日志')
    cl = ROOT / 'CHANGELOG.md'
    if cl.exists():
        print('  已存在，跳过')
    elif DRY:
        print('  会写入 CHANGELOG.md（--check 模式，不实际写）')
    else:
        cl.write_text(CHANGELOG, encoding='utf-8')
        print('  已写入 CHANGELOG.md')

    # ---------------- 5. Actions ----------------
    step('5/7', '自动密钥扫描 workflow')
    wf = ROOT / '.github' / 'workflows' / 'check.yml'
    if wf.exists():
        print('  已存在，跳过')
    elif DRY:
        print('  会写入 %s（--check 模式，不实际写）' % wf.relative_to(ROOT))
    else:
        wf.parent.mkdir(parents=True, exist_ok=True)
        wf.write_text(WORKFLOW, encoding='utf-8')
        print('  已写入 %s' % wf.relative_to(ROOT))

    if DRY:
        print()
        print('--check 模式结束，没有改动任何东西。')
        return

    # ---------------- 6. 建仓库 ----------------
    step('6/7', '初始化并提交')
    if not already:
        print('  ' + run(['git', 'init']).stdout.strip())
        run(['git', 'branch', '-M', 'main'])
    # 提交者身份会永久留在开源项目的提交历史里，不替用户猜 —— 缺了就问
    name = run(['git', 'config', 'user.name']).stdout.strip()
    mail = run(['git', 'config', 'user.email']).stdout.strip()
    if not name or not mail:
        print('  这个仓库还没有配置提交者身份。')
        print('  它会写进每一次提交，公开在 GitHub 上，所以由你来填。')
        print()
        name = name or input('  名字（例如 Zhang San）：').strip()
        mail = mail or input('  邮箱（例如 you@example.com）：').strip()
        if not name or not mail:
            fail('名字和邮箱都要填。')
        # 只写进这个仓库，不动你的全局配置
        run(['git', 'config', 'user.name', name])
        run(['git', 'config', 'user.email', mail])
        print('  已写入本仓库配置（没动全局）：%s <%s>' % (name, mail))

    run(['git', 'add', '-A'])

    st = run(['git', 'status', '--porcelain'])
    staged = [l for l in st.stdout.splitlines() if l.strip()]
    print('  将要提交 %d 个文件' % len(staged))
    for l in staged[:12]:
        print('    ' + l)
    if len(staged) > 12:
        print('    … 还有 %d 个' % (len(staged) - 12))

    if not staged:
        print('  没有新变化，不需要提交。')
    else:
        c = run(['git', 'commit', '-m',
                 'AsamaTavern: 本地 AI 角色扮演客户端'])
        if c.returncode != 0:
            print(c.stdout.strip() or c.stderr.strip())
            fail('提交失败。把上面的报错发我看看。')
        print('  ' + run(['git', 'log', '--oneline', '-1']).stdout.strip())

    # ---------------- 7. 下一步 ----------------
    step('7/7', '接下来在 GitHub 上做的事')
    print("""
  1. 打开 https://github.com/new
  2. 仓库名填 AsamaTavern，选 Public
  3. **不要**勾 "Add a README"（本地已经有了，勾了会冲突）
  4. 建好后在项目目录里执行：

       git remote add origin https://github.com/<你的用户名>/AsamaTavern.git
       git push -u origin main

  5. 推送时用 Personal Access Token 当密码，不是账号密码
     GitHub → Settings → Developer settings → Personal access tokens
     生成一个勾了 repo 权限的 token

  提醒：
    - D:\\asama\\dist 里的每个 APK 都带着你的真实 key，别一起推
    - 以后每次 push，GitHub Actions 都会自动跑一遍密钥扫描
""")


if __name__ == '__main__':
    main()
