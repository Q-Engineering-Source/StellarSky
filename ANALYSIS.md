# StellarSky Mod 技术分析

## 概述

**StellarSky** 是一个 Minecraft 1.12.2 Forge mod，用真实的星座和天文数据替换 Minecraft 的天空渲染。

- **版本**: 0.5.4.1
- **MC 版本**: 1.12.2
- **Forge**: 14.23.5.2768
- **依赖**: `stellarapi@[1.12.2-0.5.2.1, 1.12.2-0.5.3.0]`
- **许可证**: 开源

---

## 架构概览

```
StellarSky (主入口)
├── StellarForgeEventHook (Forge 事件钩子)
├── StellarTickHandler (每 tick 更新)
├── StellarNetworkManager (网络同步)
├── ClientProxy / CommonProxy (代理)
├── StellarManager (时间管理, WorldSavedData)
├── StellarScene (场景管理)
├── StellarModel (渲染模型)
│   ├── StellarRenderModel (天体层渲染)
│   │   ├── LayerBrStar (亮星层)
│   │   ├── LayerMilkyway (银河层)
│   │   ├── LayerSolarSystem (太阳系层)
│   │   └── LayerDeepSky (深空天体层)
│   ├── DisplayModel (坐标网格)
│   └── LandscapeModel (地景)
├── StellarRenderer (多 pass 渲染引擎)
├── AtmosphereModel (大气散射)
└── StellarCoordinates (坐标变换)
```

---

## 与 B3M 的核心区别

| 方面 | B3M | StellarSky |
|------|-----|------------|
| **修改方式** | ASM 字节码注入 | Forge 事件 + API |
| **天体数据** | 无真实数据 | 耶鲁亮星星表 + JPL 轨道元素 + 梅西耶目录 |
| **坐标系统** | 简单角度计算 | 完整的黄道/赤道/地平坐标变换链 |
| **大气模型** | 简单亮度修正 | 消光、大气折射、散射 |
| **渲染管线** | 单 pass | 多 pass (Source/Opaque/DominateScatter) + FBO 后处理 |
| **太阳系** | 仅太阳/月亮 | 八大行星 + 月球 + 太阳 |
| **恒星** | 无 | 9110 颗真实恒星（耶鲁 BSC5） |
| **深空天体** | 无 | 梅西耶目录天体 |
| **银河** | 无 | 纹理银河球 |
| **配置** | 50+ 选项 | 分层配置（服务器/维度/客户端） |

---

## 渲染管线详解

### 多 Pass 架构

```
GenericSkyRenderer.render()
  ├── 1. preRender() — 大气预处理
  ├── 2. 坐标旋转 (-90X, 180Z)
  ├── 3. DisplayRenderer — 坐标网格（后层）
  ├── 4. StellarRenderer.render()
  │     ├── Pass 1: Source — 点光源（恒星、行星）
  │     ├── Pass 2: Opaque — 不透明球体（太阳、月亮）
  │     ├── Pass 3: DominateScatter — 大气散射贡献
  │     └── FBO 后处理（bloom/HDR）
  ├── 5. DisplayRenderer — 坐标网格（前层）
  └── 6. LandscapeRenderer — 地景
```

### 渲染 Pass 细节

**Source Pass** (点光源):
- GL 状态：`GL_ONE, GL_ONE` (加法混合)
- 渲染对象：恒星、行星（作为点）
- 使用 `renderPoint(pos, depth, r, g, b)` 渲染面向相机的四边形

**Opaque Pass** (不透明体):
- GL 状态：深度测试启用，混合禁用
- 渲染对象：太阳（纹理球体）、月亮（带光照的纹理球体）
- 使用球面网格渲染

**DominateScatter Pass** (大气散射):
- 渲染对象：太阳/月亮的散射光贡献
- 与大气模型交互

---

## 坐标系统 (`StellarCoordinates`)

### 坐标帧变换链

```
黄道零时 (Zero-Time Ecliptic)
    ↓ Z 轴旋转 (-precession * year)
黄道当前 (Now Ecliptic)
    ↓ X 轴旋转 (-axialTilt)
赤道当前 (Now Equatorial)
    ↓ Z 轴旋转 (-rot * year - longitude)
赤道旋转 (Rotating Equatorial)
    ↓ X 轴旋转 (PI/2 - latitude)
地平坐标 (Horizontal)
```

### 变换矩阵

```java
// 黄道 ↔ 赤道（轴倾角）
EqtoEc = rotationX(-axialTilt)   // 默认 23.5°
EctoEq = rotationX(axialTilt)

// 零时黄道 ↔ 当前黄道（岁差）
ZTEctoNEc = rotationZ(-precession * year)
NEctoZTEc = rotationZ(precession * year)

// 当前赤道 ↔ 旋转赤道（地球自转）
NEqtoREq = rotationZ(-rot * year - longitude)
REqtoNEq = rotationZ(rot * year + longitude)

// 旋转赤道 ↔ 地平
REqtoHor = rotationX(PI/2 - latitude)
HortoREq = rotationX(-(PI/2 - latitude))
```

### 天体位置投影

```java
// 恒星位置投影到地面相对坐标
getProjectionToGround(starPos):
  // starPos 是黄道单位向量
  // 通过变换链投影到地平坐标
  return projection * starPos
```

---

## 太阳系模型 (`stellars/system/`)

### JPL 开普勒轨道元素

所有行星使用真实的 JPL 轨道元素（历元 J2000）：

| 天体 | 半长轴 (AU) | 偏心率 | 倾角 (°) | 质量 (太阳=1) |
|------|------------|--------|----------|--------------|
| 太阳 | — | — | — | 1.0 |
| 水星 | 0.387 | 0.2056 | 7.005 | 1.66e-7 |
| 金星 | 0.723 | 0.0068 | 3.395 | 2.45e-6 |
| 地球 | 1.000 | 0.0167 | -0.0005 | 3.00e-6 |
| 月球 | 0.00257 | 0.0549 | 5.145 | 3.7e-8 |
| 火星 | 1.524 | 0.0934 | 1.850 | 3.23e-7 |
| 木星 | 5.203 | 0.0485 | 1.303 | 9.55e-4 |
| 土星 | 9.537 | 0.0556 | 2.489 | 2.86e-4 |
| 天王星 | 19.19 | 0.0472 | 0.773 | 4.37e-5 |
| 海王星 | 30.07 | 0.0086 | 1.770 | 5.15e-5 |

### 轨道位置计算

```java
getRelativePos(year):
  // 1. 计算世纪数
  cen = year / 100.0
  
  // 2. 更新轨道元素（含长期摄动）
  a = a0 + ad * cen    // 半长轴
  e = e0 + ed * cen    // 偏心率
  I = I0 + Id * cen    // 倾角
  L = L0 + Ld * cen    // 平黄经
  wbar = wbar0 + wbard * cen  // 近日点经度
  Omega = Omega0 + Omegad * cn // 升交点经度
  
  // 3. 计算近点角
  w = wbar - Omega
  M = L - wbar + b*cen² + c*cos(f*cen) + s*sin(f*cen)
  
  // 4. 构造旋转矩阵
  matrix = Omega_rotation * I_rotation * w_rotation
  
  // 5. 求解开普勒方程，计算位置向量
  return getOrbVec(a, e, M, matrix)
```

### 开普勒方程求解 (`StellarMath.calEcanomaly`)

```java
calEcanomaly(ecc, M) -> E:
  // 牛顿-拉弗森迭代
  E = M  // 初始猜测
  for (i = 0; i < 1000; i++):
    dE = (E - ecc * sin(E) - M) / (1 - ecc * cos(E))
    E -= dE
    if (|dE| < 1e-12): break
  
  // 回退：一阶近似
  if (i >= 1000):
    E = M + ecc * sin(M) / (1 - sin(M + ecc) + sin(M))
  
  return E
```

### 轨道位置向量 (`StellarMath.getOrbVec`)

```java
getOrbVec(a, e, M, rotation) -> Vec3:
  E = calEcanomaly(e, M)
  
  // 轨道平面内的位置
  x = a * (cos(E) - e)
  y = a * sqrt(1 - e²) * sin(E)
  z = 0
  
  // 应用旋转矩阵
  return rotation * (x, y, z)
```

---

## 视星等与亮度

### 视星等计算 (`SolarObject.updateMagnitude`)

```java
// 相对太阳的亮度
LvsSun = radius² * phase * distE² * albedo * 1.4 / (dist² * distS²)

// 视星等
currentMag = -26.74 - 2.5 * log10(LvsSun)
```

其中：
- `radius` — 天体半径（天文单位）
- `phase` — 位相因子（0-1）
- `dist` — 到地球的距离
- `distS` — 到太阳的距离
- `distE` — 地球到太阳的距离
- `albedo` — 反照率

### 位相计算

```java
getCurrentPhase():
  // 太阳-地球-天体的夹角
  cosAngle = (sunPos · earthPos) / (|sunPos| * |earthPos|)
  phase = (1 + cosAngle) / 2
  return phase
```

### 亮度转换 (`OpticsHelper`)

```java
// 星等到亮度 [0, 1]
getBrightnessFromMag(mag):
  MAG_BASE = 10^0.4  // 波格森比率
  MAG_UPPER_LIMIT = -0.5
  return MAG_BASE^(MAG_UPPER_LIMIT - mag)

// 面积归一化
getMultFromArea(angularArea):
  SURF_MULTIPLIER = 2π * DEFAULT_RESOLUTION²
  return SURF_MULTIPLIER / angularArea

// 相对太阳亮度
getDominationFromMag(mag):
  MAG_SUN = -26.74
  return MAG_BASE^(MAG_SUN - mag)
```

---

## 恒星系统

### 耶鲁亮星星表 (BSC5)

- **数据源**: `/data/bsc5.dat` — 9110 颗恒星
- **记录格式**: 198 字节/记录
- **解析内容**:
  - 恒星名称（字节 4-14）
  - 赤经/赤纬 J2000（字节 75-89）
  - 视星等（字节 103+）
  - B-V 色指数（字节 109-113）

### 赤道 → 黄道变换

```java
// 使用轴倾角 0.4090926 弧度（约 23.44°）
EqtoEc = rotationX(-0.4090926)
```

### 恒星颜色映射 (`StarColor`)

49 个 RGB 条目，映射 B-V 色指数 [-0.4, 2.0] 到颜色：

| B-V 范围 | 光谱型 | 颜色 |
|----------|--------|------|
| -0.4 ~ -0.2 | O/B | 蓝白 |
| -0.2 ~ 0.0 | A | 白色 |
| 0.0 ~ 0.3 | F | 黄白 |
| 0.3 ~ 0.6 | G | 黄色 |
| 0.6 ~ 1.0 | K | 橙色 |
| 1.0 ~ 2.0 | M | 红色 |

### 闪烁效果

```java
turbulance():
  return turbulance_setting * gaussian_random * 0.1
```

---

## 银河渲染

### 银河模型 (`MilkywayRenderCache`)

- 使用球面网格（经纬度网格）
- 每个顶点：
  1. 球面坐标 → 赤道坐标
  2. 赤道 → 黄道变换
  3. 投影到地面相对坐标
- 表面亮度：`brightness * getBrightnessFromMag(4.5)`

---

## 大气模型

### 大气参数 (`PerDimensionSettings`)

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `Scale_Height` | 0.1238 | 标度高度 |
| `Total_Height` | 0.3 | 总高度 |
| `Height_Offset` | 0.0 | 高度偏移 |
| `Height_Increase_Scale` | 0.0 | 高度增长比例 |
| `Sky_Extinction_Factors` | {1.0, 1.0, 1.0} | R/V/B 消光因子 |
| `Sky_Dispersion_Rate` | 1.0 | 色散率 |
| `Light_Pollution_Rate` | 0.0 | 光污染率 |

### 大气折射 (`ExtinctionRefraction`)

**应用折射** (Saemundsson 1986):
```java
R = 1.02 / tan(h + 10.3/(h + 5.11))
refracted_h = h + R/60  // 度
```

**消除折射** (Garfinkel 1967):
```java
R = 1.0 / tan(h + 7.31/(h + 4.4))
true_h = h - R/60  // 度
```

### 气团计算

```java
// Rozenberg (1966)
airmass(cosZ):
  return 1.0 / (cosZ + 0.025 * exp(-11 * cosZ))
```

---

## 深空天体

### 梅西耶目录

- 从 JSON 文件加载 (`/assets/stellarium/deepsky/messier/`)
- 每个天体包含：
  - 名称
  - 赤经/赤纬（HMS/DMS 格式）
  - 视星等
  - 角尺寸（宽/高，弧度）
  - 可选纹理

---

## 坐标网格显示

### 三种网格类型

1. **水平网格** (`HorGridType`) — 地平坐标系网格
2. **赤道网格** (`EqGridType`) — 赤道坐标系网格
3. **黄道网格** (`EcGridType`) — 黄道坐标系网格

每种网格都有独立的设置、缓存和渲染器。

---

## 配置系统

### 分层配置

```
ServerSettings (全局服务器)
├── Day_Length (默认 24000)
├── Year_Length (默认 365.25)
├── Year_Offset / Day_Offset / Tick_Offset
├── Axial_Tilt (默认 23.5°)
├── Precession (默认 0°/年)
└── SolarSystemSettings

DimensionSettings (每维度)
└── PerDimensionSettings
    ├── StellarSky_Enabled
    ├── Latitude / Longitude
    ├── Sky_Renderer_Type
    ├── 大气参数
    ├── 消光/折射设置
    └── Landscape_Enabled

ClientSettings (客户端)
├── Mag_Limit (默认 4.5, 范围 3.0-7.0)
└── 各层客户端设置
```

---

## 时间系统

### 天文年计算

```java
getSkyYear(currentTick):
  // 时间偏移
  phase = (yearOffset * year + dayOffset) * day + tickOffset
  
  // 天文年 = (当前tick + 偏移) / (一天ticks × 一年天数)
  return (currentTick + phase) / (day * year)
```

### 锁定机制

- 服务器可以"锁定"天空设置
- 锁定时：设置存储在 NBT 中，通过网络同步到客户端
- 解锁时：设置从配置文件读取

---

## 实现要点总结

1. **真实天文数据**: 使用耶鲁 BSC5 星表（9110 颗恒星）、JPL 轨道元素（八大行星+月球）、梅西耶目录
2. **完整坐标变换**: 黄道 ↔ 赤道 ↔ 地平，含岁差和轴倾角
3. **多 pass 渲染**: Source（点光源）、Opaque（球体）、DominateScatter（大气散射）
4. **大气物理**: 消光、折射、散射、光污染
5. **开普勒方程**: 牛顿-拉弗森迭代求解
6. **星等系统**: 完整的视星等到亮度转换
7. **层架构**: 恒星/银河/太阳系/深空独立管理
8. **缓存渲染**: 物理计算与 GL 渲染分离
