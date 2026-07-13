#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
in vec4 InstanceModel0;
in vec4 InstanceModel1;
in vec4 InstanceModel2;
in vec4 InstanceModel3;
in vec2 InstanceLight;
in vec2 InstanceOverlay;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    mat4 instanceModel = mat4(InstanceModel0, InstanceModel1, InstanceModel2, InstanceModel3);
    vec4 viewPosition = ModelViewMat * instanceModel * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    // Cached positions are model-local; vanilla buffered BER vertices have already had the
    // instance pose applied on the CPU. Compute fog from the equivalent transformed position.
    vertexDistance = fog_distance(viewPosition.xyz, FogShape);
    vec3 instanceNormal = normalize(mat3(instanceModel) * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, instanceNormal, Color);
    lightMapColor = texelFetch(Sampler2, ivec2(InstanceLight) / 16, 0);
    overlayColor = texelFetch(Sampler1, ivec2(InstanceOverlay), 0);
    texCoord0 = UV0;
}
