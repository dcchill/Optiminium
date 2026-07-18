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
uniform samplerBuffer TransformPalette;
uniform samplerBuffer BonePalette;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform vec3 PersistentCameraPos;
in float InstanceTransformIndex;
in vec2 InstanceLight;
in vec2 InstanceOverlay;
in vec4 InstanceColor;
in float InstancePaletteOffset;
in float InstanceWorldSpace;
in vec4 InstanceDirectMatrix0;
in vec4 InstanceDirectMatrix1;
in vec4 InstanceDirectMatrix2;
in vec4 InstanceDirectMatrix3;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    bool directMatrix = InstancePaletteOffset < -1.5;
    bool posedInstance = directMatrix || InstancePaletteOffset >= 0.0;
    mat4 instanceModel;
    if (directMatrix) {
        instanceModel = mat4(InstanceDirectMatrix0,
                             InstanceDirectMatrix1,
                             InstanceDirectMatrix2,
                             InstanceDirectMatrix3);
    } else if (InstancePaletteOffset >= 0.0) {
        int matrixTexel = (int(InstancePaletteOffset) + UV1.x) * 4;
        instanceModel = mat4(texelFetch(BonePalette, matrixTexel),
                             texelFetch(BonePalette, matrixTexel + 1),
                             texelFetch(BonePalette, matrixTexel + 2),
                             texelFetch(BonePalette, matrixTexel + 3));
    } else {
        int transformTexel = int(InstanceTransformIndex) * 4;
        instanceModel = mat4(texelFetch(TransformPalette, transformTexel),
                             texelFetch(TransformPalette, transformTexel + 1),
                             texelFetch(TransformPalette, transformTexel + 2),
                             texelFetch(TransformPalette, transformTexel + 3));
    }
    vec4 positioned = instanceModel * vec4(Position, 1.0);
    if (InstanceWorldSpace > 0.5) positioned.xyz -= PersistentCameraPos;
    vec4 viewPosition = ModelViewMat * positioned;
    gl_Position = ProjMat * viewPosition;
    // Cached positions are model-local; vanilla buffered BER vertices have already had the
    // instance pose applied on the CPU. Compute fog from the equivalent transformed position.
    vertexDistance = fog_distance(viewPosition.xyz, FogShape);
    // Bone palettes contain vanilla ModelPart rigid/uniform transforms. Their normalized
    // upper-left matrix is the exact normal transform and avoids a per-vertex matrix inverse.
    // Resident block-entity transforms retain the general inverse-transpose path.
    vec3 instanceNormal = posedInstance
        ? normalize(mat3(instanceModel) * Normal)
        : normalize(transpose(inverse(mat3(instanceModel))) * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, instanceNormal, Color * InstanceColor);
	// 0xffff/0xffff is reserved for adapters whose vanilla renderer emits exact
	// per-vertex light values (multi-tile paintings). Ordinary instances retain
	// the cheaper uniform instance-light path.
	ivec2 lightCoords = InstanceLight.x > 65500.0 ? UV2 : ivec2(InstanceLight);
	lightMapColor = texelFetch(Sampler2, lightCoords / 16, 0);
    overlayColor = texelFetch(Sampler1, ivec2(InstanceOverlay), 0);
    texCoord0 = UV0;
}
