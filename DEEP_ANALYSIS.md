# StellarSky 深度分析 — 渲染管线、Shader、子系统详解

## 渲染管线完整流程

### 入口：`GenericSkyRenderer.render()`

```
1. 获取 ViewerInfo (坐标 + 大气效果 + 观察者)
2. 创建 SkyRI (渲染信息：MC实例、世界、partialTicks、屏幕尺寸)
3. SkyRenderer.preRender()  → 大气预处理
4. SkyRenderer.render()     → 主渲染
```

### 主渲染：`SkyRenderer.render()`

```
GL状态设置:
  pushMatrix()
  rotate(-90, 1, 0, 0)    // 仰角→地平
  rotate(180, 0, 0, 1)    // E,N,Z坐标系
  disableDepth, disableAlpha, disableFog
  enableBlend(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)

渲染顺序:
  1. DisplayRenderer.render(displayModel, isPost=false)  // 坐标网格(后层)
  2. StellarRenderer.render(stellarModel)                 // 天体
  3. DisplayRenderer.render(displayModel, isPost=true)    // 坐标网格(前层)
  4. LandscapeRenderer.render(landscapeModel)             // 地景

恢复GL状态
```

### 核心渲染：`StellarRenderer.render()`

```
LayerRHelper = new LayerRHelper(info, shaders)

// 1. 预处理 — 绑定FBO
postProcessor.preProcess()
  → 绑定 frame1 (RGBE FBO)
  → 清空

// 2. 设置混合模式
shadeModel(GL_SMOOTH)
blendFunc(GL_ONE, GL_ONE)  // 加法混合

// 3. 大气准备
AtmosphereRenderer.render(atmModel, Prepare, info)
  → 保存当前FBO
  → 绑定 stellar FBO (RGB32F)
  → 清空
  → 计算大气折射边界修正
  → 旋转矩阵修正视锥体

// 4. Source Pass — 点光源
StellarPhasedRenderer.render(layersModel, Source, layerInfo)
  → 遍历所有层
  → 每层: layerRenderer.acceptPass(Source)?
  → 如果接受: preRender → 遍历对象 → renderPoint() → postRender

// 5. 切换到Opaque Pass
enableDepth(), depthMask(true), disableBlend()

// 6. Opaque Pass — 不透明球体
StellarPhasedRenderer.render(layersModel, Opaque, layerInfo)
  → 太阳: 纹理球体网格渲染
  → 月亮: 带逐顶点光照的纹理球体

// 7. 切换回加法混合
disableDepth(), depthMask(false)
enableBlend(GL_ONE, GL_ONE)
shadeModel(GL_FLAT)

// 8. DominateScatter Pass — 大气散射
AtmosphereRenderer.render(atmModel, SetupDominateScatter, info)
  → 先做消光 (blendFunc(GL_ZERO, GL_SRC_COLOR))
  → 绑定大气散射shader
  → 设置dominateRenderer回调

// 9. 渲染散射贡献
StellarPhasedRenderer.render(layersModel, DominateScatter, layerInfo)
  → 太阳: renderDominate(pos, 1, 1, 1)
  → 月亮: renderDominate(pos, domination, domination, domination)

// 10. 大气终结
AtmosphereRenderer.render(atmModel, Finalize, info)
  → 恢复原始FBO
  → 绑定折射shader
  → 应用大气折射后处理
  → 渲染stellar FBO到屏幕

// 11. 后处理
postProcessor.postProcess(info)
  → Scope效果 (模糊 + 色彩校正)
  → 亮度查询 (mipmap降采样)
  → HDR→LDR色调映射
  → Linear→sRGB转换
```

---

## Shader 系统

### 顶点着色器

**`point.vsh`** — 恒星点光源
```glsl
#version 120
varying vec4 color;
void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    color = gl_Color;
    gl_TexCoord[0] = gl_MultiTexCoord0;
}
```

**`textured.vsh`** — 纹理对象
```glsl
#version 120
varying vec4 color;
void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    color = gl_Color;
    gl_TexCoord[0] = gl_MultiTexCoord0;
}
```

### 大气着色器

**`atmosphere_single.vsh`** — 大气散射（核心）

Uniform参数:
- `lightDir` — 光源方向
- `lightColor` — 光源颜色
- `cameraHeight` — 相机高度（标度高度单位）
- `outerRadius` / `innerRadius` — 外/内半径
- `nSamples` — 采样数
- `extinctionFactor` — 消光因子 (R/V/B)

核心算法:
```glsl
// 气团计算 (含折射修正)
float airmass(float cosAngleToZenith, float viewRadiusScaled) {
    viewRadiusScaled *= 7.0 / 6.0;  // 折射修正
    float scale = cosAngle² * viewRadiusScaled / 2.0;
    if (scale > 12.0 && cosAngle > 0)
        return 1.0 / cosAngle;  // 天顶近似
    else
        return airmassFactor(viewRadiusScaled) * calcScale(scale, sgn);
}

// 散射积分循环
for (int i = 0; i < nSamples; i++) {
    float radiusSample = length(v3SamplePoint);
    float depthFactor = exp(innerRadius - radiusSample);
    float cosLightAngle = dot(lightDir, v3SamplePoint) / radiusSample;
    
    // 阴影检测：光线是否被行星遮挡
    depthFactor *= float(cosLightAngle >= 0.0 || 
        radius² * (1 - cosLightAngle²) >= innerRadius²);
    
    float depthCurrent = depthCamera + 
        (airmass(cosLightAngle) - airmass(cosCameraAngle)) * depthFactor;
    
    vec3 v3Extincted = exp(-depthCurrent * extinctionFactor);
    integratedScatterColor += max(v3Extincted * depthFactor * fScaledLength, 0.0);
    
    v3SamplePoint += v3SampleRay;
}

scatteringColor4.rgb = integratedScatterColor * lightColor;
```

**`atmosphere_extinction.vsh`** — 大气消光

```glsl
// 简化版：只计算光线穿过大气的总消光
float airmassEnd = airmass(dot(v3End, v3Ray) / lenEnd, lenEnd);
float depthEnd = airmassEnd * exp(innerRadius - lenEnd);

alpha4.rgb = weatherAlpha * exp((depthEnd - depthCamera) * extinctionFactor);
```

**`atmosphere_refraction.vsh`** — 大气折射

```glsl
// 屏幕坐标 → 世界坐标
vec3 sCoord = gl_MultiTexCoord0.xyz - vec3(0.5, 0.5, 0.0);
sCoord.z = 1.0;
sCoord *= relative;

// 仰角旋转
vec3 wCoord = vec3(sCoord);
wCoord.y = sCoord.y * cos(pitch) + sCoord.z * sin(pitch);
wCoord.z = -sCoord.y * sin(pitch) + sCoord.z * cos(pitch);

// Garfinkel折射公式
float h = asin(wCoord.y / length(wCoord));
float ref = radians(1.0/60.0) / tan(radians(degrees(h) + 7.31/(degrees(h) + 4.4))) 
            - preRotated;

// 应用折射偏移
float d = atan(wCoord.x, wCoord.z);
vec3 wPos = vec3(cos(h-ref)*sin(d), sin(h-ref), cos(h-ref)*cos(d));

// 世界坐标 → 屏幕坐标
vec3 sPos = vec3(wPos);
sPos.y = wPos.y * cos(pitch) - wPos.z * sin(pitch);
sPos.z = wPos.y * sin(pitch) + wPos.z * cos(pitch);
sPos = sPos / sPos.z;
sPos /= relative;
sPos += vec3(0.5, 0.5, 0.0);
```

### 后处理 Shader

**Scope (模糊 + 色彩)**:
- 双pass (X轴模糊 + Y轴模糊)
- `brightnessMult` — 色彩校正乘数
- `resDirection` — 模糊方向和分辨率

**SkyToQueried (亮度提取)**:
- `relative` — 相对宽高比
- 降采样到mipmap用于亮度查询

**HDRtoLDR (色调映射)**:
- `brScale` — 亮度缩放因子
- `brScale = clamp(pow(4.5 * brightness, 0.5) * 10.0, 1.0, 1000.0)`

**LinearToSRGB (色彩空间转换)**:
- 线性RGB → sRGB伽马校正

---

## 点光源渲染 (`LayerRHelper`)

### 面向相机的四边形

```java
// 计算相机局部坐标轴
Matrix3 transformer = rotation(0, 0, 1, 270° - yaw) * rotation(0, 1, 0, -pitch)
xAxis = transformer * (0, relativeWidth/displayWidth, 0)
yAxis = transformer * (0, 0, relativeHeight/displayHeight)

// 渲染四边形 (4个顶点)
renderPoint(pos, length, r, g, b):
  // 左下
  vertex(pos + (xAxis - yAxis) * length, tex(1,0), color(r,g,b))
  // 左上
  vertex(pos + (xAxis + yAxis) * length, tex(1,1), color(r,g,b))
  // 右上
  vertex(pos + (-xAxis + yAxis) * length, tex(0,1), color(r,g,b))
  // 右下
  vertex(pos + (-xAxis - yAxis) * length, tex(0,0), color(r,g,b))
```

### 亮度归一化

```java
pointArea = relativeWidth * relativeHeight / displayWidth / displayHeight
// pointArea 是一个像素的立体角 (rad²)

multiplier = SURF_MULTIPLIER / pointArea
// SURF_MULTIPLIER = 2π * DEFAULT_RESOLUTION²
// DEFAULT_RESOLUTION = 人眼分辨率 (弧度)
```

---

## 太阳渲染 (`SunRenderer`)

### 渲染流程

```
Opaque Pass:
  1. 绑定太阳表面纹理 (sun.png)
  2. 绑定纹理shader
  3. 开始 GL_QUADS (POSITION_TEX_COLOR_F_NORMAL)
  4. 遍历球面网格 (longn × latn)
     - 每个四边形: 4个顶点
     - 位置: sunPos[long][lat] (缩放到 DEEP_DEPTH * 0.8)
     - 纹理: (long/longn, 1-lat/latn)
     - 颜色: (4830000, 4830000, 4830000) — 太阳亮度
     - 法线: sunNormal[long][lat]
  5. 结束绘制

DominateScatter Pass:
  → renderDominate(appPos, 1.0, 1.0, 1.0)
```

### 太阳表面位置 (`Sun.posLocalSun`)

```java
posLocalSun(longitude, latitude):
  longp = toRadians(longitude + rotation)  // rotation = 14.7 * 365.2422 * year
  lat = toRadians(latitude)
  
  result = (0, 0, 1) * sin(lat)
         + (1, 0, 0) * cos(lat) * cos(longp)
         + (0, 1, 0) * cos(lat) * sin(longp)
  result *= radius
```

---

## 月亮渲染 (`MoonRenderer`)

### 渲染流程

```
Opaque Pass:
  1. 绑定月面纹理 (lune.png)
  2. 开始 GL_QUADS (POSITION_TEX_COLOR_F_NORMAL)
  3. 遍历球面网格
     - 位置: pos[long][lat] (缩放到 DEEP_DEPTH * 0.5)
     - 纹理: (long/longn + 0.5, 1-lat/latn)
     - 颜色: surfBr[long][lat] — 逐顶点光照
     - 法线: normal[long][lat]
  4. 结束绘制

DominateScatter Pass:
  → renderDominate(appPos, domination, domination, domination)
```

### 月面光照 (`Moon.illumination`)

```java
illumination(p):
  // p 是月面某点的位置向量
  // sunPos 是从地球看太阳的方向
  return -sunPos.dot(p) / (|sunPos| * |p|) * brightness
```

### 月球位置 (`Moon.posLocalM`)

```java
posLocalM(longitude, latitude):
  longp = toRadians(longitude + rotation)  // rotation = mean_mot * year
  lat = toRadians(latitude)
  
  result = Pole * sin(lat)
         + PrMer0 * cos(lat) * cos(longp)
         + East * cos(lat) * sin(longp)
  result *= radius
```

---

## 恒星渲染

### 数据加载 (`LayerBrStar`)

```
从 /data/bsc5.dat 加载耶鲁亮星星表
每条记录 198 字节:
  - 字节 4-14: 恒星名称
  - 字节 75-89: 赤经/赤纬 (J2000)
  - 字节 103+: 视星等
  - 字节 109-113: B-V色指数

赤道→黄道变换:
  EqtoEc = rotationX(-0.4090926)  // 轴倾角 23.44°
```

### 渲染缓存 (`StarRenderCache`)

```java
updateCache(star, info):
  // 1. 投影到地面坐标
  ref = star.pos  // 黄道单位向量
  info.coordinate.getProjectionToGround().transform(ref)
  
  // 2. 缩放到天空深度
  pos = ref * DEEP_DEPTH
  
  // 3. 获取颜色
  color = StarColor.getColor(star.B_V)
  
  // 4. 计算亮度 (含闪烁)
  alpha = getBrightnessFromMag(turbulance() + star.mag)
  red = alpha * color.r / 255
  green = alpha * color.g / 255
  blue = alpha * color.b / 255
```

### 渲染 (`StarRenderer`)

```java
render(cache, pass, info):
  multiplier = getMultFromArea(info.pointArea())
  info.renderPoint(cache.pos, DEEP_DEPTH,
    cache.red * multiplier,
    cache.green * multiplier,
    cache.blue * multiplier)
```

---

## 银河渲染

### 球面网格生成 (`MilkywayRenderCache`)

```
for longc in 0..longn:
  for latc in 0..latn:
    // 球面坐标
    spCoord = (longc*360/longn, 180*latc/latn - 90)
    
    // 赤道→黄道
    vec = spCoord.getVec()
    EqtoEc.transform(vec)
    
    // 投影到地面
    coordinate.getProjectionToGround().transform(vec)
    
    milkywayNormal[longc][latc] = vec
```

### 渲染 (`MilkywayRenderer`)

```
绑定银河纹理 (milkyway.png)
遍历球面网格:
  每个四边形:
    位置 = normal * DEEP_DEPTH
    纹理 = (long/longn, lat/latn)
    亮度 = brightness * getBrightnessFromMag(4.5)
```

---

## 深空天体

### 数据来源
- JSON文件: `/assets/stellarium/deepsky/messier/*.json`
- 纹理: 梅西耶天体PNG (m31, m42, m51等)

### 渲染
- Source Pass 渲染
- 使用位置工具 (`PositionUtil`) 处理HMS/DMS坐标

---

## 大气模型详解

### 大气散射渲染器 (`AtmosphereRenderer`)

**FBO管理**:
- `stellar` FBO: RGB32F格式，用于累积散射结果
- 屏幕分区网格: `fragScreen × fragScreen` 四边形
- 天球网格: `fragLong × fragLat` 四边形

**渲染Pass**:
1. **Prepare**: 绑定stellar FBO，修正视锥体
2. **SetupDominateScatter**: 
   - 先做消光 (乘法混合)
   - 再设置散射shader，创建dominate回调
3. **Finalize**: 恢复FBO，应用折射shader

**折射边界修正**:
```java
getBoundaryRefraction(info):
  boundHeight = atan(relativeHeight / 2)
  height1 = -viewerPitch + boundHeight
  height2 = -viewerPitch - boundHeight
  ref1 = height1 - refraction(height1)
  ref2 = height2 - refraction(height2)
  return (ref1 + ref2) / 2
```

---

## 后处理管线 (`PostProcess`)

### FBO结构
- `frame1`: RGBA8 FBO (中间结果)
- `frame2`: RGBA8 FBO (中间结果)
- `brQuery`: RGB16F FBO (亮度查询，带mipmap)

### 处理流程

```
1. Scope效果 (模糊):
   frame1 → frame2 (X轴模糊 + 色彩校正)
   frame2 → frame1 (Y轴模糊 + 色彩校正)
   
   模糊参数:
     resolution = DEFAULT_RESOLUTION / multiplyingPower
     resDir.x = resolution / relativeWidth  (X pass)
     resDir.y = resolution / relativeHeight  (Y pass)

2. 亮度查询 (每50ms):
   frame1 → brQuery (skyToQueried shader)
   brQuery → generateMipmap()
   glGetTexImage(mipmap最高层) → PBO
   readBr = 读取蓝色通道
   brightness = readBr * 1000 / screenRatio
   brightness += (currentBrightness - brightness) * 0.1  // 平滑

3. HDR→LDR:
   frame1 → frame2
   brScale = clamp(pow(4.5 * brightness, 0.5) * 10.0, 1.0, 1000.0)

4. Linear→sRGB:
   frame2 → 屏幕
```

---

## 坐标网格显示

### 三种网格类型

| 类型 | 配置键 | 坐标系 |
|------|--------|--------|
| `HorGridType` | Horizontal_Coordinate_Grid | 地平坐标 |
| `EqGridType` | Equatorial_Coordinate_Grid | 赤道坐标 |
| `EcGridType` | Ecliptic_Coordinate_Grid | 黄道坐标 |

每个网格有:
- `Settings` — 网格密度、可见性
- `Cache` — 网格顶点缓存
- `Renderer` — 渲染逻辑

---

## HUD 时钟 (`OverlayClock`)

### 功能
- 显示当前天文时间
- 支持 HH:MM / Tick / AM/PM 格式
- 可锁定/解锁位置
- 动画滑入/滑出效果
- 聚焦模式显示额外控制器

### 时间显示
```java
CelestialPeriod periodDay = PeriodHelper.getDayPeriod(world)
CelestialPeriod periodYear = PeriodHelper.getYearPeriod(world)
```

---

## 资源清单

### 天体纹理
- `stellar/sun.png` — 太阳表面
- `stellar/lune.png` — 月面
- `stellar/halo.png` — 太阳光晕
- `stellar/haloLune.png` — 月亮光晕
- `stellar/star.png` — 恒星/行星点
- `stellar/milkyway.png` — 银河纹理

### 深空天体纹理
- `deepsky/messier/m31.png` — 仙女座星系
- `deepsky/messier/m42dumont.png` — 猎户座星云
- `deepsky/messier/m51.png` — 涡状星系
- `deepsky/messier/crab_nebula.png` — 蟹状星云
- `deepsky/messier/ring_nebula.png` — 环状星云
- 等等

### Shader文件
- `shaders/point.vsh/psh` — 点光源
- `shaders/textured.vsh/psh` — 纹理对象
- `shaders/atmosphere/atmosphere_single.vsh/psh` — 大气散射
- `shaders/atmosphere/atmosphere_extinction.vsh/psh` — 消光
- `shaders/atmosphere/atmosphere_refraction.vsh/psh` — 折射
- `shaders/postprocess/scope.vsh/psh` — 模糊效果
- `shaders/postprocess/hdr_to_ldr.vsh/psh` — 色调映射
- `shaders/postprocess/linear_to_srgb.vsh/psh` — 色彩空间
- `shaders/postprocess/sky_to_queried.vsh/psh` — 亮度提取

---

## 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEEP_DEPTH` | 100.0 | 天球渲染距离 |
| `MAG_BASE` | 10^0.4 ≈ 2.512 | 波格森比率 |
| `MAG_SUN` | -26.74 | 太阳视星等 |
| `MAG_UPPER_LIMIT` | -0.5 | 亮度1.0对应星等 |
| `DEFAULT_RESOLUTION` | ~0.0003 rad | 人眼分辨率 |
| `SURF_MULTIPLIER` | 2π × resolution² | 面积归一化因子 |
| `SUN_BRIGHTNESS` | 4,830,000 | 太阳渲染亮度 |
| `rotationSpeed` | 14.7 × 365.2422 | 太阳自转速度 (°/年) |

---

## 与 B3M 的技术差异总结

| 方面 | B3M | StellarSky |
|------|-----|------------|
| **渲染目标** | 修改原版天空 | 完全替换天空渲染 |
| **GL管线** | 固定管线 | 可编程管线 (GLSL 120) |
| **FBO** | 无 | 多FBO后处理 (HDR/bloom/折射) |
| **大气** | 简单亮度乘数 | 物理级散射/消光/折射 |
| **恒星** | 无 | 9110颗真实恒星 + 闪烁 |
| **太阳系** | 太阳+月亮角度 | 完整8行星 + 开普勒轨道 |
| **渲染方式** | 天体作为2D纹理 | 太阳/月亮作为3D球体 |
| **颜色空间** | sRGB | 线性RGB → sRGB转换 |
| **亮度处理** | 简单乘数 | HDR + 自适应色调映射 |
| **依赖** | 无 | StellarAPI库 |
