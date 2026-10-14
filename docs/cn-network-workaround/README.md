# 受限网络（中国大陆）构建绕行

这两个脚本是**可选的机器本地绕行**，不参与本项目的正常构建 —— 网络正常时完全不需要它们。

适用于：`maven.neoforged.net` 被 DNS 污染 / TLS 重置的网络环境（明文 DNS 应答会被伪造成 Facebook 网段
`31.13.x`、`69.63.x`、`108.160.x`、`2a03:2880:…:face:b00c:…`）。此时 Forge 侧工具链
（moddev legacyforge → NeoFormRuntime，NFRT）取不到产物，`build` 与 IDEA 导入都会以 `ConnectException` 失败。

## 用法

把两个脚本复制到 Gradle 的全局 init 目录（对所有构建自动生效，无需 `--init-script`）：

```bash
mkdir -p ~/.gradle/init.d
cp neoforged-mirror.gradle nfrt-offline-artifacts.gradle ~/.gradle/init.d/
```

- `neoforged-mirror.gradle`：把任何 `maven.neoforged.net` 仓库 URL 改写成官方 ForgeCDN 镜像
  `https://neoforged.forgecdn.net/`（同一批产物）—— 管 Gradle 自己的依赖解析。
- `nfrt-offline-artifacts.gradle`：NFRT **不走** Gradle 仓库、自己发 HTTP，所以还要把这些坐标先解析到本地，
  再注入任务的 artifact manifest（`artifactManifestEntries`；manifest 命中就直接用本地文件、不再联网）。
  按任务类型 `net.neoforged.nfrtgradle.*` 匹配，因此 `createMinecraftArtifacts`（构建时跑）与
  `downloadAssets`（IDEA 导入 / Gradle 同步会跑，**有自己独立的一份 manifest**）都会被覆盖。

## 为什么放在这里而不是项目构建脚本里

`nfrt-offline-artifacts.gradle` 要反射 moddev 内部的包私有字段，属于对第三方插件实现细节的绕行；
它对正常网络零收益，却会随插件升级而变化。放在机器本地既不影响项目可移植性，也不给其他贡献者添麻烦。
本项目仓库只保留了一处无害的镜像仓库声明（`buildSrc/src/main/groovy/multiloader-common.gradle` 的
`repositories` 里加了 `neoforged.forgecdn.net`）。
