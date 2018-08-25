/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.klib

// TODO: Extract `library` package as a shared jar?
import org.jetbrains.kotlin.backend.konan.library.KLIB_CURRENT_ABI_VERSION
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.konan.isKonanStdlib
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KLIB_FILE_EXTENSION_WITH_DOT
import org.jetbrains.kotlin.konan.library.createKonanLibrary
import org.jetbrains.kotlin.konan.library.noTargetResolver
import org.jetbrains.kotlin.konan.library.unpackZippedKonanLibraryTo
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.PlatformManager
import org.jetbrains.kotlin.konan.util.DependencyProcessor
import org.jetbrains.kotlin.konan.utils.KonanFactories.DefaultDeserializedDescriptorFactory
import org.jetbrains.kotlin.metadata.konan.KonanProtoBuf
import org.jetbrains.kotlin.serialization.konan.parseModuleHeader
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import java.lang.System.out
import kotlin.system.exitProcess

fun printUsage() {
    println("Usage: klib <command> <library> <options>")
    println("where the commands are:")
    println("\tinfo\tgeneral information about the library")
    println("\tinstall\tinstall the library to the local repository")
    println("\tcontents\tlist contents of the library")
    println("\tremove\tremove the library from the local repository")
    println("and the options are:")
    println("\t-repository <path>\twork with the specified repository")
    println("\t-target <name>\tinspect specifics of the given target")
}

private fun parseArgs(args: Array<String>): Map<String, List<String>> {
    val commandLine = mutableMapOf<String, MutableList<String>>()
    for (index in 0..args.size - 1 step 2) {
        val key = args[index]
        if (key[0] != '-') {
            throw IllegalArgumentException("Expected a flag with initial dash: $key")
        }
        if (index + 1 == args.size) {
            throw IllegalArgumentException("Expected an value after $key")
        }
        val value = listOf(args[index + 1])
        commandLine[key]?.addAll(value) ?: commandLine.put(key, value.toMutableList())
    }
    return commandLine
}


class Command(args: Array<String>){
    init {
        if (args.size < 2) {
            printUsage()
            exitProcess(0)
        }
    }
    val verb = args[0]
    val library = args[1]
    val options = parseArgs(args.drop(2).toTypedArray())
}

fun warn(text: String) {
    println("warning: $text")
}

fun error(text: String) {
    println("error: $text")
    exitProcess(1)
}

val defaultRepository = File(DependencyProcessor.localKonanDir.resolve("klib").absolutePath)

open class ModuleDeserializer(val library: ByteArray) {
    protected val moduleHeader: KonanProtoBuf.LinkDataLibrary
        get() = parseModuleHeader(library)

    val moduleName: String
        get() = moduleHeader.moduleName

    val packageFragmentNameList: List<String>
        get() = moduleHeader.packageFragmentNameList

}

class Library(val name: String, val requestedRepository: String?, val target: String) {

    val repository = requestedRepository?.File() ?: defaultRepository
    fun info() {
        val library = libraryInRepoOrCurrentDir(repository, name)
        val reader = createKonanLibrary(library, currentAbiVersion)
        val headerAbiVersion = reader.abiVersion
        val moduleName = ModuleDeserializer(reader.moduleHeaderData).moduleName

        println("")
        println("Resolved to: ${reader.libraryName.File().absolutePath}")
        println("Module name: $moduleName")
        println("ABI version: $headerAbiVersion")
        val targets = reader.targetList.joinToString(", ")
        print("Available targets: $targets\n")
    }

    fun install() {
        Library(File(name).name.removeSuffix(KLIB_FILE_EXTENSION_WITH_DOT), requestedRepository, target).remove(true)

        val klibFile = libraryInCurrentDir(name)
        val newLibDir = File(repository, klibFile.name.removeSuffix(KLIB_FILE_EXTENSION_WITH_DOT))

        klibFile.unpackZippedKonanLibraryTo(newLibDir)
    }

    fun remove(blind: Boolean = false) {
        if (!repository.exists) error("Repository does not exist: $repository")

        val libDir = try {
            val library = libraryInRepo(repository, name)
            if (blind) warn("Removing The previously installed $name from $repository.")
            library

        } catch (e: Throwable) {
            if (!blind) println(e.message)
            null

        }
        libDir?.deleteRecursively()
    }

    fun contents(output: Appendable = out) {

        val storageManager = LockBasedStorageManager()
        val library = createKonanLibrary(libraryInRepoOrCurrentDir(repository, name), currentAbiVersion)
        val versionSpec = LanguageVersionSettingsImpl(currentLanguageVersion, currentApiVersion)
        val module = DefaultDeserializedDescriptorFactory.createDescriptorAndNewBuiltIns(library, versionSpec, storageManager)

        val defaultModules = mutableListOf<ModuleDescriptorImpl>()
        if (!module.isKonanStdlib()) {
            val resolver = noTargetResolver(
                    emptyList(),
                    distributionKlib = Distribution().klib,
                    skipCurrentDir = true)
            resolver.defaultLinks(false, true)
                    .mapTo(defaultModules) {
                        DefaultDeserializedDescriptorFactory.createDescriptor(
                                createKonanLibrary(it, currentAbiVersion), versionSpec, storageManager, module.builtIns)
                    }
        }

        (defaultModules + module).let { allModules ->
            allModules.forEach { it.setDependencies(allModules) }
        }

        KlibPrinter(output).print(module)
    }
}

const val currentAbiVersion = KLIB_CURRENT_ABI_VERSION
val currentLanguageVersion = LanguageVersion.LATEST_STABLE
val currentApiVersion = ApiVersion.LATEST_STABLE

fun libraryInRepo(repository: File, name: String) =
        noTargetResolver(listOf(repository.absolutePath), skipCurrentDir = true).resolve(name)

fun libraryInCurrentDir(name: String) = noTargetResolver(emptyList()).resolve(name)

fun libraryInRepoOrCurrentDir(repository: File, name: String) =
        noTargetResolver(listOf(repository.absolutePath)).resolve(name)

fun main(args: Array<String>) {
    val command = Command(args)

    val targetManager = PlatformManager().targetManager(command.options["-target"]?.last())
    val target = targetManager.targetName

    val repository = command.options["-repository"]?.last()

    val library = Library(command.library, repository, target)

    warn("IMPORTANT: the library format is unstable now. It can change with any new git commit without warning!")

    when (command.verb) {
        "contents"  -> library.contents()
        "info"      -> library.info()
        "install"   -> library.install()
        "remove"    -> library.remove()
        else        -> error("Unknown command ${command.verb}.")
    }
}

