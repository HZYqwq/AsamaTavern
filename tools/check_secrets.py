"""提交前扫描：仓库里不该有任何真实密钥或私人信息。

    python -X utf8 tools/check_secrets.py

命中就退出码 1，可以直接挂在 pre-commit 或 CI 上。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 只看源码与配置，不看构建产物
EXTS = {'.java', '.html', '.js', '.json', '.xml', '.txt', '.md',
        '.gradle', '.properties', '.yml', '.yaml', '.sh', '.py'}

PATTERNS = [
    (r'sk-[A-Za-z0-9]{16,}',            'OpenAI 风格的密钥'),
    (r'bce-v3/[A-Za-z0-9\-]{10,}',      '百度千帆密钥'),
    (r'eyJ[A-Za-z0-9_\-]{20,}\.',       'JWT'),
    (r'AKID[A-Za-z0-9]{16,}',           '腾讯云 SecretId'),
    (r'LTAI[A-Za-z0-9]{12,}',           '阿里云 AccessKey'),
    (r'(?<!\d)(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}', '硬编码的 IP:端口'),
    (r'(api[_-]?key|apikey|secret|token|passwd|password)\s*[=:]\s*["\'][^"\']{16,}',
     '疑似硬编码的密钥/口令'),
    (r'MINIMAX_CN_API_KEY',             'MiniMax 环境变量名'),
    (r'DEEPSEEK_API_KEY',               'DeepSeek 环境变量名'),
]

# 明确允许出现的占位写法
ALLOW = re.compile(r'(你的|请输入|placeholder|YOUR_|xxx|\*\*\*|示例|example|TODO)', re.I)


def main():
    hits = []
    scanned = 0
    for p in ROOT.rglob('*'):
        if not p.is_file() or p.suffix.lower() not in EXTS:
            continue
        if any(part in ('build', 'dist', '.git') for part in p.parts):
            continue
        # 这个脚本自己就带着规则表，扫自己没有意义
        if p.name == 'check_secrets.py':
            continue
        scanned += 1
        try:
            text = p.read_text(encoding='utf-8', errors='replace')
        except Exception:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if ALLOW.search(line):
                continue
            for pat, label in PATTERNS:
                if re.search(pat, line):
                    hits.append((p.relative_to(ROOT), i, label, line.strip()[:110]))

    print('扫描 %d 个文件' % scanned)
    if not hits:
        print('干净：没有发现硬编码的密钥或私人地址。')
        return 0
    print()
    print('!! 发现 %d 处可疑内容，提交前必须处理：' % len(hits))
    for path, line, label, text in hits:
        print('  %s:%d  [%s]' % (path, line, label))
        print('      %s' % text)
    return 1


if __name__ == '__main__':
    sys.exit(main())
