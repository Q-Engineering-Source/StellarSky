# 交接提示词

将以下内容完整复制，交给实现者：

---

## 任务

在 StellarSky (MC 1.12.2 Forge mod) 中加入 B3M 的时间缩放功能。

## 背景

- **StellarSky** 是一个天文渲染 mod，用真实星座替换 Minecraft 天空。许可证：MIT。仓库：`https://github.com/MinecraftModDevelopmentMods/StellarSky.git`
- **B3M** 是一个时间控制 mod，可以加速/减慢/暂停/倒流时间。但它用 ASM 字节码注入，与其他 CoreMod 冲突导致崩溃。
- **目标**：用 Mixin 将 B3M 的时间缩放功能移植到 StellarSky 中。

## 需要实现的功能

### 1. 时间倍率控制

支持 -20 到 72 的整数倍率：
- `multiplier > 1`：时间变慢（每 N 个游戏 tick 才递增一次世界时间）
- `multiplier = 1`：原版行为
- `multiplier = 0`：时间暂停
- `multiplier < 0`：时间倒流

### 2. 天气同步

天气计时器（rainTime、thunderTime）需要与世界时间同步缩放，否则下雨时间会异常。

### 3. 命令

`/stellartime <multiplier|pause|resume|reset>` — 需要 OP 权限。

### 4. HUD

在屏幕左上角显示当前倍率（正常速度时不显示）。

### 5. 配置持久化

倍率设置保存到 `StellarManager` (WorldSavedData)，世界重载后恢复。

## 技术要求

- **使用 Mixin**，不要用 ASM 注入
- **不要修改** StellarSky 的天体角计算（它读取 `worldTime`，会自动适配）
- **测试兼容性**：确保与 LoliASM、UniversalTweaks 等 CoreMod 共存
- **范围限制**：只做时间缩放，不要加入 B3M 的纹理、大气、HUD 时钟等功能

## 关键文件

- `src/main/java/stellarium/StellarSky.java` — 主入口
- `src/main/java/stellarium/StellarTickHandler.java` — tick 处理
- `src/main/java/stellarium/stellars/StellarManager.java` — 世界数据
- `src/main/java/stellarium/common/ServerSettings.java` — 配置
- `src/main/java/stellarium/sync/StellarNetworkManager.java` — 网络
- `src/main/java/stellarium/client/overlay/` — HUD

## 参考实现

核心逻辑伪代码：

```java
// 时间缩放
class StellarSkyTime {
    static int timeMultiplier = 1;
    static int tickCounter = 0;
    
    // 每 tick 调用，判断是否应该递增世界时间
    static boolean shouldIncrement() {
        if (timeMultiplier <= 1) return true;
        return ++tickCounter % timeMultiplier == 0;
    }
    
    // 天气计时器缩放
    static int scaleTick(int original) {
        if (timeMultiplier > 1) return shouldIncrement() ? original - 1 : original;
        if (timeMultiplier == 0) return original;
        if (timeMultiplier < 0) return original + timeMultiplier;
        return original - 1;
    }
}

// Mixin: WorldServer.tick()
// 在 setWorldTime() 调用前注入
// if (!shouldIncrement()) cancel;
// if (multiplier < 0) { setWorldTime(current + multiplier); cancel; }
```

## 验收标准

1. `multiplier = 1`：时间正常，无副作用
2. `multiplier = 2`：一天变成 2 个原版天
3. `multiplier = 0`：时间暂停，红石/实体也暂停
4. `multiplier = -1`：时间倒流
5. 天气持续时间与倍率同步
6. 命令 `/stellartime 10` 生效，`/stellartime pause` 暂停
7. HUD 显示当前倍率
8. 世界重载后倍率恢复
9. 与其他 CoreMod 共存不崩溃

---

## 完整计划文档

详细实现计划在 `StellarSky/IMPLEMENTATION_PLAN.md`，包含：
- 完整的技术设计
- 所有代码示例
- 文件结构
- 测试计划

## 已有分析文档

- `StellarSky/ANALYSIS.md` — StellarSky 架构分析
- `StellarSky/DEEP_ANALYSIS.md` — 渲染管线、Shader 深度分析
- `b3m/ANALYSIS.md` — B3M 整体分析
- `b3m/docs/01-time-system.md` — B3M 时间系统详解
- `b3m/docs/11-time-system-comparison.md` — 两个 mod 时间系统对比
