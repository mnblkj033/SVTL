#!/usr/bin/env python3
"""在 apktool 解包出来的（文本）AndroidManifest 上改入口。

- 摘掉游戏 Activity 的 MAIN/LAUNCHER intent-filter
- 往 <application> 里加我们的启动器 Activity（竖屏）
"""
import re
import sys

LAUNCHER = "com.svtl.launcher.LauncherActivity"
GAME = "com.chucklefish.stardewvalley.MainActivity"

LOADING_ACTIVITY = '''        <activity
            android:name="com.svtl.launcher.LoadingActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
'''

OUR_ACTIVITY = f'''        <activity
            android:name="{LAUNCHER}"
            android:exported="true"
            android:label="SVTL"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:theme="@android:style/Theme.Material.Light.NoActionBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
'''


def main(path: str) -> int:
    text = open(path, encoding="utf-8").read()

    if LAUNCHER in text:
        print("清单里已有启动器，跳过")
        return 0

    pattern = re.compile(
        r'(<activity[^>]*android:name="%s"[^>]*>)(.*?)(</activity>)' % re.escape(GAME), re.S
    )
    m = pattern.search(text)
    if not m:
        print("找不到游戏 Activity", file=sys.stderr)
        return 1
    inner = re.sub(r"<intent-filter>.*?</intent-filter>", "", m.group(2), flags=re.S)
    text = text[: m.start()] + m.group(1) + inner + m.group(3) + text[m.end():]

    # 让 Application 换成我们的：叠加层靠它挂到游戏窗口
    app_pat = re.compile(r'(<application[^>]*android:name=")[^"]*(")', re.S)
    if app_pat.search(text):
        text = app_pat.sub(r'\1com.svtl.launcher.SvtlApp\2', text, count=1)
    else:
        text = text.replace('<application', '<application android:name="com.svtl.launcher.SvtlApp"', 1)

    idx = text.rfind("</application>")
    if idx < 0:
        print("找不到 </application>", file=sys.stderr)
        return 1
    text = text[:idx] + LOADING_ACTIVITY + OUR_ACTIVITY + text[idx:]
    open(path, "w", encoding="utf-8").write(text)
    print("清单已改：入口 =", LAUNCHER)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
