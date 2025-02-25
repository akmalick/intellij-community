// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing.roots;

import com.intellij.openapi.project.Project;
import com.intellij.util.indexing.roots.IndexableEntityProvider.ParentEntityDependent;
import com.intellij.util.indexing.roots.builders.IndexableIteratorBuilders;
import com.intellij.workspaceModel.storage.bridgeEntities.api.JavaResourceRootEntity;
import com.intellij.workspaceModel.storage.bridgeEntities.api.ModuleEntity;
import com.intellij.workspaceModel.storage.bridgeEntities.api.SourceRootEntity;
import com.intellij.workspaceModel.storage.url.VirtualFileUrl;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;

import static com.intellij.util.indexing.roots.JavaSourceRootIndexableEntityProvider.collectBuildersOnAddedEntityWithDataExtractor;
import static com.intellij.util.indexing.roots.JavaSourceRootIndexableEntityProvider.collectBuildersOnReplacedEntityWithDataExtractor;

class JavaResourceRootIndexableEntityProvider implements ParentEntityDependent<JavaResourceRootEntity, SourceRootEntity> {
  @Override
  public @NotNull Class<JavaResourceRootEntity> getEntityClass() {
    return JavaResourceRootEntity.class;
  }

  @Override
  public @NotNull Collection<? extends IndexableIteratorBuilder> getAddedEntityIteratorBuilders(@NotNull JavaResourceRootEntity entity,
                                                                                                @NotNull Project project) {
    return collectBuildersOnAddedEntityWithDataExtractor(entity, JavaResourceRootIndexableEntityProvider::getDataForBuilders);
  }

  @Override
  public @NotNull Collection<? extends IndexableIteratorBuilder> getReplacedEntityIteratorBuilders(@NotNull JavaResourceRootEntity oldEntity,
                                                                                                   @NotNull JavaResourceRootEntity newEntity) {
    return collectBuildersOnReplacedEntityWithDataExtractor(oldEntity, newEntity,
                                                            JavaResourceRootIndexableEntityProvider::getDataForBuilders);
  }

  @NotNull
  private static Pair<VirtualFileUrl, ModuleEntity> getDataForBuilders(@NotNull JavaResourceRootEntity entity) {
    SourceRootEntity sourceRootEntity = entity.getSourceRoot();
    return new Pair<>(sourceRootEntity.getUrl(), sourceRootEntity.getContentRoot().getModule());
  }


  @Override
  public @NotNull Class<SourceRootEntity> getParentEntityClass() {
    return SourceRootEntity.class;
  }

  @Override
  public @NotNull Collection<? extends IndexableIteratorBuilder> getReplacedParentEntityIteratorBuilder(@NotNull SourceRootEntity oldEntity,
                                                                                                        @NotNull SourceRootEntity newEntity,
                                                                                                        @NotNull Project project) {
    if (oldEntity.getJavaResourceRoots().equals(newEntity.getJavaResourceRoots())) return Collections.emptyList();
    return IndexableIteratorBuilders.INSTANCE.forModuleRoots(newEntity.getContentRoot().getModule().getPersistentId(), newEntity.getUrl());
  }
}
