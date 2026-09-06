# AutoAgent

装在家人手机上的「手机 GUI Agent」：既能本地自动点击/输入，也能接入 AI 自主完成任务，也支持你在自己电脑上远程人工操作她的手机。

**感知只依赖无障碍节点树（纯文本），不使用截图、不依赖读图能力。**

## 架构

```
模块 A  感知/执行引擎（无障碍服务）
         感知: 节点树 → 带编号可读文本（如 [03] Button "下一步" 点 (540,1100)）
         执行: click / longClick / inputText / swipe / scroll / back / home / getElements / wait
模块 B  命令生成器（统一接口，可插拔）
         B1 手动脚本（纯本地连点器）   B2 指令控制台
         B3 内置 LLM（OpenAI 兼容）    B4 pi agent（ddeb SSH + pi -p）
         B5 远程人工指挥（经 ddeb 中转，浏览器控制页）
模块 C  UI（Compose：主页/脚本/设置 + 悬浮球）
模块 D  可选按需单帧截图（MediaProjection，默认关闭）
```

A 与 B 之间通过 JSON 命令协议解耦（见 `app/src/main/java/com/dilfish/autoagent/engine/Protocol.kt`）。

## 权限

- 必须手动开启：**无障碍服务**（Android 13+ 传 APK 自装需先在应用信息 → 允许受限设置）
- 悬浮球使用无障碍专属窗口，无需悬浮窗权限；无需 root；截图功能仅在启用时要求 MediaProjection

## 已知系统级限制

银行/支付密码框与 FLAG_SECURE 界面读不到；部分银行 App 检测无障碍会拒绝运行；指纹/人脸必须本人。

## 构建

```
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装）。
依赖下载走 socks5h://localhost:1140 代理（见 `gradle.properties`）。

## 远程指挥服务（部署在 ddeb）

```
./server/deploy.sh          # 部署到 /opt/autoagent-relay
# 启动: TOKEN=你的token PORT=8787 node relay.js
```

- 手机端：设置页填 ws://deb.871116.xyz:8787/ws/phone + token
- 电脑端：浏览器打开 http://deb.871116.xyz:8787/?token=你的token
  左侧实时节点树 + 截图，右侧命令行直接操作她的手机
