package org.openrndr.extra.shadergenerator.phrases.dsl

import org.openrndr.extra.shadergenerator.phrases.dsl.functions.*
import org.openrndr.extra.shadergenerator.phrases.dsl.structs.Struct
import org.openrndr.extra.shadergenerator.phrases.dsl.structs.StructSymbol
import org.openrndr.math.*
import kotlin.reflect.KProperty

open class ShaderBuilder : Generator, Functions, DoubleFunctions, ArrayFunctions, Sampler2DFunctions, IntFunctions,
    Vector2Functions, Vector3Functions, Vector4Functions,
    IntVector2Functions {
    var code = ""
    var preamble = ""

    override fun emit(code: String) {
        this.code += code + "\n"
    }

    infix fun Int.until(to: Int): Range {
        val range = Range(startV = this, endV = to)
        return range
    }

    infix fun Symbol<Int>.until(to: Int): Range {
        val range = Range(startP = this, endV = to)
        return range
    }


    infix fun Symbol<Int>.until(to: Symbol<Int>): Range = Range(startP = this, endP = to)

    operator fun Symbol<Int>.rangeTo(to: Symbol<Int>): Range = Range(startP = this, endP = to + symbol<Int>("1"))
    operator fun Int.rangeTo(to: Symbol<Int>): Range = Range(startV = this, endP = to + symbol<Int>("1"))
    operator fun Symbol<Int>.rangeTo(to: Int): Range = Range(startP = this, endV = to - 1)

    operator fun Int.provideDelegate(thisRef: Any?, property: KProperty<*>): ConstantProperty<Int> {
        emit("int ${property.name} = $this;")
        return ConstantProperty("int")
    }

    inline operator fun <reified T : EuclideanVector<T>> T.provideDelegate(
        thisRef: Any?,
        property: KProperty<*>
    ): ConstantProperty<T> {
        emit("${staticType<T>()} ${property.name} = ${glsl(this)};")
        return ConstantProperty(staticType<T>())
    }

    operator fun Matrix44.provideDelegate(thisRef: Any?, property: KProperty<*>): ConstantProperty<Matrix44> {
        emit("mat4 ${property.name} = ${glsl(this)};")
        return ConstantProperty("mat4")
    }

    operator fun Matrix33.provideDelegate(thisRef: Any?, property: KProperty<*>): ConstantProperty<Matrix33> {
        emit("mat3 ${property.name} = $this;")
        return ConstantProperty("mat3")
    }


    operator fun Double.provideDelegate(thisRef: Any?, property: KProperty<*>): ConstantProperty<Double> {
        emit("float ${property.name} = $this;")
        return ConstantProperty("float")
    }


    inline operator fun <reified T> ArraySymbol<T>.provideDelegate(
        thisRef: Any?,
        property: KProperty<*>
    ): ArrayValueProperty<T> {
        if (name.isNotEmpty()) {
            emit("${staticType<T>()} ${property.name}[$length] = ${name};")
        } else {
            emit("${staticType<T>()} ${property.name}[$length];")
        }
        return ArrayValueProperty(this@ShaderBuilder, this.length, this.type)
    }

    inline operator fun <reified T> Symbol<T>.provideDelegate(thisRef: Any?, property: KProperty<*>): ValueProperty<T> {
        emit("$type ${property.name} = ${name};")
        return ValueProperty(type)
    }

    override fun emitPreamble(code: String) {
        this.preamble += code + "\n"
    }

    inline fun <reified T> variable(): VariableProperty<T> {
        val glslType = staticType<T>()
        return VariableProperty(this@ShaderBuilder, glslType)
    }

    inline fun <reified T> arrayVariable(length: Int): ArrayVariableProperty<T> {
        val glslType = staticType<T>()
        return ArrayVariableProperty(this@ShaderBuilder, length, glslType)
    }


    inline fun <reified T> array(length: Int): ArraySymbol<T> = arraySymbol("", length)


    inline fun <reified T> constant(): ConstantProperty<T> {
        return ConstantProperty(staticType<T>())
    }

    inline fun <reified T> output(): OutputProperty<T> {
        return OutputProperty(this@ShaderBuilder, staticType<T>())
    }

    class FunctionProperty<T, R>(
        name: String,
        private val generator: Generator,
        parameter0Type: String,
        private val returnType: String,
        f: ShaderBuilder.(Symbol<T>) -> Symbol<R>,
    ) {
        init {
            val sb = ShaderBuilder()
            val resultSym = sb.f(symbol("$0", "dc"))
            generator.emitPreamble(
                """$returnType ${name}($parameter0Type __x__) { 
${sb.code.replace("$0", "__x__").prependIndent("    ")}                    
    return ${resultSym.name.replace("$0", "__x__")};
}"""
            )
        }

        operator fun getValue(any: Any?, property: KProperty<*>): (Symbol<T>) -> FunctionSymbol1<T, R> {
            return { x -> FunctionSymbol1(p0 = x, function = "${property.name}($0)", type = returnType) }
        }
    }

    class StructFunctionProperty<T:Struct<T>, R>(
        name: String,
        private val generator: Generator,
        parameter0Type: String,
        private val returnType: String,
        f: ShaderBuilder.(T) -> Symbol<R>,
        val proto: T,
    ) {
        init {
            val sb = ShaderBuilder()
//            val oldName = proto.hackName
//            println("storing hack name $oldName")
//            println("setting hack name to $0")
//            proto.hackName = "$0"
            val resultSym = sb.f(proto)
            generator.emitPreamble(
                """$returnType ${name}($parameter0Type __x__) { 
${sb.code.replace("$0", "__x__").prependIndent("    ")}                    
    return ${resultSym.name.replace("$0", "__x__")};

}"""
            )
//            println("restoring hackname to $oldName")
//            proto.hackName = oldName
        }

        operator fun getValue(any: Any?, property: KProperty<*>): (T) -> StructFunctionSymbol1<T, R> {


            return { x ->
                println("doing some function hacking for ${property.name} ${x==proto} ${x.hackName} " )
                println("storing hackname ${x.hackName}")
//                val oldName = x.hackName
                val fx = StructSymbol(generator, "$0", proto)

                val r = StructFunctionSymbol1<T, R>(p0 = fx, function = "${property.name}(${x.hackName})", type = returnType)
//                println("restoring hackname to $oldName")
//                x.hackName = oldName
                r

            }
        }
    }

    class FunctionPropertyCapture1<T, R>(
        name: String,
        private val generator: Generator,
        capture0Type: String,
        private val capture0Name: String,
        parameter0Type: String,
        private val returnType: String,
        f: ShaderBuilder.(Symbol<T>) -> Symbol<R>
    ) {
        init {
            val sb = ShaderBuilder()
            generator.emitPreamble(
                """$returnType ${name}($capture0Type $capture0Name, $parameter0Type x_) { 
${sb.code.prependIndent("    ")}                    
    return ${sb.f(symbol("$0", "")).name.replace("$0", "x_")};
}"""
            )
        }

        operator fun getValue(any: Any?, property: KProperty<*>): (Symbol<T>) -> FunctionSymbol1<T, R> =
            { x -> FunctionSymbol1(p0 = x, function = "${property.name}(${capture0Name}, $0)", type = returnType) }
    }

    class FunctionPropertyProvider<T, R>(
        private val generator: Generator,
        private val parameter0Type: String,
        private val returnType: String,
        private val f: ShaderBuilder.(Symbol<T>) -> Symbol<R>
    ) {
        operator fun provideDelegate(any: Any?, property: KProperty<*>): FunctionProperty<T, R> =
            FunctionProperty(property.name, generator, parameter0Type, returnType, f)
    }

    class StructFunctionPropertyProvider<T:Struct<T>, R>(
        private val generator: Generator,
        private val parameter0Type: String,
        private val returnType: String,
        private val f: ShaderBuilder.(T) -> Symbol<R>,
        private val proto: T,
    ) {
        operator fun provideDelegate(any: Any?, property: KProperty<*>): StructFunctionProperty<T, R> =
            StructFunctionProperty(property.name, generator, parameter0Type, returnType, f, proto)
    }


    class FunctionPropertyProviderCapture1<T, R>(
        private val generator: Generator,
        private val capture0Type: String,
        private val capture0Name: String,
        private val parameter0Type: String,
        private val returnType: String,
        private val f: ShaderBuilder.(Symbol<T>) -> Symbol<R>
    ) {
        operator fun provideDelegate(any: Any?, property: KProperty<*>): FunctionPropertyCapture1<T, R> {
            return FunctionPropertyCapture1(
                property.name,
                generator,
                capture0Type,
                capture0Name,
                parameter0Type,
                returnType,
                f
            )
        }
    }

    inline fun <reified T, reified R> function(noinline f: ShaderBuilder.(Symbol<T>) -> Symbol<R>): FunctionPropertyProvider<T, R> =
        FunctionPropertyProvider(
            this@ShaderBuilder,
            parameter0Type = staticType<T>(),
            returnType = staticType<R>(),
            f
        )


    inline fun <reified T:Struct<T>, reified R> StructSymbol<T>.Function(noinline f: ShaderBuilder.(T) -> Symbol<R>): StructFunctionPropertyProvider<T, R> =
        StructFunctionPropertyProvider(
            this@ShaderBuilder,
            parameter0Type = this.name,
            returnType = staticType<R>(),
            f,
            this.proto.create().apply { hackName = "$0" }
        )


    inline fun <reified C0, reified T, reified R> function(
        capture0: Symbol<C0>,
        noinline f: ShaderBuilder.(Symbol<T>) -> Symbol<R>
    ): FunctionPropertyProviderCapture1<T, R> = FunctionPropertyProviderCapture1(
        this@ShaderBuilder, staticType<C0>(), capture0.name,
        parameter0Type = staticType<T>(),
        returnType = staticType<R>(), f
    )

    inline fun <reified R> BoxRange2.weightedAverageBy(
        noinline itemFunction: (x: Symbol<IntVector2>) -> FunctionSymbol1<IntVector2, R>,
        noinline weightFunction: (x: Symbol<IntVector2>) -> FunctionSymbol1<IntVector2, Double>
    ): Symbol<R> {
        val id = symbol<IntVector2>("$0")
        val itemFunctionId = itemFunction(id)
        val weightFunctionId = weightFunction(id)
        val returnType = staticType<R>()
        val hash = hash(itemFunctionId.name, weightFunctionId.name, returnType)

        emitPreamble(
            """$returnType weightedAverageBy_${hash}(int startX, int endX, int startY, int endY) {
    $returnType sum = ${zero<R>()};
    float weight = 0.0;
    for (int j = startY; j < endY; ++j) {
       for (int i = startX; i < endX; ++i) {
           sum += ${itemFunctionId.function.replace("$0", "ivec2(i, j)")};
           weight += ${weightFunctionId.function.replace("$0", "ivec2(i, j)")};
       }
    }
    return sum / weight;
}"""
        )
        val startX = xrange.startP?.name ?: xrange.startV?.toString() ?: error("no startX")
        val startY = yrange.startP?.name ?: yrange.startV?.toString() ?: error("no startY")
        val endX = xrange.endP?.name ?: xrange.endV?.toString() ?: error("no endX")
        val endY = yrange.endP?.name ?: yrange.endV?.toString() ?: error("no endY")
        return symbol("weightedAverageBy_${hash}($startX, $endX, $startY, $endY)")
    }

    inline fun <reified R> BoxRange2.sumBy(noinline function: (x: Symbol<IntVector2>) -> FunctionSymbol1<IntVector2, R>): Symbol<R> {
        val id = symbol<IntVector2>("$0")
        val functionId = function(id)
        val returnType = staticType<R>()
        val hash = hash(functionId.name, this, returnType)
        emit(
            """$returnType sumBy_${hash}(int startX, int endX, int startY, int endY) {
    $returnType sum = ${zero<R>()};
    for (int j = startY; j < endY; ++j) {
       for (int i = startX; i < endX; ++i) {
           sum += ${functionId.function.replace("$0", "ivec2(i, j)")};
       }
    }
    return sum;
}"""
        )
        val startX = xrange.startP?.name ?: xrange.startV?.toString() ?: error("no startX")
        val startY = yrange.startP?.name ?: yrange.startV?.toString() ?: error("no startY")
        val endX = xrange.endP?.name ?: xrange.endV?.toString() ?: error("no endX")
        val endY = yrange.endP?.name ?: yrange.endV?.toString() ?: error("no endY")

        return symbol("sumBy_${hash}($startX, $endX, $startY, $endY)")
    }


    inline fun <reified R> Range.sumBy(noinline function: (x: Symbol<Int>) -> FunctionSymbol1<Int, R>): Symbol<R> {
        val id = symbol<Int>("$0")
        val functionId = function(id)
        val returnType = staticType<R>()
        val hash = hash(functionId.name, returnType)

        emitPreamble(
            """$returnType sumBy_${hash}(int start, int end) {
    $returnType sum = ${zero<R>()}; 
    for (int i = start; i < end; ++i) {
        sum += ${functionId.function.replace("$0", "i")};
    }
    return sum;
}"""
        )
        val start = startP?.name ?: startV?.toString() ?: error("no start")
        val end = endP?.name ?: endV?.toString() ?: error("no end")
        return symbol("sumBy_${hash}($start, $end)")
    }

    inline fun <reified T, reified R> ArraySymbol<T>.map(noinline function: (x: Symbol<T>) -> FunctionSymbol1<T, R>): ArraySymbol<R> {
        val id = symbol<T>("$0")
        val functionId = function(id)
        val returnType = staticType<R>()
        val inputType = staticType<T>()
        val hash = hash(functionId.name, returnType, inputType)

        emitPreamble(
            """$returnType[${length}] map_${hash}($inputType x[${length}]) {
    $returnType[$length] y;
    for (int i = 0; i < $length; ++i) {
        y[i] = ${functionId.function.replace("$0", "x[i]")};
    }
    return y;
}"""
        )
        return object : ArraySymbol<R> {
            override val name = "map_${hash}(${this@map.name})"
            override val length = this@map.length
            override val type = "float"
        }
    }
}

