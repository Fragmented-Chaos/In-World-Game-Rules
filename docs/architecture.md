# In-World Game Rules — 架构与原理

> 一个**纯客户端**模组：在「选项」界面里把原版那一格换成「世界选项」，让玩家在存档内直接改游戏规则、调难度。
> 目标版本 MC 1.20.1；同时支持 **Fabric / Quilt / Forge / NeoForge**。

---

## 1. 为什么不是一个 jar

| 加载器家族 | 运行时命名空间 | 需要的 class 形态 |
|---|---|---|
| Fabric / Quilt | **intermediary**（`net/minecraft/class_310`、`method_xxxx`） | loom 的 `remapJar` 产物 |
| Forge / NeoForge | **SRG**（`net/minecraft/client/Minecraft`、`m_xxxxx_`） | moddev 的 `reobfJar` 产物 |

同一个类路径下的 `WorldOptionsScreen.class` 在两边**字节码不同**（实测：同一个类，fabric 侧 104 处 intermediary 名，forge 侧 70 处 SRG 名），
所以"合并成一份"在 1.20.1 上不成立（26.x 起 MC 无 intermediary 才可以，本机其它项目正是那种情况）。
**结论：一个平台家族一个 jar，各自自包含。**

（曾尝试过"罐中罐"单文件包：外层外壳 + 嵌套两份 jar。已放弃，原因见 §7。）

---

## 2. 工程结构

```
InWorldGameRules/
├── common/                     ← 共享逻辑，不含任何加载器专属代码
│   └── src/main/
│       ├── java/com/fragmentedchaos/inworldgamerules/
│       │   ├── Constants.java                 模组 id / 日志器
│       │   ├── CommonClass.java               初始化（打一行日志）
│       │   ├── client/
│       │   │   ├── WorldOptionsHelper.java    ★ 权限判定 + 打开/写回规则的"动作层"
│       │   │   ├── WorldOptionsScreen.java    ★「世界选项」界面（难度+锁+编辑游戏规则）
│       │   │   ├── Translations.java          ★ 文案解析（游戏 → 自带 lang → 英文兜底）
│       │   │   └── RemoteGameRules.java       ★ 多人服务器上的规则读写（命令通道）
│       │   └── mixin/
│       │       ├── MixinOptionsScreen.java       把原版那一格换成我们的入口
│       │       ├── MixinMinecraftTick.java       每帧轮询（远端快照收尾）
│       │       ├── MixinClientPacketListener.java 截获 /gamerule 的答复
│       │       └── GameRulesValueInvoker.java    @Invoker 打开 deserialize
│       └── resources/
│           ├── assets/inworldgamerules/lang/*.json   143 份语言（抄自官方 26.3）
│           ├── assets/inworldgamerules/icon.png
│           ├── inworldgamerules.png                  列表图标
│           ├── pack.mcmeta
│           └── inworldgamerules.mixins.json          客户端 mixin 列表
├── fabric/                     ← fabric-loom 1.9.2；产出 intermediary 映射的 jar
│   └── src/main/resources/{fabric.mod.json, quilt.mod.json, inworldgamerules.fabric.mixins.json}
├── forge/                      ← net.neoforged.moddev.legacyforge 2.0.147；产出 SRG 映射的 jar
│   └── src/main/resources/{META-INF/mods.toml, inworldgamerules.forge.mixins.json}
└── buildSrc/src/main/groovy/
    ├── multiloader-common.gradle    公共编译配置（toolchain 17、maven 源）
    └── multiloader-loader.gradle    把 common 的 classes/resources 并进平台 jar
```

产物（`./gradlew build`）：

```
build/dist/inworldgamerules-1.20.1-1.0.0.zip
├── fabric/inworldgamerules-fabric-1.20.1-1.0.0.jar    → Fabric + Quilt
└── forge/inworldgamerules-forge-1.20.1-1.0.0.jar      → Forge + NeoForge(1.20.1)
```

| 平台 | 依据 |
|---|---|
| Fabric | `fabric.mod.json`（客户端 entrypoint + `mixins`） |
| Quilt | 同一份 jar 里的 `quilt.mod.json`（`intermediate_mappings=net.fabricmc:intermediary` + `mixin` 字符串数组），Quilt 会优先用它，成为**原生 Quilt 模组** |
| Forge / NeoForge | `META-INF/mods.toml`（`modLoader=javafml`）；NeoForge 1.20.1 与 Forge 同用 SRG，其 loader mod id 仍是 `forge` |

---

## 3. 运行时原理：入口与权限

```
                        玩家按 Esc
                            │
                    ┌───────▼────────┐
                    │  选项 (Options) │
                    └───────┬────────┘
        原版 init 会调用 createOnlineButton()（单人未发布时改用难度按钮）
                            │
        ┌───────────────────▼─────────────────────────────┐
        │ MixinOptionsScreen @Inject(HEAD, cancellable)    │
        │   if (!canShowWorldOptions) return;   ← 原样返回 │
        │   else 返回「世界选项」按钮                       │
        └───────────────────┬─────────────────────────────┘
                            │ 点击
                    ┌───────▼─────────┐
                    │ WorldOptionsScreen │
                    │  难度 + 锁（原版）  │
                    │  编辑游戏规则       │
                    └───────┬─────────┘
                            │ 点击「编辑游戏规则」
                    ┌───────▼──────────────────────────┐
                    │ WorldOptionsHelper.openGameRules  │
                    └───────┬──────────────────────────┘
                            │
                getIntegratedServer() == null ?
                   │                    │
                是（真·远程）          否（有本地集成服务器）
                   │                    │
        ┌──────────▼─────────┐  ┌───────▼────────────────────┐
        │ RemoteGameRules    │  │ 直接拿 server.getGameRules() │
        │ （命令通道，§5）    │  │ 开原版 EditGameRulesScreen   │
        └────────────────────┘  └────────────────────────────┘
```

**权限判定（唯一的门槛）**

```java
canShowWorldOptions(mc) = mc.player != null && mc.player.hasPermissions(2);
```

- 权限等级由**服务端**同步给客户端，所以单人（开作弊的房主）、局域网主机、服务器上的 OP 都成立 ✓；
- 没有权限时**完全不介入** ✓：单人 = 原版「难度 + 锁」，多人/已发布局域网 = 原版「联机设置…」。

**一个必须记住的坑：`hasSingleplayerServer()` 在"已发布局域网"后会变成 false。**

```
世界刚打开                isLocalServer = true   → hasSingleplayerServer() = true
点了「对局域网开放」之后   isLocalServer = false  → hasSingleplayerServer() = false
```

原版据此把那一格从「难度」换成「联机设置…」；用它判断"是不是房主"是错的 ✗
（房主会被误判成远程玩家 → 被迫走命令通道 → 规则编辑失效）。正确做法是 `getSingleplayerServer()`，它在发布后**依然非空**。

---

## 4. 本地路径（单人 / 局域网主机）—— 主路径，零命令

```
openGameRules()
  server = getSingleplayerServer()          // 非空即本地
  live   = server.getGameRules()            // 服务端权威实例就在本进程
  before = live.copy()
  setScreen(new EditGameRulesScreen(live.copy(), result -> {
      setScreen(returnTo);
      result.ifPresent(edited -> server.execute(() -> {      // 排到服务端线程
          applyChanges(live, before, edited, server);         // 只写真正变化的键
          minecraft.execute(() -> announce(改动提示));         // 回客户端线程播报
      }));
  }));
```

- `applyChanges` 只用 `setFrom(newValue, server)`，**不用 `assignFrom`**（后者会对所有规则触发 `onChanged` 回调，等于把没改的规则也"动"一遍）；
- 播报文案复用原版 `commands.gamerule.set`，与手打 `/gamerule` 完全一致。

---

## 5. 远程路径（真·多人服务器上的 OP）—— 命令通道

1.20.1 **没有任何把游戏规则同步给客户端的包**（game 协议里相关包类数 = 0），客户端那份 `GameRules` 全是默认值。

> **教训**：拿客户端那份当基准做差分再回写，会把"其实没变"的规则按默认值覆盖过去 —— 曾经一次性刷掉 37 条规则。现在有硬保险（见下）。

```
① 探针      request() 只发一条 gamerule doFireTick
              └─ 3 秒无答复 → 立刻放弃（不轰命令、不误判），聊天栏提示"未做任何修改"
② 批量查询  收到探针答复后，才按 1 条/帧逐个发 gamerule <规则id>（~46 条 ≈ 2 秒）
③ 收集      MixinClientPacketListener 在 handleSystemChat(HEAD) 截下答复：
              只认 Component 的翻译键 commands.gamerule.query，直接取 args[0]=规则id、args[1]=值
              （★ 不解析文本 —— 中文译文没有空格，按空格分词会得到 "randomTickSpeed3" 这种垃圾）
              截下的消息不再显示到聊天栏（不刷屏）
④ 收尾      MixinMinecraftTick 每帧检查：收齐 / 空闲 250ms / 上限 20 秒 → 用 @Invoker deserialize
              把服务端字符串装回一份镜像 GameRules
⑤ 编辑      用镜像开原版 EditGameRulesScreen
⑥ 写回      apply() 与服务端快照做差分，逐条发 gamerule <规则id> <值>，由服务端执行
              若快照不完整（读回数 < 查询数）→ **一条都不写**，并提示玩家
```

`sendCommand` 传的字符串**不带** `/`（字节码确认它不做斜杠处理）。

---

## 6. 文案解析：为什么不用 `Component.translatable`

> Minecraft 只在"有东西把模组放进资源系统"时才读它的 `assets/<id>/lang/`：
> Forge/NeoForge 由加载器自己做，**Fabric/Quilt 则依赖 Fabric API 的 resource-loader**。

本模组不依赖 Fabric API，所以在 Fabric/Quilt 上语言文件不会被加载，`Component.translatable` 会直接显示原始 key。

```
Translations.get(key, 英文兜底)
  ├─ 1. Language.getInstance().has(key) ? 用它        ← Forge/NeoForge，或资源包提供了这些 key
  ├─ 2. 读本模组 jar 内的 /assets/inworldgamerules/lang/<当前语言>.json（类路径资源，与资源系统无关）
  └─ 3. 代码里的英文兜底
按当前语言缓存（Minecraft.getLanguageManager().getSelected()），切换语言后下次查询即生效。
调用点一律**现取**（不做 static final 常量，否则语言会被冻结在类加载时）。
```

143 份语言抄自官方 **26.3** 的语言文件（4 个 key：`options.worldOptions.*` 那一套）。
其中 **27 个语言官方尚未翻译、内容与英文完全相同**（`en_gb`/`en_au`/`en_ca`/`en_nz` 本就该是英文）—— 属上游状态，不是文件问题。

---

## 7. Mixin 注入点一览

| 目标 | 位置 | 作用 | 冲突面 |
|---|---|---|---|
| `OptionsScreen#createOnlineButton` | HEAD, cancellable | 有权限时替换那一格为「世界选项」 | 只在被原版调用时介入；无权限时原样返回 |
| `Minecraft#tick` | TAIL | 远端快照收尾轮询 | Mixin 允许多注入共存 |
| `ClientPacketListener#handleSystemChat` | HEAD, cancellable | **仅收集窗口内**吃掉 `commands.gamerule.query` | 条件极窄（窗口期 + 单一翻译键） |
| `GameRules.Value#deserialize` | `@Invoker` | 把服务端字符串装回规则值 | 纯访问器，不改行为 |

**游戏规则表是全局的**：`GameRules.visitGameRuleTypes` 遍历 `GAME_RULE_TYPES`（`Map<Key<?>,Type<?>>`），
所以**其它模组注册的规则同样会出现、同样可改**；前提是**客户端也装了那个模组**（表在客户端 JVM 里）。
远端场景还要求两端装着同一个模组（否则服务端不认识那个 id）。

---

## 8. 已知边界（诚实清单）

| 项 | 状态 |
|---|---|
| 本地路径（单人 / 局域网主机） | 已验证：读写集成服务器的规则、只改变化项 |
| Fabric / Quilt 上显示中文等本地化文案 | 已验证：`Translations` 生效（不再显示原始 key） |
| 远端路径 | **未在真实服务器验证通过**：本机测试中服务端对 `gamerule <id>` 查询**毫无回应**（无答复、无报错），已加探针 + 限速 + 拒绝不完整写入 |
| 依赖 Fabric API | 不需要；装了也兼容（会用游戏自带翻译，结果一致） |
| 需要权限 | 必须真的是 OP（权限等级 ≥ 2）；模组不绕过任何权限，写入就是普通 `/gamerule` |
| 服务端插件拦 `/gamerule` | 远端读取会失败并提示；本地路径不受影响 |

---

## 9. 已废弃的设计：罐中罐（JiJ 单文件包）

试过并在实测后放弃，原因各有实证：

| 卡点 | 现象 |
|---|---|
| 外壳缺 `@Mod` 类 | Forge：`constructed 0 mods: [], but had 1 mods specified` |
| 外壳缺 `pack.mcmeta` | Forge：`未能为文件…加载有效的 resourcePackInfo` |
| `quilt.mod.json` 缺 `intermediate_mappings` | Quilt：`doesn't support hashed mappings` 直接崩溃 |
| `quilt_loader.jars` 条目格式 | 必须是**字符串数组**（对象写法属 fabric 语法，混用即 `Entry inside jars must be a string`） |
| **Quilt 不加载用户模组声明的嵌套 jar** | 两种元数据写法均只加载外壳，nesting 从未生效（loader 里只有 Fabric 插件实现了嵌套加载） |

现在：`./gradlew build` 只产出 §2 那两份自包含 jar，并由 `distZip` 打成一个 zip。
