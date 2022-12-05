/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.execution.history.impl

import org.gradle.api.internal.file.TestFiles
import org.gradle.cache.CacheDecorator
import org.gradle.cache.PersistentExclusiveCache
import org.gradle.cache.internal.DefaultInMemoryCacheDecoratorFactory
import org.gradle.internal.serialize.BaseSerializerFactory
import org.gradle.internal.snapshot.FileSystemLocationSnapshot
import org.gradle.test.fixtures.file.CleanupTestDirectory
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.testfixtures.internal.TestInMemoryIndexedCache
import org.gradle.util.UsesNativeServices
import org.junit.Rule
import spock.lang.Specification

@CleanupTestDirectory(fieldName = "tmpDir")
@UsesNativeServices
class DefaultOutputFilesRepositoryTest extends Specification {

    @Rule
    public final TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider(getClass())

    def outputFiles = new TestInMemoryIndexedCache<String, Boolean>(BaseSerializerFactory.BOOLEAN_SERIALIZER)
    def cacheAccess = Stub(PersistentExclusiveCache) {
        createIndexedCache(_) >> outputFiles
    }
    def cacheDecorator = Mock(CacheDecorator)
    def inMemoryCacheDecoratorFactory = Stub(DefaultInMemoryCacheDecoratorFactory) {
        decorator(100000, true) >> cacheDecorator
    }
    def repository = new DefaultOutputFilesRepository(cacheAccess, inMemoryCacheDecoratorFactory)
    def fileSystemAccess = TestFiles.fileSystemAccess()

    def "should determine output files generated by Gradle"() {
        def outputFiles = [
            tmpDir.createDir('build/outputs/directory'),
            tmpDir.createFile('build/file'),
            tmpDir.file('build/not-existing'),
        ]

        when:
        repository.recordOutputs(outputFiles.collect { snapshot(it) })

        then:
        repository.isGeneratedByGradle(file('build'))
        repository.isGeneratedByGradle(file('build/outputs'))
        repository.isGeneratedByGradle(file('build/outputs/directory'))
        repository.isGeneratedByGradle(file('build/outputs/directory/subdir'))
        repository.isGeneratedByGradle(file('build/file'))
        repository.isGeneratedByGradle(file('build/file/other'))
        !repository.isGeneratedByGradle(file('build/other'))
        !repository.isGeneratedByGradle(file('build/outputs/other'))
        !repository.isGeneratedByGradle(file('build/not-existing'))
    }

    private File file(String path) {
        tmpDir.file(path).absoluteFile
    }

    private FileSystemLocationSnapshot snapshot(File file) {
        return fileSystemAccess.read(file.getAbsolutePath())
    }
}
