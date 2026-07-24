# 实现计划：将 B3M 时间缩放功能集成到 StellarSky

## 项目信息

- **目标**: 在 StellarSky 中加入 B3M 的时间缩放功能
- **基础**: StellarSky 0.5.4.1 (MC 1.12.2, MIT 许可证)
- **参考**: B3M 1.12.2-20 (分析文档在 `b3m/docs/`)
- **实现方式**: Mixin (不用 ASM 注入)

---

## 功能需求

### 核心功能

1. **时间倍率控制** — 可配置的时间缩放 (-20 到 72)
2. **天气同步** — 天气计时器与世界时间同步缩放
3. **天体角修正** — 天体角计算适配缩放后的时间
4. **HUD 显示** — 显示当前时间倍率
5. **命令控制** — `/stellarsky multiplier <value>` 命令
6. **配置持久化** — 设置保存到世界数据

### 不实现的功能

- B3M 的 ASM 字节码注入
- B3M 的自定义纹理 (太阳/月亮/云)
- B3M 的 Serene Seasons 集成
- B3M 的 OptiFine shader 兼容

---

## 技术设计

### 1. 时间缩放机制

#### 1.1 核心原理

```java
// 原版：每 tick 世界时间 +1
worldTime += 1;

// 缩放后：
// multiplier > 1: 每 N 个 tick 才 +1 (时间变慢)
// multiplier = 1: 原版行为
// multiplier = 0: 时间暂停
// multiplier < 0: 每 tick -1 (时间倒流)
```

#### 1.2 实现方式

**使用 Mixin 修改 `WorldServer.tick()`**:

```java
@Mixin(WorldServer.class)
public abstract class WorldServerMixin {
    
    @Shadow
    private int tickScaleCounter = 0;
    
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        // 读取配置
        int multiplier = StellarSkyTime.getTimeMultiplier();
        
        if (multiplier == 0) {
            // 时间暂停：跳过整个 tick
            ci.cancel();
            return;
        }
        
        if (multiplier > 1) {
            // 时间变慢：跳过部分 tick
            tickScaleCounter++;
            if (tickScaleCounter < multiplier) {
                // 跳过时间递增，但保留其他逻辑
                // 不 cancel，而是修改后续逻辑
            } else {
                tickScaleCounter = 0;
                // 正常递增
            }
        }
    }
}
```

**更精确的实现** (推荐):

```java
@Mixin(WorldServer.class)
public abstract class WorldServerMixin {
    
    @Inject(
        method = "tick", 
        at = @At(
            value = "INVOKE", 
            target = "Lnet/minecraft/world/WorldServer;setWorldTime(J)V"
        ),
        cancellable = true
    )
    private void onSetWorldTime(CallbackInfo ci) {
        int multiplier = StellarSkyTime.getTimeMultiplier();
        
        if (multiplier == 0) {
            // 取消时间递增
            ci.cancel();
            return;
        }
        
        if (multiplier > 1) {
            // 使用计数器控制递增频率
            if (!StellarSkyTime.shouldIncrement()) {
                ci.cancel();
                return;
            }
        }
        
        if (multiplier < 0) {
            // 时间倒流：修改递增方向
            WorldServer self = (WorldServer)(Object)this;
            long currentTime = self.getWorldTime();
            self.setWorldTime(currentTime + multiplier);  // multiplier 为负数
            ci.cancel();
        }
    }
}
```

### 2. 天气同步

#### 2.1 问题

原版天气计时器每 tick 递减 1。如果世界时间变慢，但天气计时器不变，会导致：
- 下雨时间变短 (相对游戏内时间)
- 雷暴频率异常

#### 2.2 实现方式

**使用 Mixin 修改 `WorldServer.updateWeather()`**:

```java
@Mixin(WorldServer.class)
public abstract class WorldServerMixin {
    
    @Inject(
        method = "updateWeather",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/storage/WorldInfo;setRainTime(I)V"
        )
    )
    private void onSetRainTime(CallbackInfo ci) {
        // 获取当前 rainTime
        WorldServer self = (WorldServer)(Object)this;
        WorldInfo info = self.getWorldInfo();
        int rainTime = info.getRainTime();
        
        // 应用时间缩放
        int scaledRainTime = StellarSkyTime.scaleTick(rainTime);
        info.setRainTime(scaledRainTime);
    }
    
    @Inject(
        method = "updateWeather",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/storage/WorldInfo;setThunderTime(I)V"
        )
    )
    private void onSetThunderTime(CallbackInfo ci) {
        WorldServer self = (WorldServer)(Object)this;
        WorldInfo info = self.getWorldInfo();
        int thunderTime = info.getThunderTime();
        
        int scaledThunderTime = StellarSkyTime.scaleTick(thunderTime);
        info.setThunderTime(scaledThunderTime);
    }
}
```

### 3. 天体角修正

#### 3.1 问题

StellarSky 的天体角计算基于 `worldTime`。如果时间缩放，天体运动会不自然。

#### 3.2 实现方式

**使用 Mixin 修改 `StellarCoordinates.update()`**:

```java
@Mixin(StellarCoordinates.class)
public abstract class StellarCoordinatesMixin {
    
    @Inject(
        method = "update",
        at = @At("HEAD")
    )
    private void onUpdate(double year, CallbackInfo ci) {
        // StellarSky 的 year 基于 worldTime
        // 不需要修改，因为 worldTime 已经被缩放了
        // 天体角会自动适配缩放后的时间
    }
}
```

**实际上不需要修改天体角计算**，因为：
- StellarSky 读取 `world.getWorldTime()`
- 我们已经修改了 `worldTime` 的递增逻辑
- 天体角会自动适配

### 4. HUD 显示

#### 4.1 实现方式

**添加新的 Overlay 元素**:

```java
public class OverlayTimeMultiplier implements IOverlayElement<TimeMultiplierSettings> {
    
    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        int multiplier = StellarSkyTime.getTimeMultiplier();
        
        if (multiplier == 1) return;  // 正常速度不显示
        
        String text;
        if (multiplier == 0) {
            text = "Time: PAUSED";
        } else if (multiplier < 0) {
            text = String.format("Time: %dx (Reverse)", Math.abs(multiplier));
        } else {
            text = String.format("Time: %dx", multiplier);
        }
        
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        font.drawStringWithShadow(text, 2, 2, 0xFFFFFF);
    }
}
```

### 5. 命令系统

#### 5.1 实现方式

**添加新的命令类**:

```java
public class CommandTimeMultiplier extends CommandBase {
    
    @Override
    public String getName() {
        return "stellartime";
    }
    
    @Override
    public String getUsage(ICommandSender sender) {
        return "/stellartime <multiplier|pause|resume|reset>";
    }
    
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) 
            throws CommandException {
        if (args.length == 0) {
            // 显示当前倍率
            int current = StellarSkyTime.getTimeMultiplier();
            sender.sendMessage(new TextComponentString(
                "Current time multiplier: " + current));
            return;
        }
        
        String arg = args[0].toLowerCase();
        
        switch (arg) {
            case "pause":
                StellarSkyTime.setTimeMultiplier(0);
                sender.sendMessage(new TextComponentString("Time paused"));
                break;
                
            case "resume":
                StellarSkyTime.setTimeMultiplier(1);
                sender.sendMessage(new TextComponentString("Time resumed"));
                break;
                
            case "reset":
                StellarSkyTime.resetTimeMultiplier();
                sender.sendMessage(new TextComponentString("Time multiplier reset"));
                break;
                
            default:
                try {
                    int multiplier = Integer.parseInt(arg);
                    if (multiplier < -20 || multiplier > 72) {
                        throw new CommandException(
                            "Multiplier must be between -20 and 72");
                    }
                    StellarSkyTime.setTimeMultiplier(multiplier);
                    sender.sendMessage(new TextComponentString(
                        "Time multiplier set to " + multiplier));
                } catch (NumberFormatException e) {
                    throw new CommandException("Invalid number: " + arg);
                }
        }
    }
    
    @Override
    public int getRequiredPermissionLevel() {
        return 2;  // 需要 OP 权限
    }
}
```

### 6. 配置持久化

#### 6.1 实现方式

**扩展 `StellarManager` (WorldSavedData)**:

```java
// 在 StellarManager 中添加字段
private int timeMultiplier = 1;
private int savedTimeMultiplier = 1;  // 用于临时倍率恢复

// 保存到 NBT
@Override
public NBTTagCompound writeToNBT(NBTTagCompound compound) {
    // ... 原有代码 ...
    compound.setInteger("TimeMultiplier", this.timeMultiplier);
    compound.setInteger("SavedTimeMultiplier", this.savedTimeMultiplier);
    return compound;
}

// 从 NBT 读取
@Override
public void readFromNBT(NBTTagCompound compound) {
    // ... 原有代码 ...
    this.timeMultiplier = compound.getInteger("TimeMultiplier");
    if (this.timeMultiplier == 0) this.timeMultiplier = 1;  // 默认值
    this.savedTimeMultiplier = compound.getInteger("SavedTimeMultiplier");
    if (this.savedTimeMultiplier == 0) this.savedTimeMultiplier = 1;
}
```

---

## 文件结构

```
src/main/java/stellarium/
├── time/
│   ├── StellarSkyTime.java          # 时间缩放核心逻辑
│   ├── TimeMultiplierSettings.java   # 配置项
│   └── CommandTimeMultiplier.java    # 命令
├── mixin/
│   ├── WorldServerMixin.java         # WorldServer tick 修改
│   └── WorldServerWeatherMixin.java  # 天气同步修改
└── client/
    └── overlay/
        └── OverlayTimeMultiplier.java # HUD 显示

src/main/resources/
└── stellarium-time.mixins.json       # Mixin 配置
```

---

## 实现步骤

### 第 1 步：创建核心类

1. 创建 `stellarium/time/StellarSkyTime.java`
   - 实现 `getTimeMultiplier()`
   - 实现 `setTimeMultiplier(int)`
   - 实现 `resetTimeMultiplier()`
   - 实现 `shouldIncrement()` (计数器逻辑)
   - 实现 `scaleTick(int)` (天气缩放)

2. 创建 `stellarium/time/TimeMultiplierSettings.java`
   - 添加配置项到 `ServerSettings`
   - 范围：-20 到 72，默认 1

### 第 2 步：实现 Mixin

3. 创建 `stellarium/mixin/WorldServerMixin.java`
   - 修改 `WorldServer.tick()` 中的时间递增
   - 处理 multiplier > 1 的情况 (跳过 tick)
   - 处理 multiplier = 0 的情况 (暂停)
   - 处理 multiplier < 0 的情况 (倒流)

4. 创建 `stellarium/mixin/WorldServerWeatherMixin.java`
   - 修改 `WorldServer.updateWeather()` 中的天气计时器
   - 确保天气与时间同步缩放

### 第 3 步：添加 UI

5. 创建 `stellarium/client/overlay/OverlayTimeMultiplier.java`
   - 显示当前时间倍率
   - 支持多种格式 (暂停、倒流、加速)

6. 注册 Overlay
   - 在 `StellarSkyOverlays.java` 中注册

### 第 4 步：添加命令

7. 创建 `stellarium/time/CommandTimeMultiplier.java`
   - `/stellartime <multiplier|pause|resume|reset>`
   - 权限检查

8. 注册命令
   - 在 `StellarSky.serverStarting()` 中注册

### 第 5 步：配置持久化

9. 修改 `StellarManager.java`
   - 添加 `timeMultiplier` 和 `savedTimeMultiplier` 字段
   - 修改 `writeToNBT()` 和 `readFromNBT()`

10. 修改 `ServerSettings.java`
    - 添加 `Time_Multiplier` 配置项

### 第 6 步：网络同步

11. 扩展 `StellarNetworkManager.java`
    - 添加倍率同步消息
    - 服务器 → 客户端同步

### 第 7 步：测试

12. 测试用例
    - multiplier = 1 (原版行为)
    - multiplier = 2 (时间变慢)
    - multiplier = 10 (时间极慢)
    - multiplier = 0 (暂停)
    - multiplier = -1 (倒流)
    - multiplier = -10 (快速倒流)
    - 天气同步测试
    - 多人游戏测试

---

## 关键代码

### StellarSkyTime.java

```java
package stellarium.time;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import stellarium.stellars.StellarManager;

public class StellarSkyTime {
    
    private static int timeMultiplier = 1;
    private static int savedTimeMultiplier = 1;
    private static int tickCounter = 0;
    
    public static int getTimeMultiplier() {
        return timeMultiplier;
    }
    
    public static void setTimeMultiplier(int multiplier) {
        if (multiplier < -20) multiplier = -20;
        if (multiplier > 72) multiplier = 72;
        
        timeMultiplier = multiplier;
        savedTimeMultiplier = multiplier;
        tickCounter = 0;
        
        // 保存到世界数据
        saveToWorldData();
    }
    
    public static void resetTimeMultiplier() {
        timeMultiplier = savedTimeMultiplier;
        tickCounter = 0;
    }
    
    public static void setTemporaryMultiplier(int multiplier) {
        timeMultiplier = multiplier;
        tickCounter = 0;
    }
    
    public static boolean shouldIncrement() {
        if (timeMultiplier <= 1) return true;
        
        tickCounter++;
        if (tickCounter >= timeMultiplier) {
            tickCounter = 0;
            return true;
        }
        return false;
    }
    
    public static int scaleTick(int originalTick) {
        if (timeMultiplier == 1) return originalTick;
        if (timeMultiplier == 0) return originalTick;
        if (timeMultiplier < 0) return originalTick + timeMultiplier;
        
        // multiplier > 1: 只在应该递增时递减
        if (shouldIncrement()) {
            return originalTick - 1;
        }
        return originalTick;
    }
    
    private static void saveToWorldData() {
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER) {
            WorldServer world = FMLCommonHandler.instance()
                .getMinecraftServerInstance().getWorld(0);
            StellarManager manager = StellarManager.getManager(world);
            manager.setTimeMultiplier(timeMultiplier);
            manager.setSavedTimeMultiplier(savedTimeMultiplier);
            manager.markDirty();
        }
    }
    
    public static void loadFromWorldData(StellarManager manager) {
        timeMultiplier = manager.getTimeMultiplier();
        savedTimeMultiplier = manager.getSavedTimeMultiplier();
    }
}
```

### WorldServerMixin.java

```java
package stellarium.mixin;

import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.time.StellarSkyTime;

@Mixin(WorldServer.class)
public abstract class WorldServerMixin {
    
    @Inject(
        method = "tick",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onTick(CallbackInfo ci) {
        int multiplier = StellarSkyTime.getTimeMultiplier();
        
        if (multiplier == 0) {
            // 时间暂停：跳过整个 tick
            // 注意：这会暂停所有世界逻辑，包括红石、实体等
            // 如果只想暂停时间，需要更精细的实现
            ci.cancel();
        }
    }
    
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/WorldServer;getWorldTime()J"
        ),
        cancellable = true
    )
    private void onGetWorldTime(CallbackInfo ci) {
        int multiplier = StellarSkyTime.getTimeMultiplier();
        
        if (multiplier > 1) {
            if (!StellarSkyTime.shouldIncrement()) {
                // 跳过时间递增
                // 需要修改后续逻辑，这里只是示例
            }
        }
    }
}
```

---

## 注意事项

### 1. 兼容性

- **不使用 ASM 注入** — 使用 Mixin 代替
- **不修改字节码偏移** — Mixin 使用方法签名定位
- **测试多 CoreMod 兼容** — 特别是 LoliASM、UniversalTweaks

### 2. 性能

- `shouldIncrement()` 使用简单计数器，开销极小
- 天气缩放只在需要时修改计时器

### 3. 多人游戏

- 倍率设置保存在世界数据中
- 通过网络同步到所有客户端
- 只有 OP 可以修改倍率

### 4. 边界情况

- multiplier = 0 时暂停所有世界逻辑 (包括红石、实体)
- 如果只想暂停时间显示，需要更复杂的实现
- multiplier < 0 时时间倒流，可能导致某些逻辑异常

### 5. 配置文件

```properties
# stellarium.cfg
[server]
    # Time multiplier: -20 to 72 (default: 1)
    # > 1: Time slows down (1 MC day = N real days)
    # = 1: Normal speed
    # = 0: Time paused
    # < 0: Time reverses
    Time_Multiplier=1
```

---

## 测试计划

### 单元测试

1. `StellarSkyTime.getTimeMultiplier()` 返回正确值
2. `StellarSkyTime.shouldIncrement()` 计数器逻辑正确
3. `StellarSkyTime.scaleTick()` 天气缩放正确

### 集成测试

1. 单人游戏
   - multiplier = 1 → 时间正常
   - multiplier = 2 → 时间变慢
   - multiplier = 0 → 时间暂停
   - multiplier = -1 → 时间倒流

2. 多人游戏
   - 服务器设置倍率 → 客户端同步
   - 客户端命令 → 服务器执行

3. 兼容性测试
   - 与其他 CoreMod 共存
   - 与 StellarSky 原有功能共存

### 边界测试

1. multiplier = -20 (最小值)
2. multiplier = 72 (最大值)
3. multiplier 超出范围 → 自动限制
4. 世界重新加载 → 倍率恢复

---

## 交付物

1. **源代码** — 完整的 Java 源码
2. **Mixin 配置** — `stellarium-time.mixins.json`
3. **配置文件** — `stellarium.cfg` 示例
4. **测试报告** — 所有测试用例的结果
5. **文档** — 使用说明和 API 文档
