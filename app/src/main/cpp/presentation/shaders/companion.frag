#version 450
// X14U 3x 50 MP viewfinder: the camera-processed PRIVATE companion through an immutable YCbCr sampler, aspect-fit and
// turned like present.frag (same quarterTurns convention). The camera's output is display-encoded; the swapchain is
// UNORM, so it is written as is.
layout(set=0,binding=0) uniform sampler2D srcImage;
layout(push_constant) uniform PC { uint quarterTurns; float srcAspect; float dstAspect; } pc;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;

vec2 sourceUv(vec2 q) {
    if(pc.quarterTurns==1u) return vec2(q.y,1.0-q.x);
    if(pc.quarterTurns==2u) return vec2(1.0-q.x,1.0-q.y);
    if(pc.quarterTurns==3u) return vec2(1.0-q.y,q.x);
    return q;
}

void main(){
    vec2 q=uv;
    if(pc.dstAspect > pc.srcAspect){
        float w=pc.srcAspect/pc.dstAspect;
        q.x=(uv.x-0.5)/w+0.5;
        if(q.x<0.0||q.x>1.0){outColor=vec4(0,0,0,1);return;}
    } else {
        float h=pc.dstAspect/pc.srcAspect;
        q.y=(uv.y-0.5)/h+0.5;
        if(q.y<0.0||q.y>1.0){outColor=vec4(0,0,0,1);return;}
    }
    outColor=vec4(texture(srcImage,sourceUv(q)).rgb,1.0);
}
