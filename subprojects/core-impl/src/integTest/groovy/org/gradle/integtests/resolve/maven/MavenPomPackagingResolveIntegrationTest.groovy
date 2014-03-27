/*
 * Copyright 2012 the original author or authors.
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
package org.gradle.integtests.resolve.maven

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.test.fixtures.maven.MavenHttpModule
import org.gradle.test.fixtures.maven.MavenHttpRepository
import spock.lang.FailsWith
import spock.lang.Issue

class MavenPomPackagingResolveIntegrationTest extends AbstractDependencyResolutionTest {
    MavenHttpRepository repo1
    MavenHttpRepository repo2
    MavenHttpModule projectARepo1
    MavenHttpModule projectARepo2

    public setup() {
        server.start()
        repo1 = mavenHttpRepo("repo1")
        repo2 = mavenHttpRepo("repo2")
        projectARepo1 = repo1.module('group', 'projectA')
        projectARepo2 = repo2.module('group', 'projectA')
    }

    private void buildWithDependencies(def dependencies) {
        buildFile << """
repositories {
    maven { url '${repo1.uri}' }
    maven { url '${repo2.uri}' }
}
configurations { compile }
dependencies {
    $dependencies
}
task deleteDir(type: Delete) {
    delete 'libs'
}
task retrieve(type: Copy, dependsOn: deleteDir) {
    into 'libs'
    from configurations.compile
}
"""
    }

    def "includes jar artifact if present for pom with packaging of type 'pom'"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo2.hasPackaging("pom").publish()

        and:
        // First attempts to resolve in repo1
        projectARepo1.pom.expectGetMissing()
        projectARepo1.artifact.expectHeadMissing()

        projectARepo2.pom.expectGet()
        projectARepo2.artifact.expectHead()
        projectARepo2.artifact.expectGet()

        and:
        run 'retrieve'

        then:
        file('libs').assertHasDescendants('projectA-1.0.jar')

        when:
        server.resetExpectations()
        run 'retrieve'

        then: // Uses cached artifacts
        file('libs').assertHasDescendants('projectA-1.0.jar')
    }

    def "ignores missing jar artifact for pom with packaging of type 'pom'"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo1.hasPackaging("pom").publishPom()

        and:
        projectARepo1.pom.expectGet()
        projectARepo1.artifact.expectHeadMissing()

        and:
        run 'retrieve'

        then:
        file('libs').assertIsEmptyDir()

        when:
        server.resetExpectations()
        run 'retrieve'

        then: // Uses cached artifacts
        file('libs').assertIsEmptyDir()
    }

    def "will use jar artifact for pom with packaging that maps to jar"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo1.hasPackaging(packaging).publish()

        and:
        projectARepo1.pom.expectGet()
        projectARepo1.artifact.expectGet()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.jar')
        file('libs/projectA-1.0.jar').assertIsCopyOf(projectARepo1.artifactFile)

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'

        where:
        packaging << ['', 'jar', 'eclipse-plugin', 'bundle']
    }


    @Issue('GRADLE-2188')
    def "will use jar artifact for pom with packaging 'orbit'"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo1.hasPackaging('orbit').publish()

        and:
        projectARepo1.pom.expectGet()
        projectARepo1.artifact(type: 'orbit').expectHeadMissing()
        projectARepo1.artifact.expectGet()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.jar')
        file('libs/projectA-1.0.jar').assertIsCopyOf(projectARepo1.artifactFile)

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'
    }

    @Issue('GRADLE-2188')
    def "where 'module.custom' exists, will use it as main artifact for pom with packaging 'custom' and emit deprecation warning"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo1.hasPackaging("custom").hasType("custom").publish()

        and:
        projectARepo1.pom.expectGet()
        projectARepo1.artifact.expectHead()
        projectARepo1.artifact.expectGet()

        and:
        executer.withDeprecationChecksDisabled()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.custom')
        file('libs/projectA-1.0.custom').assertIsCopyOf(projectARepo1.artifactFile)

        and:
        result.output.contains("Relying on packaging to define the extension of the main artifact has been deprecated")

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'
    }

    def "fails and reports type-based location if neither packaging-based or type-based artifact can be located"() {
        when:
        buildWithDependencies("compile 'group:projectA:1.0'")
        projectARepo1.hasPackaging("custom").publishPom()

        and:
        projectARepo1.pom.expectGet()
        projectARepo1.artifact(type: 'custom').expectHeadMissing()
        projectARepo1.artifact(type: 'jar').expectGetMissing()

        then:
        fails 'retrieve'

        and:
        result.error.contains("Artifact 'group:projectA:1.0:projectA.jar' not found.")
    }

    def "will use non-jar dependency type to determine jar artifact location"() {
        when:
        buildWithDependencies("""
compile('group:projectA:1.0') {
    artifact {
        name = 'projectA'
        type = 'zip'
    }
}
""")
        projectARepo1.hasPackaging("custom").hasType("custom")
        projectARepo1.artifact(type: 'zip')
        projectARepo1.publish()

        and:
        projectARepo1.pom.expectGet()

        // TODO:GRADLE-2188 This call should not be required, since "type='zip'" on the dependency alleviates the need to check for the packaging artifact
        projectARepo1.artifact(type: 'custom').expectHeadMissing()
        projectARepo1.artifact(type: 'zip').expectGet()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.zip')
        file('libs/projectA-1.0.zip').assertIsCopyOf(projectARepo1.artifact(type: 'zip').file)

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'
    }

    def "will use non-jar maven dependency type to determine artifact location"() {
        when:
        buildWithDependencies("""
compile 'group:mavenProject:1.0'
""")
        def mavenProject = repo1.module('group', 'mavenProject', '1.0').hasPackaging('pom').dependsOn('group', 'projectA', '1.0', 'zip').publishPom()
        projectARepo1.hasPackaging("custom")
        projectARepo1.artifact(type: 'zip')
        projectARepo1.publish()

        and:
        mavenProject.pom.expectGet()
        mavenProject.artifact.expectHeadMissing()

        projectARepo1.pom.expectGet()
        // TODO:GRADLE-2188 This call should not be required, since "type='zip'" on the dependency alleviates the need to check for the packaging artifact
        projectARepo1.artifact(type: 'custom').expectHeadMissing()
        projectARepo1.artifact(type: 'zip').expectGet()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.zip')
        file('libs/projectA-1.0.zip').assertIsCopyOf(projectARepo1.artifact(type: 'zip').file)

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'
    }

    @FailsWith(value = AssertionError, reason = "Pending better fix for GRADLE-2188")
    def "does not emit deprecation warning if dependency type is used to locate artifact, even if custom packaging matches file extension"() {
        when:
        buildWithDependencies("""
compile('group:projectA:1.0') {
    artifact {
        name = 'projectA'
        type = 'zip'
    }
}
""")
        projectARepo1.hasPackaging("zip").hasType("zip").publish()

        and:
        projectARepo1.pom.expectGet()
        // TODO - should not need this head request
        projectARepo1.artifact.expectHead()
        projectARepo1.artifact.expectGet()

        then:
        succeeds 'retrieve'

        and:
        file('libs').assertHasDescendants('projectA-1.0.zip')
        file('libs/projectA-1.0.zip').assertIsCopyOf(projectARepo1.artifactFile)

        and: "Stop the http server here to allow failure to be declared (otherwise occurs in tearDown) - remove this when the test is fixed"
        server.stop()

        // Check caching
        when:
        server.resetExpectations()
        then:
        succeeds 'retrieve'
    }

}