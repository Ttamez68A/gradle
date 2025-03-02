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

package org.gradle.internal.component.model;

import org.gradle.api.artifacts.ArtifactView;

import java.util.Set;

/**
 * Metadata used to select the artifacts for a particular variant selected during graph resolution.
 */
public interface VariantArtifactSelectionMetadata {
    /**
     * The variants available for artifact selection when {@link ArtifactView.ViewConfiguration#withVariantReselection()} is enabled.
     */
    Set<? extends VariantResolveMetadata> getAllVariants();

    Set<? extends VariantResolveMetadata> getLegacyVariants();
}
