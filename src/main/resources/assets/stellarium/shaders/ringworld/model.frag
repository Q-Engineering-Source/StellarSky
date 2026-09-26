#version 400 compatibility

uniform vec4 uModelViewport;
uniform int uModelPass;
uniform sampler2D uModelHigh;
uniform sampler2D uModelLow;
uniform sampler2D uModelSurface;
uniform sampler2D uModelCloud;
uniform float uModelWeather;
// 0 is the accepted C38 preview; 1 ground and 2 cloud are the connected layers.
uniform int uModelLayer;
uniform float uModelMinimum;
uniform vec2 uModelFarBand;
uniform sampler2D uModelGroundHigh;
uniform sampler2D uModelGroundLow;
flat in dvec4 vModelPlane;
in vec2 vModelUv;
in vec3 vModelPhysical;
uniform int uModelLightMode;
uniform vec4 uModelLightBandHigh;
uniform vec4 uModelLightBandLow;
uniform vec4 uModelLightBoundsHigh;
uniform vec4 uModelLightBoundsLow;
uniform vec4 uModelLightDirectionHigh;
uniform vec4 uModelLightDirectionLow;
uniform int uModelPreviewCount;
uniform vec4 uModelPreviewBounds[48];
uniform int uModelPreviewLevels[48];
uniform int uModelTerrainLevel;
#include "dh_shadow_kernel"

vec3 modelUnproject(float z) {
    vec2 ndc = ((gl_FragCoord.xy-uModelViewport.xy)/uModelViewport.zw)*2.0-1.0;
    vec4 eye = gl_ProjectionMatrixInverse*vec4(ndc,z,1.0);
    eye /= eye.w;
    vec4 local = gl_ModelViewMatrixInverse*vec4(eye.xyz,1.0);
    return local.xyz/local.w;
}

void main() {
    vec3 ray = normalize(modelUnproject(0.0)-modelUnproject(-1.0));
    double denominator = dot(vModelPlane.xyz,dvec3(ray));
    if (abs(denominator)<1.0e-15LF) discard;
    double lambda = -(vModelPlane.w+dot(vModelPlane.xyz,dvec3(uSSEyeRelative)))/denominator;
    if (isnan(lambda) || isinf(lambda) || lambda < double(uModelMinimum)) discard;
    dvec3 hit = dvec3(uSSEyeRelative)+dvec3(ray)*lambda;
    // Only an uploaded seed mesh owns this footprint. Missing data never punches a hole.
    // All private distance passes use the same rejection as final color.
    if(uModelLayer==1 && uModelPreviewCount>0 && lambda<1048576.0LF) {
        dvec3 physical=ssCurvedPhysicalPointD(hit);
        for(int i=0;i<uModelPreviewCount;i++) {
            dvec4 bounds=dvec4(uModelPreviewBounds[i]);
            if(physical.x>=bounds.x && physical.z>=bounds.y && physical.x<bounds.z && physical.z<bounds.w) discard;
        }
    }
    if(uModelLayer==3 && uModelTerrainLevel>6) {
        for(int i=0;i<uModelPreviewCount;i++) {
            vec4 bounds=uModelPreviewBounds[i];
            if(uModelPreviewLevels[i]<uModelTerrainLevel && vModelPhysical.x>=bounds.x && vModelPhysical.z>=bounds.y
                    && vModelPhysical.x<bounds.z && vModelPhysical.z<bounds.w) discard;
        }
    }
    dvec4 clip = dmat4(gl_ProjectionMatrix)*dmat4(gl_ModelViewMatrix)*dvec4(hit,1.0LF);
    if (!(clip.w>0.0LF)) discard;
    double ndc = clip.z/clip.w;
    if (isnan(ndc) || isinf(ndc) || ndc < -1.0LF) discard;
    vec4 encoded = ssEncodeOwnMediaDistance(lambda);
    ivec2 pixel=ivec2(gl_FragCoord.xy-uModelViewport.xy);
    vec4 cloud=texture(uModelCloud,vModelUv);
    if (uModelLayer==2) {
        int x=pixel.x&3, y=pixel.y&3;
        float rank=(float(((x&1)*2+(y&1))*4+(x&2)+((y&2)>>1))+0.5)/16.0;
        float farWeight=smoothstep(uModelFarBand.x,uModelFarBand.y,float(lambda));
        if (rank>=farWeight || cloud.a<0.5) discard;
    }
    // Alpha remains one even in private reductions, including under Actinium's injected alpha guard.
    if (uModelPass==0) { gl_FragData[0]=vec4(encoded.r,0.0,0.0,1.0); return; }
    if (encoded.r!=texelFetch(uModelHigh,pixel,0).r) discard;
    if (uModelPass==1) { gl_FragData[0]=vec4(encoded.g,0.0,0.0,1.0); return; }
    if (encoded.g!=texelFetch(uModelLow,pixel,0).r) discard;
    // These textures have the original viewport; reductions never sample them.
    if (ssSelectedBoardPrecedes(lambda) || lambda>double(ssDistantOpaqueLimit(ray))) discard;
    if (uModelLayer==2) {
        float groundHigh=texelFetch(uModelGroundHigh,pixel,0).r;
        if (encoded.r>groundHigh || (encoded.r==groundHigh && encoded.g>=texelFetch(uModelGroundLow,pixel,0).r)) discard;
    }
    // This late ground settles after the pass's opaque board/cloud media, so a media
    // fragment on the same ray that is nearer, or exactly as near, keeps the pixel.
    // ssOwnMediaOpaqueLimit() reads the production (high, residual) encoding written
    // by own_media.glsl and returns 1.0e15 when the attachment is inactive or empty,
    // so a missing or mismatched capture can only disable this discard.
    if (lambda >= ssOwnMediaOpaqueLimit()) discard;
    float depth=gl_DepthRange.near+(float(ndc)*0.5+0.5)*gl_DepthRange.diff;
    gl_FragDepth=clamp(depth,min(gl_DepthRange.near,gl_DepthRange.far),max(gl_DepthRange.near,gl_DepthRange.far));
    vec3 ground=texture(uModelSurface,vModelUv).rgb;
    vec3 color=uModelLayer==0?mix(ground,cloud.rgb,cloud.a):(uModelLayer==1?ground:cloud.rgb);
    if (uModelLayer==3) color=mix(vec3(0.035,0.12,0.23),vec3(0.22,0.32,0.12),step(0.5,vModelUv.x));
    float grazing=1.0-clamp(abs(dot(normalize(vec3(vModelPlane.xyz)),ray)),0.0,1.0);
    float haze=0.10+0.32*grazing*grazing;
    // Local transfer owns [0,2048m]. This remote radiance proxy starts beyond that endpoint.
    if (uModelLayer!=0) haze*=1.0-exp(-max(float(lambda)-2048.0,0.0)/16384.0);
    color=mix(color,vec3(0.32,0.49,0.72),haze)*(1.0-0.22*uModelWeather);
    if(uModelLayer==3) {
        dvec4 b=dvec4(uModelLightBandHigh)+dvec4(uModelLightBandLow);
        dvec4 h=dvec4(uModelLightBoundsHigh)+dvec4(uModelLightBoundsLow);
        dvec4 d=dvec4(uModelLightDirectionHigh)+dvec4(uModelLightDirectionLow);
        double shade=ssDhSkySubtraction(uModelLightMode,double(vModelPhysical.x),double(vModelPhysical.y),double(vModelPhysical.z),
                b.x,b.y,b.z,b.w,h.x,h.y,h.z,h.w,d.x,d.y,d.z,d.w);
        color*=0.035+0.965*(1.0-float(shade)/15.0);
    }
    gl_FragData[0]=vec4(color,1.0);
    gl_FragData[1]=encoded;
}
