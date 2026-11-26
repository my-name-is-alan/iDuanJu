# iDuanJu 插件合集（红果 / 黄果 / 视频站 / 漫画）

纯 JS idj1 插件，可在支持 idj1 的 iDuanJu 客户端里一键导入；不需要内置源码。

## 一键导入（推荐，订阅里包含全部 16 个插件）

我的 → 插件 → 添加订阅地址，粘贴：

```
https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/subscription.json
```

订阅添加后，点订阅里的「全部安装」即可一次装好所有可见插件（成人插件需要先在「片源管理」解锁成人内容；「全部安装」需要 1.6b41 / 1.6.41-android 及以上，旧版本可逐个安装）。之后点「检查更新」即可拿到新版本。

## 单独导入

我的 → 插件 → 从网址添加，粘贴对应链接：

- 红果短剧：`https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/plugin-packages/hongguo.idjplugin`
- 黄果短剧：`https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/plugin-packages/huangguo.idjplugin`
- 其余 14 个：`https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/plugin-packages/<名字>.idjplugin`

也可以下载 `.idjplugin` 文件后从文件导入。

## 插件清单

| 插件 | 类型 | 说明 |
| --- | --- | --- |
| 红果短剧 | 短剧 | 网页榜单调目录，App 签名通道取全集播放地址；支持官方弹幕 |
| 黄果短剧 | 短剧 | AI 站 + 视频站 + App 源三路子源（含成人内容） |
| 91Porn / Eporner / Hanime1 / HQPorner / Jable / MissAV / Pornhub / RedTube / XNXX / XVideos / YouPorn | 长视频（18+） | 与 App 内置推荐插件相同的 `.idjplugin` 整包 |
| 18comic 禁漫 / Komikindo / Manga18fx | 漫画（18+） | 与 App 内置推荐插件相同的 `.idjplugin` 整包 |

成人插件默认隐藏，需在「片源管理」打开成人内容 / 成人漫画开关后才会显示并安装。

## 目录结构（本分支）

```
PluginsSrc/hongguo/        红果插件源码（manifest.json + main.js）
PluginsSrc/huangguo/       黄果插件源码（manifest.json + main.js）
plugin-packages/*.idjplugin       打包产物，供上面的链接直接安装（16 个）
subscription.json          订阅清单（16 个插件）
```

红果 / 黄果之外的 14 个插件与 App 内置包逐字节相同，源码不在本仓库；修改红果 / 黄果源码后重新打包：

```
uv run --python 3.11 python scripts/pack-plugins.py /tmp/idjplugins
cp /tmp/idjplugins/*.idjplugin plugin-packages/
```