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
package org.gradle.jvm.component.internal;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ConfigurationPublications;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.capabilities.CapabilitiesMetadata;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.ConfigurationBackedConsumableVariant;
import org.gradle.api.component.ConsumableVariant;
import org.gradle.api.component.internal.DefaultConfigurationBackedConsumableVariant;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRole;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRoles;
import org.gradle.api.internal.artifacts.configurations.RoleBasedConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.DefaultSourceSet;
import org.gradle.api.internal.tasks.JvmConstants;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.internal.JvmPluginsHelper;
import org.gradle.api.plugins.jvm.internal.JvmPluginServices;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.jvm.component.JvmFeature;
import org.gradle.util.internal.TextUtil;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.List;

import static org.gradle.api.attributes.DocsType.JAVADOC;
import static org.gradle.api.attributes.DocsType.SOURCES;

/**
 * The default implementation of a {@link JvmFeature}, which is provided its backing
 * {@link SourceSet} upon initialization.
 *
 * <p>This feature can conditionally be configured to instead "extend" the production code. In that case, this
 * feature creates additional dependency configurations which live adjacent to the main source set's buckets,
 * which allow users to declare optional dependencies that the production code will compile and test against.
 * These extra dependencies are not published as part of the production variants, but as separate apiElements
 * and runtimeElements variants as defined by this feature. Then, users can declare a dependency on this
 * feature to get access to the optional dependencies.</p>
 *
 * <p>This "extending" functionality is fragile, in that it allows the production code to be compiled and
 * tested against dependencies which will not necessarily be present at runtime. For this reason, we are
 * planning to deprecate the "extending" functionality. For more information, see {@link #doExtendProductionCode}.</p>
 *
 * <p>For backwards compatibility reasons, when this feature is operating in the "extending" mode,
 * this feature is able to operate without the presence of the main feature, as long as the user
 * explicitly configures the project by manually creating a main and test source set themselves.
 * In that case, this feature will additionally create the jar and javadoc tasks which the main
 * source set would normally create. Additionally, this extension feature is able to create the
 * sources and javadoc variants that the main feature would also conditionally create.</p>
 */
public class DefaultJvmFeature implements JvmFeature {

    private final String name;
    private final SourceSet sourceSet;
    private final List<Capability> capabilities;
    private final String description;
    private final boolean extendProductionCode;
    private final ExtensiblePolymorphicDomainObjectContainer<ConsumableVariant> variants;

    // Services
    private final ProjectInternal project;
    private final ObjectFactory objectFactory;
    private final JvmPluginServices jvmPluginServices;

    // Tasks
    private final TaskProvider<Jar> jar;
    private final TaskProvider<JavaCompile> compileJava;

    // Dependency configurations
    private final Configuration implementation;
    private final Configuration runtimeOnly;
    private final Configuration compileOnly;

    // Configurable dependency configurations
    private Configuration compileOnlyApi;
    private Configuration api;

    // Resolvable configurations
    private final Configuration runtimeClasspath;
    private final Configuration compileClasspath;

    // Outgoing variants
    private final Configuration apiElements;
    private final Configuration runtimeElements;

    // Configurable outgoing variants
    private Configuration javadocElements;
    private Configuration sourcesElements;

    @Inject
    public DefaultJvmFeature(
        String name,
        SourceSet sourceSet,
        List<Capability> capabilities,
        String description,
        ProjectInternal project,
        // The elements configurations' roles should always be consumable only, but
        // some users of this class are still migrating towards that. In 9.0, we can remove this
        // parameter and hard-code the elements configurations' roles to consumable only.
        ConfigurationRole elementsConfigurationRole,
        boolean extendProductionCode
    ) {
        this.name = name;
        this.sourceSet = sourceSet;
        this.capabilities = capabilities;
        this.description = description;
        this.project = project;
        this.extendProductionCode = extendProductionCode;

        this.objectFactory = project.getObjects();
        this.jvmPluginServices = project.getServices().get(JvmPluginServices.class);

        this.variants = objectFactory.polymorphicDomainObjectContainer(ConsumableVariant.class);
        this.variants.registerFactory(ConfigurationBackedConsumableVariant.class, configName -> {
            // TODO: Should we create the configuration with a different name?
            // If the user creates an apiElements and the feature name is test should we use testApiElements for the configuration name?
            Configuration config = project.getConfigurations().createWithRole(configName, elementsConfigurationRole);
            capabilities.forEach(config.getOutgoing()::capability);
            return objectFactory.newInstance(DefaultConfigurationBackedConsumableVariant.class, config);
        });

        // TODO: Deprecate allowing user to extend main feature.
        if (extendProductionCode && !SourceSet.isMain(sourceSet)) {
            throw new GradleException("Cannot extend main feature if source set is not also main.");
        }

        RoleBasedConfigurationContainerInternal configurations = project.getConfigurations();
        TaskContainer tasks = project.getTasks();

        this.compileJava = tasks.named(sourceSet.getCompileJavaTaskName(), JavaCompile.class);
        this.jar = registerOrGetJarTask(sourceSet, tasks);

        // If extendProductionCode=false, the source set has already created these configurations.
        // If extendProductionCode=true, then we create new buckets and later update the main and
        // test source sets to extend from these buckets.
        this.implementation = bucket("Implementation", JvmConstants.IMPLEMENTATION_CONFIGURATION_NAME);
        this.compileOnly = bucket("Compile-only", JvmConstants.COMPILE_ONLY_CONFIGURATION_NAME);
        this.runtimeOnly = bucket("Runtime-only", JvmConstants.RUNTIME_ONLY_CONFIGURATION_NAME);

        this.runtimeClasspath = configurations.getByName(sourceSet.getRuntimeClasspathConfigurationName());
        this.compileClasspath = configurations.getByName(sourceSet.getCompileClasspathConfigurationName());

        PublishArtifact jarArtifact = new LazyPublishArtifact(jar, project.getFileResolver(), project.getTaskDependencyFactory());
        this.apiElements = createApiElements(configurations, jarArtifact, compileJava, elementsConfigurationRole);
        this.runtimeElements = createRuntimeElements(configurations, jarArtifact, compileJava, elementsConfigurationRole);
        variants.add(objectFactory.newInstance(DefaultConfigurationBackedConsumableVariant.class, apiElements));
        variants.add(objectFactory.newInstance(DefaultConfigurationBackedConsumableVariant.class, runtimeElements));

        if (extendProductionCode) {
            doExtendProductionCode();
        }

        JavaPluginExtension javaPluginExtension = project.getExtensions().findByType(JavaPluginExtension.class);
        JvmPluginsHelper.configureJavaDocTask("'" + name + "' feature", sourceSet, tasks, javaPluginExtension);
    }

    void doExtendProductionCode() {
        // This method is one of the primary reasons that we want to deprecate the "extending" behavior. It updates
        // the main source set and test source set to "extend" this feature. That means any dependencies declared on
        // this feature's dependency configurations will be available locally, during compilation and runtime, to the main
        // production code and default test suite. However, when publishing the production code, these dependencies will
        // not be included in its consumable variants. Therefore, the main code is compiled _and tested_ against
        // dependencies which will not necessarily be available at runtime when it is consumed from other projects
        // or in its published form.
        //
        // This leads to a case where, in order for the production code to not throw NoClassDefFoundErrors during runtime,
        // it must detect the presence of the dependencies added by this feature, and then conditionally enable and disable
        // certain optional behavior. We do not want to promote this pattern.
        //
        // A much safer pattern would be to create normal features as opposed to an "extending" feature. Then, the normal
        // feature would have a project dependency on the main feature. It would provide an extra jar with any additional code,
        // and also bring along any extra dependencies that code requires. The main feature would then be able to detect the
        // presence of the feature through some {@code ServiceLoader} mechanism, as opposed to detecting the existence of
        // dependencies directly.
        //
        // This pattern is also more flexible than the "extending" pattern in that it allows features to extend arbitrary
        // features as opposed to just the main feature.

        ConfigurationContainer configurations = project.getConfigurations();
        SourceSet mainSourceSet = project.getExtensions().findByType(JavaPluginExtension.class)
            .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        // Update the main feature's source set to extend our "extension" feature's buckets.
        configurations.getByName(mainSourceSet.getCompileClasspathConfigurationName()).extendsFrom(implementation, compileOnly);
        configurations.getByName(mainSourceSet.getRuntimeClasspathConfigurationName()).extendsFrom(implementation, runtimeOnly);
        // Update the default test suite's source set to extend our "extension" feature's buckets.
        configurations.getByName(JvmConstants.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME).extendsFrom(implementation);
        configurations.getByName(JvmConstants.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(implementation, runtimeOnly);
    }

    /**
     * Hack to allow us to create configurations for normal and "extending" features. This should go away.
     */
    private String getConfigurationName(String suffix) {
        if (extendProductionCode) {
            return name + StringUtils.capitalize(suffix);
        } else {
            return ((DefaultSourceSet) sourceSet).configurationNameOf(suffix);
        }
    }

    private static void addJarArtifactToConfiguration(Configuration configuration, PublishArtifact jarArtifact) {
        ConfigurationPublications publications = configuration.getOutgoing();

        // Configure an implicit variant
        publications.getArtifacts().add(jarArtifact);
        publications.getAttributes().attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE);
    }

    private Configuration createApiElements(
        RoleBasedConfigurationContainerInternal configurations,
        PublishArtifact jarArtifact,
        TaskProvider<JavaCompile> compileJava,
        ConfigurationRole elementsRole
    ) {
        String configName = getConfigurationName(JvmConstants.API_ELEMENTS_CONFIGURATION_NAME);
        Configuration apiElements = configurations.maybeCreateWithRole(configName, elementsRole, false, false);

        apiElements.setVisible(false);
        jvmPluginServices.useDefaultTargetPlatformInference(apiElements, compileJava);
        jvmPluginServices.configureAsApiElements(apiElements);
        capabilities.forEach(apiElements.getOutgoing()::capability);
        apiElements.setDescription("API elements for the '" + name + "' feature.");

        // Configure variants
        addJarArtifactToConfiguration(apiElements, jarArtifact);

        return apiElements;
    }

    private Configuration createRuntimeElements(
        RoleBasedConfigurationContainerInternal configurations,
        PublishArtifact jarArtifact,
        TaskProvider<JavaCompile> compileJava,
        ConfigurationRole elementsRole
    ) {
        String configName = getConfigurationName(JvmConstants.RUNTIME_ELEMENTS_CONFIGURATION_NAME);
        Configuration runtimeElements = configurations.maybeCreateWithRole(configName, elementsRole, false, false);

        runtimeElements.setVisible(false);
        jvmPluginServices.useDefaultTargetPlatformInference(runtimeElements, compileJava);
        jvmPluginServices.configureAsRuntimeElements(runtimeElements);
        capabilities.forEach(runtimeElements.getOutgoing()::capability);
        runtimeElements.setDescription("Runtime elements for the '" + name + "' feature.");

        runtimeElements.extendsFrom(implementation, runtimeOnly);

        // Configure variants
        addJarArtifactToConfiguration(runtimeElements, jarArtifact);
        jvmPluginServices.configureClassesDirectoryVariant(runtimeElements, sourceSet);
        jvmPluginServices.configureResourcesDirectoryVariant(runtimeElements, sourceSet);

        return runtimeElements;
    }

    @Nullable
    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void withApi() {
        this.api = bucket("API", JvmConstants.API_CONFIGURATION_NAME);
        this.compileOnlyApi = bucket("Compile-only API", JvmConstants.COMPILE_ONLY_API_CONFIGURATION_NAME);

        this.apiElements.extendsFrom(api, compileOnlyApi);
        this.implementation.extendsFrom(api);
        this.compileOnly.extendsFrom(compileOnlyApi);

        // TODO: Why do we not always do this? Why only when we have an API?
        jvmPluginServices.configureClassesDirectoryVariant(apiElements, sourceSet);

        if (extendProductionCode) {
            project.getConfigurations().getByName(JvmConstants.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME).extendsFrom(compileOnlyApi);
        }
    }

    @Override
    public void withJavadocJar() {
        if (javadocElements != null) {
            return;
        }
        this.javadocElements = JvmPluginsHelper.createDocumentationVariantWithArtifact(
            sourceSet.getJavadocElementsConfigurationName(),
            SourceSet.isMain(sourceSet) ? null : name,
            JAVADOC,
            capabilities,
            sourceSet.getJavadocJarTaskName(),
            project.getTasks().named(sourceSet.getJavadocTaskName()),
            project
        );

        variants.add(objectFactory.newInstance(DefaultConfigurationBackedConsumableVariant.class, javadocElements));
    }

    @Override
    public void withSourcesJar() {
        if (sourcesElements != null) {
            return;
        }
        this.sourcesElements = JvmPluginsHelper.createDocumentationVariantWithArtifact(
            sourceSet.getSourcesElementsConfigurationName(),
            SourceSet.isMain(sourceSet) ? null : name,
            SOURCES,
            capabilities,
            sourceSet.getSourcesJarTaskName(),
            sourceSet.getAllSource(),
            project
        );

        variants.add(objectFactory.newInstance(DefaultConfigurationBackedConsumableVariant.class, sourcesElements));
    }

    private Configuration bucket(String kind, String suffix) {
        String configName = getConfigurationName(suffix);
        Configuration configuration = project.getConfigurations().maybeCreateWithRole(configName, ConfigurationRoles.BUCKET, false, false);
        configuration.setDescription(kind + " dependencies for the '" + name + "' feature.");
        configuration.setVisible(false);
        return configuration;
    }

    private TaskProvider<Jar> registerOrGetJarTask(SourceSet sourceSet, TaskContainer tasks) {
        String jarTaskName = sourceSet.getJarTaskName();
        if (!tasks.getNames().contains(jarTaskName)) {
            return tasks.register(jarTaskName, Jar.class, jar -> {
                jar.setDescription("Assembles a jar archive containing the classes of the '" + name + "' feature.");
                jar.setGroup(BasePlugin.BUILD_GROUP);
                jar.from(sourceSet.getOutput());
                if (!capabilities.isEmpty()) {
                    jar.getArchiveClassifier().set(TextUtil.camelToKebabCase(name));
                }
            });
        }
        return tasks.named(jarTaskName, Jar.class);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ExtensiblePolymorphicDomainObjectContainer<ConsumableVariant> getVariants() {
        return variants;
    }

    @Override
    public CapabilitiesMetadata getCommonCapabilities() {
        return ImmutableCapabilities.of(capabilities);
    }

    @Override
    public TaskProvider<Jar> getJarTask() {
        return jar;
    }

    @Override
    public TaskProvider<JavaCompile> getCompileJavaTask() {
        return compileJava;
    }

    @Override
    public SourceSet getSourceSet() {
        return sourceSet;
    }

    @Override
    public Configuration getImplementationConfiguration() {
        return implementation;
    }

    @Override
    public Configuration getRuntimeOnlyConfiguration() {
        return runtimeOnly;
    }

    @Override
    public Configuration getCompileOnlyConfiguration() {
        return compileOnly;
    }

    @Override
    public Configuration getApiConfiguration() {
        return api;
    }

    @Override
    public Configuration getCompileOnlyApiConfiguration() {
        return compileOnlyApi;
    }

    @Override
    public Configuration getRuntimeClasspathConfiguration() {
        return runtimeClasspath;
    }

    @Override
    public Configuration getCompileClasspathConfiguration() {
        return compileClasspath;
    }

    @Override
    public Configuration getApiElementsConfiguration() {
        return apiElements;
    }

    @Override
    public Configuration getRuntimeElementsConfiguration() {
        return runtimeElements;
    }

    @Override
    public Configuration getJavadocElementsConfiguration() {
        return javadocElements;
    }

    @Override
    public Configuration getSourcesElementsConfiguration() {
        return sourcesElements;
    }

}
