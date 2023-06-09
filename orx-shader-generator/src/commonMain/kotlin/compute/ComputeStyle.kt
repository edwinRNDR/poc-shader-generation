package org.openrndr.extra.shadergenerator.compute

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.draw.font.BufferAccess
import org.openrndr.draw.font.BufferFlag
import org.openrndr.internal.Driver
import org.openrndr.math.*

data class ComputeStructure(
    val structDefinitions: String? = null,
    val uniforms: String? = null,
    val buffers: String? = null,
    val computeTransform: String,
    val computePreamble: String,
    val workGroupSize: IntVector3
)

private val BufferAccess.glsl: String
    get() = when (this) {
        BufferAccess.READ -> "readonly"
        BufferAccess.READ_WRITE -> ""
        BufferAccess.WRITE -> "writeonly"
    }

private val BufferFlag.glsl: String
    get() = when (this) {
        BufferFlag.RESTRICT -> "restrict"
        BufferFlag.COHERENT -> "coherent"
        BufferFlag.VOLATILE -> "volatile"
    }



class ComputeStyle : StyleParameters, StyleBufferBindings, StyleImageBindings {
    var computePreamble: String = ""
    var computeTransform: String = ""
    var workGroupSize = IntVector3(1, 1, 1)
    private var dirty = true
    override var parameterValues: MutableMap<String, Any> = mutableMapOf()
    override var parameterTypes: ObservableHashmap<String, String> = ObservableHashmap(mutableMapOf()) { dirty = true }

    override var bufferValues = mutableMapOf<String, Any>()
    override val buffers = mutableMapOf<String, String>()
    override val bufferTypes = mutableMapOf<String, String>()
    override val bufferAccess = mutableMapOf<String, BufferAccess>()
    override val bufferFlags: MutableMap<String, Set<BufferFlag>> = mutableMapOf()

    override val imageTypes: MutableMap<String, String> = mutableMapOf()
    override val imageValues: MutableMap<String, ImageBinding> = mutableMapOf()
    override val imageAccess: MutableMap<String, ImageAccess> = mutableMapOf()

}

private fun mapTypeToImage(name: String, type: String, access: ImageAccess): String {
    val tokens = type.split(",")
    val u = "uniform"


    val subtokens = tokens[0].split(" ")
    return when (subtokens[0]) {
        "Image2D", "Image3D", "ImageCube", "Image2DArray", "ImageBuffer", "ImageCubeArray" -> {
            val sampler = tokens[0].take(1).lowercase() + tokens[0].drop(1)

            val colorFormat = ColorFormat.valueOf(tokens[1])
            val colorType = ColorType.valueOf(tokens[2])

            val samplerType = when (colorType.colorSampling) {
                ColorSampling.SIGNED_INTEGER -> "i"
                ColorSampling.UNSIGNED_INTEGER -> "u"
                else -> ""
            }
            val layout = imageLayout(colorFormat, colorType)
            when (access) {
                ImageAccess.READ -> "layout($layout) readonly $u $samplerType$sampler p_$name;"
                ImageAccess.READ_WRITE -> "layout($layout) $u $samplerType$sampler p_$name;"
                ImageAccess.WRITE -> "layout($layout) writeonly $u $samplerType$sampler p_$name;"
            }
        }
        else -> {
            error("unknown image type '${subtokens[0]}")
        }
    }
}


private fun mapTypeToUniform(type: String, name: String): String {
    val tokens = type.split(",")
    val arraySize = tokens.getOrNull(1)
    val u = "uniform"

    fun String?.arraySizeDefinition() = if (this == null) {
        ";"
    } else {
        "[$arraySize]; \n#define p_${name}_SIZE $arraySize"
    }

    val subtokens = tokens[0].split(" ")
    return when (subtokens[0]) {
        "struct" -> "$u ${subtokens[1]} p_$name${arraySize.arraySizeDefinition()}"
        else -> "$u ${shadeStyleTypeToGLSL(tokens[0])} p_$name${arraySize.arraySizeDefinition()}"
    }
}

private fun imageLayout(format: ColorFormat, type: ColorType): String {
    return when (Pair(format, type)) {
        Pair(ColorFormat.R, ColorType.UINT8) -> "r8"
        Pair(ColorFormat.R, ColorType.UINT8_INT) -> "r8u"
        Pair(ColorFormat.R, ColorType.SINT8_INT) -> "r8i"
        Pair(ColorFormat.R, ColorType.UINT16) -> "r16"
        Pair(ColorFormat.R, ColorType.UINT16_INT) -> "r16u"
        Pair(ColorFormat.R, ColorType.SINT16_INT) -> "r16i"
        Pair(ColorFormat.R, ColorType.UINT32_INT) -> "r32u"
        Pair(ColorFormat.R, ColorType.SINT32_INT) -> "r32i"
        Pair(ColorFormat.R, ColorType.FLOAT16) -> "r16f"
        Pair(ColorFormat.R, ColorType.FLOAT32) -> "r32f"

        Pair(ColorFormat.RG, ColorType.UINT8) -> "rg8"
        Pair(ColorFormat.RG, ColorType.UINT8_INT) -> "rg8u"
        Pair(ColorFormat.RG, ColorType.SINT8_INT) -> "rg8i"
        Pair(ColorFormat.RG, ColorType.UINT16) -> "rg16"
        Pair(ColorFormat.RG, ColorType.UINT16_INT) -> "rg16u"
        Pair(ColorFormat.RG, ColorType.SINT16_INT) -> "rg16i"
        Pair(ColorFormat.RG, ColorType.FLOAT16) -> "rg16f"
        Pair(ColorFormat.RG, ColorType.FLOAT32) -> "rg32f"

        Pair(ColorFormat.RGBa, ColorType.UINT8) -> "rgba8"
        Pair(ColorFormat.RGBa, ColorType.UINT8_INT) -> "rgba8u"
        Pair(ColorFormat.RGBa, ColorType.SINT8_INT) -> "rgba8i"
        Pair(ColorFormat.RGBa, ColorType.UINT16) -> "rgba16"
        Pair(ColorFormat.RGBa, ColorType.UINT16_INT) -> "rgba16u"
        Pair(ColorFormat.RGBa, ColorType.SINT16_INT) -> "rgba16i"
        Pair(ColorFormat.RGBa, ColorType.FLOAT16) -> "rgba16f"
        Pair(ColorFormat.RGBa, ColorType.FLOAT32) -> "rgba32f"
        else -> error("unsupported layout: $format $type")
    }
}

fun structureFromComputeStyle(computeStyle: ComputeStyle): ComputeStructure {
    fun structDefinitions(): String {
        val structs = computeStyle.parameterTypes.filterValues {
            it.startsWith("struct")
        } + computeStyle.bufferTypes.filterValues { it.startsWith("struct") }
        val structValues = structs.keys.map {
            if ((computeStyle.parameterValues[it]?:computeStyle.bufferValues[it]) is Array<*>) {
                @Suppress("UNCHECKED_CAST") val array = (computeStyle.parameterValues[it]?:computeStyle.bufferValues[it]) as Array<Struct<*>>
                Pair(it, array.first())
            } else {
                Pair(it, (computeStyle.parameterValues[it]?:
                ((computeStyle.bufferValues[it] as? StructuredBuffer<*>)?.struct ))!! as Struct<*>)
            }
        }
        val structProtoValues = structValues.distinctBy {
            it.second::class.simpleName
        }
        return structProtoValues.joinToString("\n") {
            it.second.typeDef((computeStyle.parameterTypes[it.first]?:computeStyle.bufferTypes[it.first])!!.split(" ")[1].split(",")[0])
        }
    }

    fun uniforms(): String {
        return computeStyle.parameterTypes.map { mapTypeToUniform(it.value, it.key) }.joinToString("\n")
    }

    fun buffers(): String {
        var bufferIndex = 2

        return computeStyle.bufferValues.map {
            val r = when (val v = it.value) {
                is StructuredBuffer<*> -> {
                    listOf(
                        "layout(std430, binding = $bufferIndex)",
                        (computeStyle.bufferFlags[it.key] ?: emptySet()).joinToString(" ") { it.glsl},
                        "${(computeStyle.bufferAccess[it.key] ?: BufferAccess.READ_WRITE).glsl}",
                        "buffer B_${it.key} { ${v.struct.typeDef("", true)} } b_${it.key};"
                    ).joinToString(" ")
                }
                is ShaderStorageBuffer -> "layout(std430, binding = $bufferIndex) buffer B_${it.key} { ${v.format.glslLayout} } b_${it.key};"
                is AtomicCounterBuffer -> "layout(binding = $bufferIndex, offset = 0) uniform atomic_uint b_${it.key}[${(it.value as AtomicCounterBuffer).size}];"

                else -> error("unsupported buffer type: $v")
            }
            bufferIndex++
            r
        }.joinToString("\n")
    }

    fun images(): String {
        return computeStyle.imageTypes.map { mapTypeToImage(it.key, it.value, computeStyle.imageAccess[it.key] ?: error("no image access for '${it.key}'")) }.joinToString("\n")
    }


    return ComputeStructure(
        structDefinitions = structDefinitions(),
        uniforms = uniforms(),
        buffers = listOf(buffers(), images()).joinToString("\n"),
        computeTransform = computeStyle.computeTransform,
        computePreamble = computeStyle.computePreamble,
        workGroupSize = computeStyle.workGroupSize
    )
}

private val BufferPrimitiveType.glslType: String
    get() {
        return when (this) {
            BufferPrimitiveType.BOOLEAN -> "bool"
            BufferPrimitiveType.INT32 -> "int"
            BufferPrimitiveType.UINT32 -> "uint"
            BufferPrimitiveType.FLOAT32 -> "float"
            BufferPrimitiveType.FLOAT64 -> "double"

            BufferPrimitiveType.VECTOR2_UINT32 -> "uvec2"
            BufferPrimitiveType.VECTOR2_BOOLEAN -> "bvec2"
            BufferPrimitiveType.VECTOR2_INT32 -> "ivec2"
            BufferPrimitiveType.VECTOR2_FLOAT32 -> "vec2"
            BufferPrimitiveType.VECTOR2_FLOAT64 -> "dvec2"

            BufferPrimitiveType.VECTOR3_UINT32 -> "uvec3"
            BufferPrimitiveType.VECTOR3_BOOLEAN -> "bvec3"
            BufferPrimitiveType.VECTOR3_INT32 -> "ivec3"
            BufferPrimitiveType.VECTOR3_FLOAT32 -> "vec3"
            BufferPrimitiveType.VECTOR3_FLOAT64 -> "dvec3"

            BufferPrimitiveType.VECTOR4_UINT32 -> "uvec4"
            BufferPrimitiveType.VECTOR4_BOOLEAN -> "bvec4"
            BufferPrimitiveType.VECTOR4_INT32 -> "ivec4"
            BufferPrimitiveType.VECTOR4_FLOAT32 -> "vec4"
            BufferPrimitiveType.VECTOR4_FLOAT64 -> "dvec4"

            BufferPrimitiveType.MATRIX22_FLOAT32 -> "mat2"
            BufferPrimitiveType.MATRIX33_FLOAT32 -> "mat3"
            BufferPrimitiveType.MATRIX44_FLOAT32 -> "mat4"
        }
    }


private val VertexElementType.glslType: String
    get() {
        return when (this) {
            VertexElementType.INT8, VertexElementType.INT16, VertexElementType.INT32 -> "int"
            VertexElementType.UINT8, VertexElementType.UINT16, VertexElementType.UINT32 -> "uint"
            VertexElementType.VECTOR2_UINT8, VertexElementType.VECTOR2_UINT16, VertexElementType.VECTOR2_UINT32 -> "uvec2"
            VertexElementType.VECTOR2_INT8, VertexElementType.VECTOR2_INT16, VertexElementType.VECTOR2_INT32 -> "ivec2"
            VertexElementType.VECTOR3_UINT8, VertexElementType.VECTOR3_UINT16, VertexElementType.VECTOR3_UINT32 -> "uvec3"
            VertexElementType.VECTOR3_INT8, VertexElementType.VECTOR3_INT16, VertexElementType.VECTOR3_INT32 -> "ivec3"
            VertexElementType.VECTOR4_UINT8, VertexElementType.VECTOR4_UINT16, VertexElementType.VECTOR4_UINT32 -> "uvec4"
            VertexElementType.VECTOR4_INT8, VertexElementType.VECTOR4_INT16, VertexElementType.VECTOR4_INT32 -> "ivec4"
            VertexElementType.FLOAT32 -> "float"
            VertexElementType.VECTOR2_FLOAT32 -> "vec2"
            VertexElementType.VECTOR3_FLOAT32 -> "vec3"
            VertexElementType.VECTOR4_FLOAT32 -> "vec4"
            VertexElementType.MATRIX22_FLOAT32 -> "mat2"
            VertexElementType.MATRIX33_FLOAT32 -> "mat3"
            VertexElementType.MATRIX44_FLOAT32 -> "mat4"
        }
    }

private val VertexElementType.glslVaryingQualifier: String
    get() {
        return when (this) {
            VertexElementType.INT8, VertexElementType.INT16, VertexElementType.INT32 -> "flat "
            VertexElementType.UINT8, VertexElementType.UINT16, VertexElementType.UINT32 -> "flat "
            VertexElementType.VECTOR2_UINT8, VertexElementType.VECTOR2_UINT16, VertexElementType.VECTOR2_UINT32 -> "flat "
            VertexElementType.VECTOR2_INT8, VertexElementType.VECTOR2_INT16, VertexElementType.VECTOR2_INT32 -> "flat "
            VertexElementType.VECTOR3_UINT8, VertexElementType.VECTOR3_UINT16, VertexElementType.VECTOR3_UINT32 -> "flat "
            VertexElementType.VECTOR3_INT8, VertexElementType.VECTOR3_INT16, VertexElementType.VECTOR3_INT32 -> "flat "
            VertexElementType.VECTOR4_UINT8, VertexElementType.VECTOR4_UINT16, VertexElementType.VECTOR4_UINT32 -> "flat "
            VertexElementType.VECTOR4_INT8, VertexElementType.VECTOR4_INT16, VertexElementType.VECTOR4_INT32 -> "flat "
            else -> ""
        }
    }


private val ShaderStorageFormat.glslLayout: String
    get() = elements.joinToString("\n") {
        when (it) {
            is ShaderStoragePrimitive -> {
                if (it.arraySize == 1) {
                    "${it.type.glslType} ${it.name};"
                } else {
                    "${it.type.glslType}[${it.arraySize}] ${it.name};"
                }
            }

            is ShaderStorageStruct -> {
                if (it.arraySize == 1) {
                    "${it.structName} ${it.name};"
                } else {
                    "${it.structName}[${it.arraySize}] ${it.name};"
                }
            }

            else -> ""
        }
    }


class ComputeStyleManager() {

    val shaders = mutableMapOf<ComputeStructure, ComputeShader>()

    fun shader(style: ComputeStyle, name: String): ComputeShader {

        val structure = structureFromComputeStyle(style)

        val shader = shaders.getOrPut(structure) {

            val code = """#version 450 core
layout(local_size_x = ${structure.workGroupSize.x}, local_size_y = ${structure.workGroupSize.y}, local_size_z = ${structure.workGroupSize.z}) in;

${structure.structDefinitions ?: ""}
${structure.uniforms ?: ""}
${structure.buffers ?: ""}

${structure.computePreamble}

void main() {
${structure.computeTransform.prependIndent("    ")}        
}"""
            Driver.instance.createComputeShader(code, name)
        }


        //shader.begin()
        var textureIndex = 2
        var imageIndex = 0
        var bufferIndex = 2
        run {
            for (it in style.bufferValues.entries) {
                when (val value = it.value) {
                    is StructuredBuffer<*> -> {
                        shader.buffer("B_${it.key}", value.ssbo)
                    }

                    is ShaderStorageBuffer -> {
                        shader.buffer("b_${it.key}", value)
                    }
                    is AtomicCounterBuffer -> {
                        shader.buffer("b_${it.key}[0]", value)
                    }

                    else -> error("unsupported buffer type $value")
                }
            }
        }

        fun setUniform(targetName: String, name: String, value: Any) {
            when (value) {
                is Boolean -> shader.uniform(targetName, value)
                is Int -> shader.uniform(targetName, value)
                is Float -> shader.uniform(targetName, value)
                is Double -> shader.uniform(targetName, value)
                is Matrix44 -> shader.uniform(targetName, value)
                is Matrix33 -> shader.uniform(targetName, value)
                is Vector4 -> shader.uniform(targetName, value)
                is Vector3 -> shader.uniform(targetName, value)
                is Vector2 -> shader.uniform(targetName, value)
                is ColorRGBa -> shader.uniform(targetName, value)
                is ColorBuffer -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is DepthBuffer -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is BufferTexture -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is Cubemap -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is ArrayTexture -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is ArrayCubemap -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is VolumeTexture -> {
                    value.bind(textureIndex)
                    shader.uniform(targetName, textureIndex)
                    textureIndex++
                }

                is ImageBinding -> {
                    shader.image(targetName, imageIndex, value)
                    imageIndex++
                }

                is DoubleArray -> {
                    shader.uniform(targetName, value.map { it.toFloat() }.toFloatArray())
                }

                is IntArray -> {
                    shader.uniform(targetName, value)
                }

                is Array<*> -> {
                    require(value.isNotEmpty())
                    when (value.firstOrNull()) {
//                        is Matrix44 -> {
//                            @Suppress("UNCHECKED_CAST")
//                            shader.uniform(targetName, value as Array<Matrix44>)
//                        }
//
//                        is Double -> {
//                            @Suppress("UNCHECKED_CAST")
//                            shader.uniform(targetName, value as Array<Double>)
//                        }
//
//                        is ColorRGBa -> {
//                            @Suppress("UNCHECKED_CAST")
//                            shader.uniform(targetName, value as Array<ColorRGBa>)
//                        }

                        is Vector4 -> {
                            @Suppress("UNCHECKED_CAST")
                            shader.uniform(targetName, value as Array<Vector4>)
                        }

                        is Vector3 -> {
                            @Suppress("UNCHECKED_CAST")
                            shader.uniform(targetName, value as Array<Vector3>)
                        }

                        is Vector2 -> {
                            @Suppress("UNCHECKED_CAST")
                            shader.uniform(targetName, value as Array<Vector2>)
                        }

                        is CastableToVector4 -> {
                            @Suppress("UNCHECKED_CAST")
                            shader.uniform(targetName, (value as Array<CastableToVector4>).map {
                                it.toVector4()
                            }.toTypedArray())
                        }

                        is Struct<*> -> {
                            for (i in 0 until value.size) {
                                setUniform("$targetName[$i]", "", value[i]!!)
                            }
                        }
                    }
                }

                is CastableToVector4 -> {
                    shader.uniform(targetName, value.toVector4())
                }

                is Struct<*> -> {
                    for (f in value.values.keys) {
                        setUniform("$targetName.$f", "", value.values.getValue(f))
                    }
                }

                else -> {
                    throw RuntimeException("unsupported value type ${value::class}")
                }
            }
        }

        run {
            for (it in style.parameterValues.entries) {
                setUniform("p_${it.key}", it.key, it.value)
            }
            for (it in style.imageValues.entries) {
                setUniform("p_${it.key}", it.key, it.value)
            }
        }

        return shader

    }


}

/**
 * inline fun <reified T : Struct<T>> ShadeStyleParameters.parameter(name: String, value: T) {
 *     parameterValues[name] = value
 *     parameterTypes[name] = "struct ${T::class.simpleName}"
 * }
 *
 */

fun computeStyle(builder:ComputeStyle.() -> Unit) : ComputeStyle {
    val computeStyle = ComputeStyle()
    computeStyle.builder()
    return computeStyle
}

val computeStyleManager = ComputeStyleManager()
fun ComputeStyle.execute(width: Int=1, height: Int=1, depth:Int=1) {
    val cs = computeStyleManager.shader(this, "compute-style")
    cs.execute(width, height, depth)
}

