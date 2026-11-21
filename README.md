# SVTL Launcher

星露谷物语的插件包启动器：扫描压缩包 → 校验 → 解压 → 从包里加载核心 → 启动游戏 → 清缓存。

包格式见 [docs/PACKAGE.md](docs/PACKAGE.md)（若存在）；核心与插件都在包里，启动器本身不带核心。

## 构建

```bash
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
