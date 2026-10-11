"""Build AsamaTavern without Gradle.

Same manual pipeline as the ASAMA client: aapt2 + javac + d8 + zipalign +
apksigner. No Gradle, no Kotlin daemon - this machine cannot afford them.

    python -X utf8 D:\\asamatavern\\tools\\build_apk.py
"""
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

SDK = Path(r'D:\deepseek\sdk')
BT = SDK / 'build-tools' / '36.0.0'
JAVA_HOME = Path('D:/')

PROJ = Path(r'D:\asamatavern')
SRC_MAIN = PROJ / 'app' / 'src' / 'main'
BUILD = PROJ / 'build'
OUT = PROJ / 'dist'
KEYSTORE = PROJ / 'debug.keystore'

AAPT2 = BT / 'aapt2.exe'
D8 = BT / 'd8.bat'
ZIPALIGN = BT / 'zipalign.exe'
APKSIGNER = BT / 'apksigner.bat'
JAVAC = JAVA_HOME / 'bin' / 'javac.exe'
KEYTOOL = JAVA_HOME / 'bin' / 'keytool.exe'

PKG = 'com.asamatavern'
APP_NAME = 'AsamaTavern'
VERSION_CODE = '11'
VERSION_NAME = '1.10'


def run(cmd):
    printable = ' '.join('"%s"' % c if ' ' in str(c) else str(c) for c in cmd)
    print('>>', printable, flush=True)
    p = subprocess.run([str(c) for c in cmd], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    if p.returncode != 0:
        print('!! FAILED (%d)' % p.returncode)
        if p.stdout.strip():
            print(p.stdout.strip()[:4000])
        if p.stderr.strip():
            print(p.stderr.strip()[:4000])
        sys.exit(1)
    if p.stdout.strip():
        print(p.stdout.strip()[:1200])
    return p


def find_android_jar():
    hits = sorted(SDK.glob('platforms/*/android.jar'))
    if not hits:
        raise SystemExit('android.jar not found under %s' % SDK)
    return hits[-1]


def main():
    for t, name in ((AAPT2, 'aapt2'), (D8, 'd8'), (ZIPALIGN, 'zipalign'),
                    (APKSIGNER, 'apksigner'), (JAVAC, 'javac')):
        if not t.exists():
            raise SystemExit('missing tool %s: %s' % (name, t))

    aj = find_android_jar()
    print('android.jar = %s' % aj)
    print('project     = %s' % PROJ)
    print()

    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)
    (BUILD / 'classes').mkdir()
    (BUILD / 'dex').mkdir()
    OUT.mkdir(parents=True, exist_ok=True)

    print('=== [1/7] aapt2 compile ===')
    res_zip = BUILD / 'res.zip'
    run([AAPT2, 'compile', '--dir', SRC_MAIN / 'res', '-o', res_zip])

    print()
    print('=== [2/7] aapt2 link ===')
    base_apk = BUILD / 'base.apk'
    gen = BUILD / 'gen'
    gen.mkdir()
    run([AAPT2, 'link', '-o', base_apk, '-I', aj,
         '--manifest', SRC_MAIN / 'AndroidManifest.xml',
         '-A', SRC_MAIN / 'assets', '-R', res_zip, '--java', gen,
         '--min-sdk-version', '21', '--target-sdk-version', '34',
         '--version-code', VERSION_CODE, '--version-name', VERSION_NAME,
         '--auto-add-overlay'])

    rjava = gen / 'com' / 'asamatavern' / 'R.java'
    if not rjava.exists():
        raise SystemExit('R.java not generated at %s' % rjava)

    print()
    print('=== [3/7] javac ===')
    javas = sorted(str(p) for p in (SRC_MAIN / 'java').rglob('*.java'))
    javas.append(str(rjava))
    print('java sources: %d' % len(javas))
    run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
         '-cp', aj, '-d', BUILD / 'classes'] + javas)

    print()
    print('=== [4/7] d8 ===')
    classes = sorted(str(p) for p in (BUILD / 'classes').rglob('*.class'))
    print('class files: %d' % len(classes))
    run([D8, '--lib', aj, '--min-api', '21', '--release',
         '--output', BUILD / 'dex'] + classes)

    dex = BUILD / 'dex' / 'classes.dex'
    if not dex.exists():
        raise SystemExit('d8 produced no classes.dex')

    print()
    print('=== [5/7] inject classes.dex ===')
    unsigned = BUILD / 'unsigned.apk'
    shutil.copyfile(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex, 'classes.dex')

    print()
    print('=== [6/7] zipalign ===')
    aligned = BUILD / 'aligned.apk'
    run([ZIPALIGN, '-f', '-p', '4', unsigned, aligned])

    print()
    print('=== [7/7] apksigner ===')
    if not KEYSTORE.exists():
        print('generating a debug keystore ...')
        run([KEYTOOL, '-genkeypair', '-keystore', KEYSTORE,
             '-storepass', 'android', '-alias', 'androiddebugkey',
             '-keypass', 'android', '-dname', 'CN=Android Debug,O=Android,C=US',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000'])

    final = OUT / ('%s-%s.apk' % (APP_NAME, VERSION_NAME))
    if final.exists():
        final.unlink()
    run([APKSIGNER, 'sign', '--ks', KEYSTORE,
         '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--out', final, aligned])

    print()
    run([AAPT2, 'dump', 'badging', final])

    print()
    print('=' * 60)
    print('APK: %s' % final)
    print('size: %.2f MB' % (final.stat().st_size / 1048576))
    print('=' * 60)


if __name__ == '__main__':
    main()
