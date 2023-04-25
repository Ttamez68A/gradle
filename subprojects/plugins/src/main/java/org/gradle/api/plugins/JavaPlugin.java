/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.plugins;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.VerificationType;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRoles;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRolesForMigration;
import org.gradle.api.internal.artifacts.configurations.RoleBasedConfigurationContainerInternal;
import org.gradle.api.internal.component.SoftwareComponentContainerInternal;
import org.gradle.api.internal.plugins.DefaultArtifactPublicationSet;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.JvmConstants;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.internal.versionmapping.VersionMappingStrategyInternal;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.internal.publication.IvyPublicationInternal;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.plugins.PublishingPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;
import org.gradle.jvm.component.JvmFeature;
import org.gradle.jvm.component.internal.DefaultJvmFeature;
import org.gradle.jvm.component.internal.DefaultJvmSoftwareComponent;
import org.gradle.testing.base.TestingExtension;

import javax.inject.Inject;
import java.util.Collections;

import static org.gradle.api.plugins.JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME;

/**
 * <p>A {@link Plugin} which compiles and tests Java source, and assembles it into a JAR file.</p>
 *
 * This plugin creates a built-in {@link JvmTestSuite test suite} named {@code test} that represents the {@link Test} task for Java projects.
 *
 * @see <a href="https://docs.gradle.org/current/userguide/java_plugin.html">Java plugin reference</a>
 * @see <a href="https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html">JVM test suite plugin reference</a>
 */
public abstract class JavaPlugin implements Plugin<Project> {
    /**
     * The name of the task that processes resources.
     */
    public static final String PROCESS_RESOURCES_TASK_NAME = JvmConstants.PROCESS_RESOURCES_TASK_NAME;

    /**
     * The name of the lifecycle task which outcome is that all the classes of a component are generated.
     */
    public static final String CLASSES_TASK_NAME = JvmConstants.CLASSES_TASK_NAME;

    /**
     * The name of the task which compiles Java sources.
     */
    public static final String COMPILE_JAVA_TASK_NAME = JvmConstants.COMPILE_JAVA_TASK_NAME;

    /**
     * The name of the task which processes the test resources.
     */
    public static final String PROCESS_TEST_RESOURCES_TASK_NAME = JvmConstants.PROCESS_TEST_RESOURCES_TASK_NAME;

    /**
     * The name of the lifecycle task which outcome is that all test classes of a component are generated.
     */
    public static final String TEST_CLASSES_TASK_NAME = JvmConstants.TEST_CLASSES_TASK_NAME;

    /**
     * The name of the task which compiles the test Java sources.
     */
    public static final String COMPILE_TEST_JAVA_TASK_NAME = JvmConstants.COMPILE_TEST_JAVA_TASK_NAME;

    /**
     * The name of the task which triggers execution of tests.
     */
    public static final String TEST_TASK_NAME = JvmConstants.TEST_TASK_NAME;

    /**
     * The name of the task which generates the component main jar.
     */
    public static final String JAR_TASK_NAME = JvmConstants.JAR_TASK_NAME;

    /**
     * The name of the task which generates the component javadoc.
     */
    public static final String JAVADOC_TASK_NAME = JvmConstants.JAVADOC_TASK_NAME;

    /**
     * The name of the API configuration, where dependencies exported by a component at compile time should
     * be declared.
     *
     * @since 3.4
     */
    public static final String API_CONFIGURATION_NAME = JvmConstants.API_CONFIGURATION_NAME;

    /**
     * The name of the implementation configuration, where dependencies that are only used internally by
     * a component should be declared.
     *
     * @since 3.4
     */
    public static final String IMPLEMENTATION_CONFIGURATION_NAME = JvmConstants.IMPLEMENTATION_CONFIGURATION_NAME;

    /**
     * The name of the configuration to define the API elements of a component.
     * That is, the dependencies which are required to compile against that component.
     *
     * @since 3.4
     */
    public static final String API_ELEMENTS_CONFIGURATION_NAME = JvmConstants.API_ELEMENTS_CONFIGURATION_NAME;

    /**
     * The name of the configuration that is used to declare dependencies which are only required to compile a component,
     * but not at runtime.
     */
    public static final String COMPILE_ONLY_CONFIGURATION_NAME = JvmConstants.COMPILE_ONLY_CONFIGURATION_NAME;

    /**
     * The name of the configuration to define the API elements of a component that are required to compile a component,
     * but not at runtime.
     *
     * @since 6.7
     */
    public static final String COMPILE_ONLY_API_CONFIGURATION_NAME = JvmConstants.COMPILE_ONLY_API_CONFIGURATION_NAME;

    /**
     * The name of the runtime only dependencies configuration, used to declare dependencies
     * that should only be found at runtime.
     *
     * @since 3.4
     */
    public static final String RUNTIME_ONLY_CONFIGURATION_NAME = JvmConstants.RUNTIME_ONLY_CONFIGURATION_NAME;

    /**
     * The name of the runtime classpath configuration, used by a component to query its own runtime classpath.
     *
     * @since 3.4
     */
    public static final String RUNTIME_CLASSPATH_CONFIGURATION_NAME = JvmConstants.RUNTIME_CLASSPATH_CONFIGURATION_NAME;

    /**
     * The name of the runtime elements configuration, that should be used by consumers
     * to query the runtime dependencies of a component.
     *
     * @since 3.4
     */
    public static final String RUNTIME_ELEMENTS_CONFIGURATION_NAME = JvmConstants.RUNTIME_ELEMENTS_CONFIGURATION_NAME;

    /**
     * The name of the javadoc elements configuration.
     *
     * @since 6.0
     */
    public static final String JAVADOC_ELEMENTS_CONFIGURATION_NAME = JvmConstants.JAVADOC_ELEMENTS_CONFIGURATION_NAME;

    /**
     * The name of the sources elements configuration.
     *
     * @since 6.0
     */
    public static final String SOURCES_ELEMENTS_CONFIGURATION_NAME = JvmConstants.SOURCES_ELEMENTS_CONFIGURATION_NAME;

    /**
     * The name of the compile classpath configuration.
     *
     * @since 3.4
     */
    public static final String COMPILE_CLASSPATH_CONFIGURATION_NAME = JvmConstants.COMPILE_CLASSPATH_CONFIGURATION_NAME;

    /**
     * The name of the annotation processor configuration.
     *
     * @since 4.6
     */
    public static final String ANNOTATION_PROCESSOR_CONFIGURATION_NAME = JvmConstants.ANNOTATION_PROCESSOR_CONFIGURATION_NAME;

    /**
     * The name of the test implementation dependencies configuration.
     *
     * @since 3.4
     */
    public static final String TEST_IMPLEMENTATION_CONFIGURATION_NAME = JvmConstants.TEST_IMPLEMENTATION_CONFIGURATION_NAME;

    /**
     * The name of the configuration that should be used to declare dependencies which are only required
     * to compile the tests, but not when running them.
     */
    public static final String TEST_COMPILE_ONLY_CONFIGURATION_NAME = JvmConstants.TEST_COMPILE_ONLY_CONFIGURATION_NAME;

    /**
     * The name of the test runtime only dependencies configuration.
     *
     * @since 3.4
     */
    public static final String TEST_RUNTIME_ONLY_CONFIGURATION_NAME = JvmConstants.TEST_RUNTIME_ONLY_CONFIGURATION_NAME;

    /**
     * The name of the test compile classpath configuration.
     *
     * @since 3.4
     */
    public static final String TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME = JvmConstants.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME;

    /**
     * The name of the test annotation processor configuration.
     *
     * @since 4.6
     */
    public static final String TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME = JvmConstants.TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME;

    /**
     * The name of the test runtime classpath configuration.
     *
     * @since 3.4
     */
    public static final String TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME = JvmConstants.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME;

    private static final String SOURCE_ELEMENTS_VARIANT_NAME_SUFFIX = "SourceElements";

    private final ObjectFactory objectFactory;

    @Inject
    public JavaPlugin(ObjectFactory objectFactory) {
        this.objectFactory = objectFactory;
    }

    @Override
    public void apply(final Project project) {
        if (project.getPluginManager().hasPlugin("java-platform")) {
            throw new IllegalStateException("The \"java\" or \"java-library\" plugin cannot be applied together with the \"java-platform\" plugin. " +
                "A project is either a platform or a library but cannot be both at the same time.");
        }
        final ProjectInternal projectInternal = (ProjectInternal) project;

        project.getPluginManager().apply(JavaBasePlugin.class);
        project.getPluginManager().apply("org.gradle.jvm-test-suite");

        // Create the 'java' component.
        DefaultJvmSoftwareComponent component = objectFactory.newInstance(
            DefaultJvmSoftwareComponent.class, JvmConstants.JAVA_COMPONENT_NAME);
        JvmFeature mainFeature = configureMainFeature(project, component);
        configureBuiltInTest(project, mainFeature);
        project.getComponents().add(component);

        // Set the 'java' component as the project's default.
        Configuration defaultConfiguration = project.getConfigurations().getByName(Dependency.DEFAULT_CONFIGURATION);
        defaultConfiguration.extendsFrom(mainFeature.getRuntimeElementsConfiguration());
        ((SoftwareComponentContainerInternal) project.getComponents()).getMainComponent().convention(component);

        configureSourceSets(projectInternal);
        configureTestTaskOrdering(project.getTasks());
        configureBuild(project);
    }

    private static void configureSourceSets(ProjectInternal project) {
        // Register the project's source set output directories
        JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        BuildOutputCleanupRegistry buildOutputCleanupRegistry = project.getServices().get(BuildOutputCleanupRegistry.class);
        javaExtension.getSourceSets().all(sourceSet -> buildOutputCleanupRegistry.registerOutputs(sourceSet.getOutput()));
    }

    /**
     * Unless there are other concerns, we'd prefer to run jar tasks prior to test tasks, as this might offer a small performance improvement
     * for common usage.  In practice, running test tasks tends to take longer than building a jar; especially as a project matures. If tasks
     * in downstream projects require the jar from this project, and the jar and test tasks in this project are available to be run in either order,
     * running jar first so that other projects can continue executing tasks in parallel while this project runs its tests could be an improvement.
     * However, while we want to prioritize cross-project dependencies to maximize parallelism if possible, we don't want to add an explicit
     * dependsOn() relationship between the jar task and the test task, so that any projects which need to run test tasks first will not need modification.
     */
    private static void configureTestTaskOrdering(TaskContainer tasks) {
        TaskCollection<Jar> jarTasks = tasks.withType(Jar.class);
        tasks.withType(Test.class).configureEach(test -> test.shouldRunAfter(jarTasks));
    }

    // TODO: We should be configuring the main feature in the the sub-plugins, like application and java-library.
    // Since the feature itself represents the software product being built, the sub-plugins, which decide which
    // products are built, should be responsible for configuring the feature.
    private static JvmFeature configureMainFeature(Project project, DefaultJvmSoftwareComponent component) {

        ExtensionContainer extensions = project.getExtensions();
        JavaPluginExtension javaExtension = extensions.findByType(JavaPluginExtension.class);

        SourceSet mainSourceSet = javaExtension.getSourceSets().create(SourceSet.MAIN_SOURCE_SET_NAME);

        // We construct the feature manually instead of with component.getFeatures().create() since this feature
        // should have empty capabilities, representing the "implicit capability" as opposed to the default, which
        // derives the capabilities from the feature name.
        JvmFeature mainFeature = project.getObjects().newInstance(DefaultJvmFeature.class,
            SourceSet.MAIN_SOURCE_SET_NAME, mainSourceSet, Collections.emptyList(), "The main production code",
            project, ConfigurationRoles.CONSUMABLE, false
        );
        component.getFeatures().add(mainFeature);

        // TODO: This variant is very similar to the sourcesElements variant. We should see if we can
        // merge them in some manner. They both provide the same sources, except one provides the
        // jar and one provides the directories.
        RoleBasedConfigurationContainerInternal configurations = ((ProjectInternal) project).getConfigurations();
        createSourceElements(configurations, project.getProviders(), project.getObjects(), mainFeature);

        // Build the main jar when running `assemble`.
        extensions.getByType(DefaultArtifactPublicationSet.class)
            .addCandidate(mainFeature.getRuntimeElementsConfiguration().getArtifacts().iterator().next());

        configurePublishing(project.getPlugins(), extensions, mainFeature);
        configureDiagnostics(project, mainFeature);

        return mainFeature;
    }

    private static Configuration createSourceElements(RoleBasedConfigurationContainerInternal configurations, ProviderFactory providerFactory, ObjectFactory objectFactory, JvmFeature feature) {

        // TODO: Why are we using this non-standard name? For the `java` component, this
        // equates to `mainSourceElements` instead of `sourceElements` as one would expect.
        // Can we change this name without breaking compatibility? Is the variant name part
        // of the component's API?
        String variantName = feature.getSourceSet().getName() + SOURCE_ELEMENTS_VARIANT_NAME_SUFFIX;

        @SuppressWarnings("deprecation") Configuration variant = configurations.createWithRole(variantName, ConfigurationRolesForMigration.CONSUMABLE_BUCKET_TO_CONSUMABLE);
        variant.setDescription("List of source directories contained in the Main SourceSet.");
        variant.setVisible(false);
        variant.extendsFrom(feature.getImplementationConfiguration());

        variant.attributes(attributes -> {
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.VERIFICATION));
            attributes.attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objectFactory.named(VerificationType.class, VerificationType.MAIN_SOURCES));
        });

        variant.getOutgoing().artifacts(
            feature.getSourceSet().getAllSource().getSourceDirectories().getElements().flatMap(e -> providerFactory.provider(() -> e)),
            artifact -> artifact.setType(ArtifactTypeDefinition.DIRECTORY_TYPE)
        );

        return variant;
    }

    // TODO: This approach is not necessarily correct for non-main features. All publications will attempt to use the main feature's
    // compile and runtime classpaths for version mapping, even if a non-main feature is being published.
    private static void configurePublishing(PluginContainer plugins, ExtensionContainer extensions, JvmFeature mainFeature) {
        plugins.withType(PublishingPlugin.class, plugin -> {
            PublishingExtension publishing = extensions.getByType(PublishingExtension.class);

            SourceSet sourceSet = mainFeature.getSourceSet();

            // Set up the default configurations used when mapping to resolved versions
            publishing.getPublications().withType(IvyPublication.class, publication -> {
                VersionMappingStrategyInternal strategy = ((IvyPublicationInternal) publication).getVersionMappingStrategy();
                strategy.defaultResolutionConfiguration(Usage.JAVA_API, sourceSet.getCompileClasspathConfigurationName());
                strategy.defaultResolutionConfiguration(Usage.JAVA_RUNTIME, sourceSet.getRuntimeClasspathConfigurationName());
            });
            publishing.getPublications().withType(MavenPublication.class, publication -> {
                VersionMappingStrategyInternal strategy = ((MavenPublicationInternal) publication).getVersionMappingStrategy();
                strategy.defaultResolutionConfiguration(Usage.JAVA_API, sourceSet.getCompileClasspathConfigurationName());
                strategy.defaultResolutionConfiguration(Usage.JAVA_RUNTIME, sourceSet.getRuntimeClasspathConfigurationName());
            });
        });
    }

    private static void configureBuiltInTest(Project project, JvmFeature mainFeature) {
        TestingExtension testing = project.getExtensions().getByType(TestingExtension.class);
        final NamedDomainObjectProvider<JvmTestSuite> testSuite = testing.getSuites().register(DEFAULT_TEST_SUITE_NAME, JvmTestSuite.class, suite -> {
            final SourceSet testSourceSet = suite.getSources();
            ConfigurationContainer configurations = project.getConfigurations();

            Configuration testImplementationConfiguration = configurations.getByName(testSourceSet.getImplementationConfigurationName());
            Configuration testRuntimeOnlyConfiguration = configurations.getByName(testSourceSet.getRuntimeOnlyConfigurationName());
            Configuration testCompileClasspathConfiguration = configurations.getByName(testSourceSet.getCompileClasspathConfigurationName());
            Configuration testRuntimeClasspathConfiguration = configurations.getByName(testSourceSet.getRuntimeClasspathConfigurationName());

            // We cannot reference the main source set lazily (via a callable) since the IntelliJ model builder
            // relies on the main source set being created before the tests. So, this code here cannot live in the
            // JvmTestSuitePlugin and must live here, so that we can ensure we register this test suite after we've
            // created the main source set.
            final SourceSet mainSourceSet = mainFeature.getSourceSet();
            final FileCollection mainSourceSetOutput = mainSourceSet.getOutput();
            final FileCollection testSourceSetOutput = testSourceSet.getOutput();
            testSourceSet.setCompileClasspath(project.getObjects().fileCollection().from(mainSourceSetOutput, testCompileClasspathConfiguration));
            testSourceSet.setRuntimeClasspath(project.getObjects().fileCollection().from(testSourceSetOutput, mainSourceSetOutput, testRuntimeClasspathConfiguration));

            testImplementationConfiguration.extendsFrom(configurations.getByName(mainSourceSet.getImplementationConfigurationName()));
            testRuntimeOnlyConfiguration.extendsFrom(configurations.getByName(mainSourceSet.getRuntimeOnlyConfigurationName()));
        });

        // Force the realization of this test suite, targets and task
        testSuite.get();

        project.getTasks().named(JavaBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(testSuite));
    }

    private static void configureDiagnostics(Project project, JvmFeature mainFeature) {
        project.getTasks().withType(DependencyInsightReportTask.class).configureEach(task -> {
            new DslObject(task).getConventionMapping().map("configuration", mainFeature::getCompileClasspathConfiguration);
        });
    }

    private static void configureBuild(Project project) {
        project.getTasks().named(JavaBasePlugin.BUILD_NEEDED_TASK_NAME, task -> addDependsOnTaskInOtherProjects(task, true,
            JavaBasePlugin.BUILD_NEEDED_TASK_NAME, JvmConstants.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME));
        project.getTasks().named(JavaBasePlugin.BUILD_DEPENDENTS_TASK_NAME, task -> addDependsOnTaskInOtherProjects(task, false,
            JavaBasePlugin.BUILD_DEPENDENTS_TASK_NAME, JvmConstants.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME));
    }

    /**
     * Adds a dependency on tasks with the specified name in other projects.  The other projects are determined from
     * project lib dependencies using the specified configuration name. These may be projects this project depends on or
     * projects that depend on this project based on the useDependOn argument.
     *
     * @param task Task to add dependencies to
     * @param useDependedOn if true, add tasks from projects this project depends on, otherwise use projects that depend on this one.
     * @param otherProjectTaskName name of task in other projects
     * @param configurationName name of configuration to use to find the other projects
     */
    private static void addDependsOnTaskInOtherProjects(final Task task, boolean useDependedOn, String otherProjectTaskName, String configurationName) {
        Project project = task.getProject();
        final Configuration configuration = project.getConfigurations().getByName(configurationName);
        task.dependsOn(configuration.getTaskDependencyFromProjectDependency(useDependedOn, otherProjectTaskName));
    }

}
