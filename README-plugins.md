# iDuanJu 短剧插件（红果 / 黄果）

纯 JS idj1 插件，可在支持 idj1 的 iDuanJu 客户端里一键导入；不需要内置源码。

## 一键导入（推荐，一次添加两个插件）

我的 → 插件 → 添加订阅地址，粘贴：

```
https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/subscription.json
```

订阅添加后在列表里按需安装「红果短剧」「黄果短剧」。之后点「检查订阅更新」即可拿到新版本。

## 单独导入

我的 → 插件 → 从网址添加，粘贴对应链接：

- 红果短剧：`https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/plugin-packages/hongguo.idjplugin`
- 黄果短剧：`https://raw.githubusercontent.com/my-name-is-alan/iDuanJu/plugin/plugin-packages/huangguo.idjplugin`

也可以下载 `.idjplugin` 文件后从文件导入。

## 插件说明

| 插件 | 内容 | 说明 |
| --- | --- | --- |
| 红果短剧 | 红果免费短剧 | 网页榜单调目录，App 签名通道取全集播放地址；支持官方弹幕 |
| 黄果短剧 | AI 站 + 视频站 + App 源 | 三路子源；AI 站含成人向短剧，默认在成人内容开关打开后可见 |

## 目录结构（本分支）

```
PluginsSrc/hongguo/        红果插件源码（manifest.json + main.js）
PluginsSrc/huangguo/       黄果插件源码（manifest.json + main.js）
plugin-packages/*.idjplugin       打包产物，供上面的链接直接安装
subscription.json          订阅清单（两个插件）
```

修改源码后重新打包：

```
uv run --python 3.11 python scripts/pack-plugins.py /tmp/idjplugins
cp /tmp/idjplugins/*.idjplugin plugin-packages/
```