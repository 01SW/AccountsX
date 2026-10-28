# 贡献指南

感谢你对 Accounts X 的关注！本文档帮助你快速上手开发和贡献。

## 环境要求

- **JDK 25**（common 编译目标 Java 17，各适配器按矩阵为 17 / 21 / 25）
- **Gradle wrapper** — 项目自带 `gradlew` / `gradlew.bat`，不要使用系统全局 Gradle
- 构建脚本默认使用国内镜像（BMCLAPI / 阿里云 / 腾讯），配置在 `gradle.properties` 的 `loom_*` 属性里

## 分支策略

- `dev` — 开发/发布分支，PR 目标

## 开发流程

1. Fork 仓库，基于 `dev` 创建特性分支
2. 修改代码，确保对应模块编译通过
3. 提交 PR 到 `dev` 分支

### 构建验证

```bash
# 改了 common 代码
./gradlew --no-daemon --stacktrace :build

# 改了某个 MC 适配器
./gradlew --no-daemon --stacktrace :adapters:mc:1.21.4:build

# 改了 authlib 适配器
./gradlew --no-daemon --stacktrace :adapters:authlib:6.0.54:jar

# 改了共享代码 / 构建脚本
./gradlew --no-daemon --stacktrace build

# 只跑校验任务
./gradlew --no-daemon --stacktrace :test :checkArchitecture :validateAdapterMatrix :checkAdapterSourceResolver
```

所有 Gradle 调用都带 `--no-daemon --stacktrace`：失败时能拿到完整堆栈，也不会在后台留下 daemon。

**不要**只改一个适配器就跑 `./gradlew build` — 那会编译全部 Loom 适配器，非常耗时。

### 官方源

需要走官方 Maven（例如国内镜像 403 / 401）时，用命令行属性覆盖，不要改 `gradle.properties`：

```bash
./gradlew --no-daemon --stacktrace build \
  -Ploom_libraries_base=https://libraries.minecraft.net/ \
  -Ploom_resources_base=https://resources.download.minecraft.net/ \
  -Ploom_version_manifests=https://piston-meta.mojang.com/mc/game/version_manifest.json \
  -Ploom_fabric_repository=https://maven.fabricmc.net/
```

### 版本号写在哪

- 第三方依赖版本 → `gradle/libs.versions.toml`
- 适配器矩阵（每个 MC 版本用哪个 authlib / Fabric API、是否混淆）→ `gradle/adapters.toml`

适配器目录下的 `build.gradle.kts` 只声明插件，不含任何版本号；新增适配器版本必须先在 `gradle/adapters.toml` 加条目，否则该子项目不会被 `include`。

MC 适配器源码按版本回落：每个版本目录只放与近邻**不同**的 Java 文件，构建期沿矩阵继承，详见 [AGENTS.md](AGENTS.md) 的「MC 适配器源码回落」。移动包路径时记得在 `source-removals.txt` 删掉旧路径。

### PR 要求

- PR 前确保 `./gradlew --no-daemon --stacktrace :build` 通过
- 说明手测了哪些 MC 版本（登录 / 切换 / 重启后账号仍在）
- 新 MC 版本的 PR 需说明：改了哪些文件、在哪个版本上验证过

## Commit 规范

```
<type>(<scope>): 中文摘要
```

| type       | 含义               |
|------------|--------------------|
| `feat`     | 新功能             |
| `fix`      | 修复 bug           |
| `refactor` | 重构               |
| `chore`    | 构建/工具/配置变更 |
| `docs`     | 文档               |
| `style`    | 代码格式           |
| `perf`     | 性能优化           |
| `test`     | 测试               |

scope 参照模块名：`build`、`ci`、`common`、`storage`、`auth`、`ui`、`mc`、`authlib`、`docs`、`test`

先 `git log --oneline -20` 查看历史提交，保持风格一致。

## 安全

- **不要**提交 `~/.config/accountsx.json`、`~/.accountsx/` 或任何含 token 的文件
- **不要**在代码、日志或提交信息中暴露 access token
- 测试 fixture 使用 `fake-` 前缀

## 项目结构

详见 [AGENTS.md](AGENTS.md) 的项目布局章节。
