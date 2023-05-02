/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.internal.shared.codegen

import org.gradle.kotlin.dsl.internal.shared.support.appendReproducibleNewLine
import org.gradle.plugin.use.PluginDependenciesSpec
import org.gradle.plugin.use.PluginDependencySpec
import java.io.File


fun writeBuiltinPluginIdExtensionsTo(file: File, gradleJars: Iterable<File>) {
    file.bufferedWriter().use { buf ->
        buf.appendReproducibleNewLine(fileHeader)
        pluginIdExtensionDeclarationsFor(gradleJars).forEach { extension ->
            buf.write("\n")
            buf.appendReproducibleNewLine(extension)
        }
    }
}


private
fun pluginIdExtensionDeclarationsFor(jars: Iterable<File>): Sequence<String> {
    val extendedType = PluginDependenciesSpec::class.qualifiedName!!
    val extensionType = PluginDependencySpec::class.qualifiedName!!
    return pluginExtensionsFrom(jars)
        .map { (memberName, pluginId, implementationClass) ->
            """
            /**
             * The builtin Gradle plugin implemented by [$implementationClass].
             *
             * @see $implementationClass
             */
            inline val $extendedType.`$memberName`: $extensionType
                get() = id("$pluginId")
            """.trimIndent()
        }
}


private
data class PluginExtension(
    val memberName: String,
    val pluginId: String,
    val implementationClass: String
)


private
fun pluginExtensionsFrom(jars: Iterable<File>): Sequence<PluginExtension> =
    jars.asSequence().flatMap(::pluginExtensionsFrom)


private
fun pluginExtensionsFrom(file: File): Sequence<PluginExtension> =
    pluginEntriesFrom(file)
        .asSequence()
        .map { (id, implementationClass) ->
            val simpleId = id.substringAfter("org.gradle.")
            // One plugin extension for the simple id, e.g., "application"
            PluginExtension(simpleId, id, implementationClass)
        }
